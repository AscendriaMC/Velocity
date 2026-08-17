//! AES-128-CFB8, the mode the Minecraft protocol mandates in both directions
//! (with the key doubling as the IV — blame Mojang, see Velocity's C impl).
//!
//! CFB8 runs one full 10-round AES block operation per *byte*:
//!
//!   keystream_i = AES(register_i)[0]
//!   c_i         = p_i ^ keystream_i
//!   register_{i+1} = register_i[1..16] || c_i
//!
//! Encryption is latency-bound: the register ingests the ciphertext byte it just
//! produced, so the AES calls form one dependency chain (~10x AESENC latency per
//! byte). Decryption is not: `register_i` is exactly the sliding 16-byte window
//! `(IV || C)[i..i+16]`, and the whole ciphertext is on hand the moment the
//! packet arrives — so every block op is independent and can be pipelined.
//! That asymmetry is the entire reason this crate exists.

use crate::backend::Backend;
use crate::key::RoundKeys;
use crate::soft;

#[cfg(target_arch = "x86_64")]
use crate::x86;

#[cfg(target_arch = "aarch64")]
use crate::neon;

/// Decryption copies `state || ciphertext` into a scratch buffer so the sliding
/// windows stay readable after plaintext has been written back over the source.
/// Chunking at 4 KiB keeps that scratch on the stack for *any* input length —
/// no allocation on the hot path, no length-dependent behaviour change.
pub const CHUNK: usize = 4096;

/// Streams processed together by [`encrypt8`].
pub const LANES: usize = 8;

/// One CFB8 stream: one connection, one direction.
pub struct Cfb8 {
    pub(crate) rk: RoundKeys,
    pub(crate) state: [u8; 16],
    pub(crate) encrypt: bool,
    pub(crate) backend: Backend,
}

impl Cfb8 {
    /// `encrypt` picks the direction; the key is also the IV, as the protocol
    /// requires.
    pub fn new(key: &[u8; 16], encrypt: bool) -> Cfb8 {
        Cfb8::with_backend(key, encrypt, Backend::detect())
    }

    pub fn with_backend(key: &[u8; 16], encrypt: bool, backend: Backend) -> Cfb8 {
        Cfb8 {
            rk: RoundKeys::expand(key),
            state: *key,
            encrypt,
            backend,
        }
    }

    pub fn backend(&self) -> Backend {
        self.backend
    }

    pub fn is_encrypt(&self) -> bool {
        self.encrypt
    }

    /// Transform `buf` in place, advancing the stream. Splitting a stream across
    /// several calls is equivalent to one call over the concatenation.
    pub fn process(&mut self, buf: &mut [u8]) {
        if buf.is_empty() {
            return;
        }
        let (rk, state) = (&self.rk, &mut self.state);
        match (self.backend, self.encrypt) {
            #[cfg(target_arch = "x86_64")]
            (Backend::Aesni, true) | (Backend::Vaes512, true) => unsafe {
                // Encryption is a serial dependency chain; wider vectors buy
                // nothing for a single stream, so both x86 backends share it.
                x86::aesni_encrypt(rk, state, buf)
            },
            #[cfg(target_arch = "x86_64")]
            (Backend::Aesni, false) => unsafe { x86::aesni_decrypt(rk, state, buf) },
            #[cfg(target_arch = "x86_64")]
            (Backend::Vaes512, false) => unsafe { x86::vaes512_decrypt(rk, state, buf) },
            #[cfg(target_arch = "aarch64")]
            (Backend::Neon, true) => unsafe { neon::encrypt(rk, state, buf) },
            #[cfg(target_arch = "aarch64")]
            (Backend::Neon, false) => unsafe { neon::decrypt(rk, state, buf) },
            (_, true) => soft::encrypt(rk, state, buf),
            (_, false) => soft::decrypt(rk, state, buf),
        }
    }
}

impl Drop for Cfb8 {
    fn drop(&mut self) {
        for b in self.state.iter_mut() {
            unsafe { core::ptr::write_volatile(b, 0) };
        }
        // RoundKeys scrubs itself.
    }
}

/// Encrypt eight independent streams in lockstep.
///
/// Single-stream CFB8 encryption cannot be pipelined, but eight *different*
/// connections have eight independent dependency chains, which is enough to fill
/// the AES unit. This is the kernel the batch scheduler feeds.
///
/// Raw pointers because [`crate::batch`] deliberately aims idle lanes at one
/// shared dummy stream and scratch buffer; eight `&mut` would be unsound.
///
/// # Safety
/// All eight `streams` must be live encryptors sharing one backend, and all
/// eight `bufs` must be writable for `n` bytes. Lanes may alias only if the
/// caller does not care about their output (i.e. padding lanes).
pub unsafe fn encrypt8(streams: &[*mut Cfb8; LANES], bufs: &[*mut u8; LANES], n: usize) {
    if n == 0 {
        return;
    }
    debug_assert!(streams.iter().all(|&s| (*s).encrypt));
    match (*streams[0]).backend {
        #[cfg(target_arch = "x86_64")]
        Backend::Aesni | Backend::Vaes512 => x86::aesni_encrypt8(streams, bufs, n),
        #[cfg(target_arch = "aarch64")]
        Backend::Neon => neon::encrypt8(streams, bufs, n),
        _ => {
            for i in 0..LANES {
                let c = &mut *streams[i];
                soft::encrypt(&c.rk, &mut c.state, core::slice::from_raw_parts_mut(bufs[i], n));
            }
        }
    }
}
