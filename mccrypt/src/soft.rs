//! Portable table-driven AES-128-CFB8.
//!
//! Two jobs:
//!   1. fallback when the CPU has no AES instructions (pre-2010 x86, ARMv8
//!      without the crypto extension). Slow — single-digit MB/s — but correct,
//!      and reaching it means the alternative was "proxy does not start".
//!   2. the differential-test oracle for the SIMD backends. Being a completely
//!      independent implementation (S-box + xtime, no AES instruction anywhere)
//!      is what makes that comparison worth anything.
//!
//! Not constant-time: `SBOX[x]` is a data-dependent table index. That is
//! acceptable *here* because the Minecraft protocol's use of AES-CFB8 with
//! IV == key is not secure against an attacker who can measure anything at all;
//! see the essay in Velocity's jni_cipher_openssl.c.

use crate::key::{RoundKeys, SBOX};

#[inline(always)]
fn xtime(b: u8) -> u8 {
    (b << 1) ^ (0x1b & (b >> 7).wrapping_neg())
}

#[inline(always)]
fn add_round_key(s: &mut [u8; 16], rk: &[u8; 16]) {
    for i in 0..16 {
        s[i] ^= rk[i];
    }
}

#[inline(always)]
fn sub_bytes(s: &mut [u8; 16]) {
    for b in s.iter_mut() {
        *b = SBOX[*b as usize];
    }
}

/// State is column-major: `s[r + 4c]`. Row `r` rotates left by `r` columns.
#[inline(always)]
fn shift_rows(s: &mut [u8; 16]) {
    let o = *s;
    for r in 1..4 {
        for c in 0..4 {
            s[r + 4 * c] = o[r + 4 * ((c + r) & 3)];
        }
    }
}

#[inline(always)]
fn mix_columns(s: &mut [u8; 16]) {
    for c in 0..4 {
        let i = 4 * c;
        let (a0, a1, a2, a3) = (s[i], s[i + 1], s[i + 2], s[i + 3]);
        let t = a0 ^ a1 ^ a2 ^ a3;
        s[i] = a0 ^ t ^ xtime(a0 ^ a1);
        s[i + 1] = a1 ^ t ^ xtime(a1 ^ a2);
        s[i + 2] = a2 ^ t ^ xtime(a2 ^ a3);
        s[i + 3] = a3 ^ t ^ xtime(a3 ^ a0);
    }
}

/// Raw AES-128 block encryption. CFB8 only ever needs the forward direction,
/// in both stream directions, so there is no inverse cipher in this crate.
pub fn encrypt_block(rk: &RoundKeys, block: &[u8; 16]) -> [u8; 16] {
    let mut s = *block;
    add_round_key(&mut s, rk.round(0));
    for r in 1..10 {
        sub_bytes(&mut s);
        shift_rows(&mut s);
        mix_columns(&mut s);
        add_round_key(&mut s, rk.round(r));
    }
    sub_bytes(&mut s);
    shift_rows(&mut s);
    add_round_key(&mut s, rk.round(10));
    s
}

pub fn encrypt(rk: &RoundKeys, state: &mut [u8; 16], buf: &mut [u8]) {
    for b in buf.iter_mut() {
        let ks = encrypt_block(rk, state)[0];
        let c = *b ^ ks;
        *b = c;
        state.copy_within(1..16, 0);
        state[15] = c;
    }
}

pub fn decrypt(rk: &RoundKeys, state: &mut [u8; 16], buf: &mut [u8]) {
    for b in buf.iter_mut() {
        let ks = encrypt_block(rk, state)[0];
        let c = *b;
        *b = c ^ ks;
        state.copy_within(1..16, 0);
        state[15] = c;
    }
}
