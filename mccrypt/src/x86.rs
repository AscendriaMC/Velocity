//! x86_64 AES-128-CFB8 kernels: AES-NI baseline, VAES/AVX-512 decrypt.
//!
//! Layout note that governs every line below: the CFB8 shift register lives in
//! an xmm for its whole life. Memory byte 0 loads into lane 0, so "drop the
//! oldest byte and append `c`" — the spec's view of the register sliding over
//! `IV || C` — is `_mm_srli_si128(s, 1)` plus an insert at lane 15. The
//! keystream byte is lane 0 of the block output.

use core::arch::x86_64::*;
use core::mem::MaybeUninit;

use crate::cfb8::{Cfb8, CHUNK, LANES};
use crate::key::{RoundKeys, ROUNDS};

#[inline(always)]
unsafe fn load_rk_ptr(p: *const u8) -> [__m128i; ROUNDS] {
    let mut out = [_mm_setzero_si128(); ROUNDS];
    for i in 0..ROUNDS {
        // RoundKeys is `repr(align(16))`.
        out[i] = _mm_load_si128(p.add(i * 16) as *const __m128i);
    }
    out
}

#[inline(always)]
unsafe fn load_rk(rk: &RoundKeys) -> [__m128i; ROUNDS] {
    load_rk_ptr(rk.as_ptr())
}

/// Full AES-128 encryption of one block. A macro rather than an `#[inline]` fn
/// because `#[target_feature]` functions cannot carry `#[inline(always)]`, and a
/// call in the CFB8 inner loop would be measurable.
macro_rules! enc_block {
    ($rk:expr, $b:expr) => {{
        let rk = &$rk;
        let mut t = _mm_xor_si128($b, rk[0]);
        t = _mm_aesenc_si128(t, rk[1]);
        t = _mm_aesenc_si128(t, rk[2]);
        t = _mm_aesenc_si128(t, rk[3]);
        t = _mm_aesenc_si128(t, rk[4]);
        t = _mm_aesenc_si128(t, rk[5]);
        t = _mm_aesenc_si128(t, rk[6]);
        t = _mm_aesenc_si128(t, rk[7]);
        t = _mm_aesenc_si128(t, rk[8]);
        t = _mm_aesenc_si128(t, rk[9]);
        _mm_aesenclast_si128(t, rk[10])
    }};
}

// ---------------------------------------------------------------- encryption

/// Serial CFB8 encryption. Latency-bound by construction: byte `i+1`'s register
/// contains byte `i`'s ciphertext. ~10x AESENC latency per byte and there is no
/// way around it for a single stream — see [`aesni_encrypt8`] for the way around
/// it across streams.
#[target_feature(enable = "aes,sse4.1,ssse3")]
pub unsafe fn aesni_encrypt(rkb: &RoundKeys, state: &mut [u8; 16], buf: &mut [u8]) {
    let rk = load_rk(rkb);
    let mut s = _mm_loadu_si128(state.as_ptr() as *const __m128i);
    for b in buf.iter_mut() {
        let t = enc_block!(rk, s);
        // Everything below is on the critical path, so it stays in xmm. The
        // obvious spelling — pull the keystream byte out to a GPR, xor, push the
        // ciphertext byte back with pinsrb — costs ~5 cycles a byte in xmm<->GPR
        // latency on top of the 40 the AES chain already owes.
        //
        // Instead: `pv` carries the plaintext byte in lane 0 (off the critical
        // path, it depends only on the input), so lane 0 of `u` is the ciphertext
        // byte, and `palignr(u, s, 1)` *is* the shift-register update — bytes
        // 1..16 of `s` followed by byte 0 of `u`. Two 1-cycle ops instead of five.
        let pv = _mm_cvtsi32_si128(*b as i32);
        let u = _mm_xor_si128(t, pv);
        *b = _mm_cvtsi128_si32(u) as u8;
        s = _mm_alignr_epi8(u, s, 1);
    }
    _mm_storeu_si128(state.as_mut_ptr() as *mut __m128i, s);
}

/// Multibuffer encryption: eight independent streams advanced one byte each per
/// iteration. Eight dependency chains is enough to keep the AES unit fed, which
/// turns a latency problem into a throughput one.
/// Raw pointers rather than `&mut`, deliberately: the batch scheduler points
/// idle lanes at one shared scratch buffer and one shared dummy stream, which
/// aliasing `&mut` would make unsound. See [`crate::batch`].
#[target_feature(enable = "aes,sse4.1,ssse3")]
pub unsafe fn aesni_encrypt8(streams: &[*mut Cfb8; LANES], bufs: &[*mut u8; LANES], n: usize) {
    let mut rk = [[_mm_setzero_si128(); ROUNDS]; LANES];
    let mut s = [_mm_setzero_si128(); LANES];
    let mut out = [core::ptr::null_mut::<u8>(); LANES];
    for i in 0..LANES {
        rk[i] = load_rk_ptr(core::ptr::addr_of!((*streams[i]).rk) as *const u8);
        s[i] = _mm_loadu_si128(core::ptr::addr_of!((*streams[i]).state) as *const __m128i);
        out[i] = bufs[i];
    }

    for pos in 0..n {
        let mut t = [_mm_setzero_si128(); LANES];
        for i in 0..LANES {
            t[i] = _mm_xor_si128(s[i], rk[i][0]);
        }
        for r in 1..(ROUNDS - 1) {
            for i in 0..LANES {
                t[i] = _mm_aesenc_si128(t[i], rk[i][r]);
            }
        }
        for i in 0..LANES {
            t[i] = _mm_aesenclast_si128(t[i], rk[i][ROUNDS - 1]);
            let p = out[i].add(pos);
            let u = _mm_xor_si128(t[i], _mm_cvtsi32_si128(*p as i32));
            *p = _mm_cvtsi128_si32(u) as u8;
            s[i] = _mm_alignr_epi8(u, s[i], 1); // see aesni_encrypt
        }
    }

    for i in 0..LANES {
        _mm_storeu_si128(
            core::ptr::addr_of_mut!((*streams[i]).state) as *mut __m128i,
            s[i],
        );
    }
}

// ---------------------------------------------------------------- decryption

/// Decrypt `win[i..n]` (windows) into `out[i..n]` with xmm, eight blocks in
/// flight, serial for the last <8. `win` must hold `state || ciphertext` and be
/// readable for `n + 16` bytes; `out` holds the ciphertext being overwritten
/// with plaintext.
#[target_feature(enable = "aes,sse4.1")]
unsafe fn decrypt_span_xmm(
    rk: &[__m128i; ROUNDS],
    win: *const u8,
    out: *mut u8,
    mut i: usize,
    n: usize,
) {
    while i + 8 <= n {
        let p = win.add(i);
        let mut b0 = _mm_loadu_si128(p.add(0) as *const __m128i);
        let mut b1 = _mm_loadu_si128(p.add(1) as *const __m128i);
        let mut b2 = _mm_loadu_si128(p.add(2) as *const __m128i);
        let mut b3 = _mm_loadu_si128(p.add(3) as *const __m128i);
        let mut b4 = _mm_loadu_si128(p.add(4) as *const __m128i);
        let mut b5 = _mm_loadu_si128(p.add(5) as *const __m128i);
        let mut b6 = _mm_loadu_si128(p.add(6) as *const __m128i);
        let mut b7 = _mm_loadu_si128(p.add(7) as *const __m128i);
        macro_rules! all {
            ($op:ident, $k:expr) => {
                b0 = $op(b0, $k); b1 = $op(b1, $k); b2 = $op(b2, $k); b3 = $op(b3, $k);
                b4 = $op(b4, $k); b5 = $op(b5, $k); b6 = $op(b6, $k); b7 = $op(b7, $k);
            };
        }
        all!(_mm_xor_si128, rk[0]);
        all!(_mm_aesenc_si128, rk[1]);
        all!(_mm_aesenc_si128, rk[2]);
        all!(_mm_aesenc_si128, rk[3]);
        all!(_mm_aesenc_si128, rk[4]);
        all!(_mm_aesenc_si128, rk[5]);
        all!(_mm_aesenc_si128, rk[6]);
        all!(_mm_aesenc_si128, rk[7]);
        all!(_mm_aesenc_si128, rk[8]);
        all!(_mm_aesenc_si128, rk[9]);
        all!(_mm_aesenclast_si128, rk[10]);
        *out.add(i) ^= _mm_cvtsi128_si32(b0) as u8;
        *out.add(i + 1) ^= _mm_cvtsi128_si32(b1) as u8;
        *out.add(i + 2) ^= _mm_cvtsi128_si32(b2) as u8;
        *out.add(i + 3) ^= _mm_cvtsi128_si32(b3) as u8;
        *out.add(i + 4) ^= _mm_cvtsi128_si32(b4) as u8;
        *out.add(i + 5) ^= _mm_cvtsi128_si32(b5) as u8;
        *out.add(i + 6) ^= _mm_cvtsi128_si32(b6) as u8;
        *out.add(i + 7) ^= _mm_cvtsi128_si32(b7) as u8;
        i += 8;
    }
    while i < n {
        let t = enc_block!(*rk, _mm_loadu_si128(win.add(i) as *const __m128i));
        *out.add(i) ^= _mm_cvtsi128_si32(t) as u8;
        i += 1;
    }
}

/// Prepare the sliding-window scratch for one chunk and hand it to `body`.
///
/// Decryption needs `(state || C)[i..i+16]` for every `i`, but the plaintext is
/// written back over `C` — so the ciphertext gets copied into scratch first.
/// Chunking at [`CHUNK`] keeps that scratch a fixed stack buffer for any input
/// size: no allocation, no `len`-dependent code path. The copy is a memcpy
/// against ~10 AES rounds per byte; it does not show up in the profile.
macro_rules! chunked_decrypt {
    ($state:expr, $buf:expr, |$rk_win:ident, $out:ident, $n:ident| $body:block) => {{
        let mut scratch = MaybeUninit::<[u8; 16 + CHUNK]>::uninit();
        let $rk_win = scratch.as_mut_ptr() as *mut u8;
        let mut s = _mm_loadu_si128($state.as_ptr() as *const __m128i);
        for chunk in $buf.chunks_mut(CHUNK) {
            let $n = chunk.len();
            _mm_storeu_si128($rk_win as *mut __m128i, s);
            core::ptr::copy_nonoverlapping(chunk.as_ptr(), $rk_win.add(16), $n);
            let $out = chunk.as_mut_ptr();
            $body
            // Next register = the last 16 bytes of (state || C).
            s = _mm_loadu_si128($rk_win.add($n) as *const __m128i);
        }
        _mm_storeu_si128($state.as_mut_ptr() as *mut __m128i, s);
    }};
}

/// CFB8 decryption, AES-NI. Every keystream input is known up front, so this is
/// throughput-bound rather than latency-bound: ~8x the serial path.
#[target_feature(enable = "aes,sse4.1")]
pub unsafe fn aesni_decrypt(rkb: &RoundKeys, state: &mut [u8; 16], buf: &mut [u8]) {
    let rk = load_rk(rkb);
    chunked_decrypt!(state, buf, |win, out, n| {
        decrypt_span_xmm(&rk, win, out, 0, n);
    });
}

/// CFB8 decryption, VAES + AVX-512.
///
/// One zmm holds four CFB8 windows — but note *which* four: a 64-byte load at
/// offset `b` puts the windows for bytes `b`, `b+16`, `b+32`, `b+48` in its four
/// 128-bit lanes, not four consecutive ones. So eight loads at `b..b+8` cover
/// bytes `b..b+8`, `b+16..b+24`, `b+32..b+40`, `b+48..b+56`, and a second group
/// at `b+8..b+16` fills the gaps: 64 bytes per iteration, 32 blocks in flight,
/// 19 zmm live. Keystream extraction goes through a scratch store because
/// pulling byte 0 out of each 128-bit lane otherwise wants AVX512-VBMI2.
///
/// Verified on Zen 4 (EPYC 9254): byte-identical to OpenSSL and to the portable
/// backend across the stride boundaries, at ~3.9 cycles/byte versus ~7.6 for the
/// xmm path on the same core.
#[target_feature(enable = "avx512f,vaes,aes,sse4.1")]
pub unsafe fn vaes512_decrypt(rkb: &RoundKeys, state: &mut [u8; 16], buf: &mut [u8]) {
    let rk128 = load_rk(rkb);
    let mut rk = [_mm512_setzero_si512(); ROUNDS];
    for i in 0..ROUNDS {
        rk[i] = _mm512_broadcast_i32x4(rk128[i]);
    }

    chunked_decrypt!(state, buf, |win, out, n| {
        let mut i = 0usize;
        let mut ks = [0u8; 64];
        while i + 64 <= n {
            for half in 0..2usize {
                let base = i + half * 8;
                let p = win.add(base);
                let mut b0 = _mm512_loadu_si512(p.add(0) as *const _);
                let mut b1 = _mm512_loadu_si512(p.add(1) as *const _);
                let mut b2 = _mm512_loadu_si512(p.add(2) as *const _);
                let mut b3 = _mm512_loadu_si512(p.add(3) as *const _);
                let mut b4 = _mm512_loadu_si512(p.add(4) as *const _);
                let mut b5 = _mm512_loadu_si512(p.add(5) as *const _);
                let mut b6 = _mm512_loadu_si512(p.add(6) as *const _);
                let mut b7 = _mm512_loadu_si512(p.add(7) as *const _);
                macro_rules! all {
                    ($op:ident, $k:expr) => {
                        b0 = $op(b0, $k); b1 = $op(b1, $k); b2 = $op(b2, $k); b3 = $op(b3, $k);
                        b4 = $op(b4, $k); b5 = $op(b5, $k); b6 = $op(b6, $k); b7 = $op(b7, $k);
                    };
                }
                all!(_mm512_xor_si512, rk[0]);
                all!(_mm512_aesenc_epi128, rk[1]);
                all!(_mm512_aesenc_epi128, rk[2]);
                all!(_mm512_aesenc_epi128, rk[3]);
                all!(_mm512_aesenc_epi128, rk[4]);
                all!(_mm512_aesenc_epi128, rk[5]);
                all!(_mm512_aesenc_epi128, rk[6]);
                all!(_mm512_aesenc_epi128, rk[7]);
                all!(_mm512_aesenc_epi128, rk[8]);
                all!(_mm512_aesenc_epi128, rk[9]);
                all!(_mm512_aesenclast_epi128, rk[10]);

                macro_rules! spill {
                    ($k:expr, $v:expr) => {{
                        _mm512_storeu_si512(ks.as_mut_ptr() as *mut _, $v);
                        let o = base + $k;
                        *out.add(o) ^= ks[0];
                        *out.add(o + 16) ^= ks[16];
                        *out.add(o + 32) ^= ks[32];
                        *out.add(o + 48) ^= ks[48];
                    }};
                }
                spill!(0, b0);
                spill!(1, b1);
                spill!(2, b2);
                spill!(3, b3);
                spill!(4, b4);
                spill!(5, b5);
                spill!(6, b6);
                spill!(7, b7);
            }
            i += 64;
        }
        // <64 bytes left: xmm 8-way, then serial. Matters — a lot of Minecraft
        // packets never reach 64 bytes in the first place.
        decrypt_span_xmm(&rk128, win, out, i, n);
    });
}
