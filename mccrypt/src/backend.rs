//! Runtime backend selection.
//!
//! Velocity picks its cipher variant *before* it can ask us anything (see
//! `Natives.cipher` / `NativeCodeLoader`), and a missing CPU feature must not be
//! an `UnsatisfiedLinkError` — so the dispatch lives here, inside the library,
//! and always has a working answer.

use std::sync::OnceLock;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Backend {
    /// AES-NI + SSE4.1. Everything x86_64 since Westmere (2010).
    Aesni,
    /// VAES + AVX-512F: 4 CFB8 windows per zmm. Ice Lake+, Zen 4+.
    Vaes512,
    /// ARMv8 crypto extension (AESE/AESMC).
    Neon,
    /// Portable fallback.
    Soft,
}

impl Backend {
    pub fn name(self) -> &'static str {
        match self {
            Backend::Aesni => "aes-ni",
            Backend::Vaes512 => "vaes-512",
            Backend::Neon => "armv8-crypto",
            Backend::Soft => "portable",
        }
    }

    pub fn is_hardware(self) -> bool {
        !matches!(self, Backend::Soft)
    }

    /// The backend this process will use. Detected once, then cached.
    pub fn detect() -> Backend {
        static SELECTED: OnceLock<Backend> = OnceLock::new();
        *SELECTED.get_or_init(select)
    }

    /// Every backend that can actually run on this CPU, best first. Used by the
    /// test suite to run each vector through all of them, and by the bench.
    pub fn available() -> Vec<Backend> {
        let mut v = Vec::new();
        #[cfg(target_arch = "x86_64")]
        {
            if has_vaes512() {
                v.push(Backend::Vaes512);
            }
            if has_aesni() {
                v.push(Backend::Aesni);
            }
        }
        #[cfg(target_arch = "aarch64")]
        {
            if has_neon_crypto() {
                v.push(Backend::Neon);
            }
        }
        v.push(Backend::Soft);
        v
    }
}

#[cfg(target_arch = "x86_64")]
fn has_aesni() -> bool {
    std::arch::is_x86_feature_detected!("aes") && std::arch::is_x86_feature_detected!("sse4.1")
}

#[cfg(target_arch = "x86_64")]
fn has_vaes512() -> bool {
    has_aesni()
        && std::arch::is_x86_feature_detected!("vaes")
        && std::arch::is_x86_feature_detected!("avx512f")
}

#[cfg(target_arch = "aarch64")]
fn has_neon_crypto() -> bool {
    std::arch::is_aarch64_feature_detected!("aes") && std::arch::is_aarch64_feature_detected!("neon")
}

fn select() -> Backend {
    let hw = Backend::available()[0];
    // Escape hatch: `MCCRYPT_BACKEND=soft` forces the portable path. Only ever
    // *down*grades — asking for a backend this CPU lacks would be a segfault, so
    // anything other than "soft" just means "whatever hardware allows".
    let chosen = match std::env::var("MCCRYPT_BACKEND").ok().as_deref() {
        Some("soft") => Backend::Soft,
        Some("aesni") if hw == Backend::Vaes512 => Backend::Aesni,
        Some(_) | None => hw,
    };
    if std::env::var_os("MCCRYPT_VERBOSE").is_some() {
        eprintln!("[mccrypt] AES-128-CFB8 backend: {}", chosen.name());
    }
    chosen
}
