//! AES-128-CFB8 for the Minecraft protocol.
//!
//! The protocol mandates AES-128-CFB8 in both directions with the key reused as
//! the IV, so the mode is not negotiable — the only freedom left is the
//! implementation. No libcrypto has an optimised CFB8 path (OpenSSL, BoringSSL
//! and AWS-LC all run a generic per-byte loop around an indirect block-function
//! call), which is why this crate exists rather than a different `-lcrypto`.
//!
//! What it exploits: CFB8 *encryption* is a serial dependency chain, but CFB8
//! *decryption* is not — see [`cfb8`] — and even encryption parallelises across
//! connections, which a proxy has by the hundred.
//!
//! Built as a `cdylib` it exports the three symbols Velocity's
//! `OpenSslCipherImpl` declares, so it is a drop-in for `velocity-cipher.so`.

#![allow(unsafe_op_in_unsafe_fn)]

pub mod backend;
pub mod batch;
pub mod cfb8;
pub mod key;
pub mod soft;

#[cfg(target_arch = "aarch64")]
pub mod neon;
#[cfg(target_arch = "x86_64")]
pub mod x86;

mod jni;

pub use backend::Backend;
pub use batch::{encrypt_batch, Job};
pub use cfb8::{encrypt8, Cfb8, LANES};
pub use key::RoundKeys;
