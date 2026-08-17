//! aarch64 AES-128-CFB8 kernels (ARMv8 crypto extension).
//!
//! Same shape as the x86 side; the two differences worth knowing:
//!   * `AESE` folds AddRoundKey into SubBytes/ShiftRows, so a round is
//!     `vaesmcq_u8(vaeseq_u8(state, rk[r]))` and the final AddRoundKey is a
//!     plain `veorq_u8` with `rk[10]`.
//!   * the shift-register update is `vextq_u8(s, vdupq_n_u8(c), 1)` — bytes
//!     1..16 of `s` followed by lane 0 of the second operand — which is exactly
//!     "drop the oldest byte, append `c`".
//!
//! 32 vector registers here versus 16 on SSE, so the multibuffer path has room
//! to spare.

use core::arch::aarch64::*;
use core::mem::MaybeUninit;

use crate::cfb8::{Cfb8, CHUNK, LANES};
use crate::key::{RoundKeys, ROUNDS};

#[inline(always)]
unsafe fn load_rk_ptr(p: *const u8) -> [uint8x16_t; ROUNDS] {
    let mut out = [vdupq_n_u8(0); ROUNDS];
    for i in 0..ROUNDS {
        out[i] = vld1q_u8(p.add(i * 16));
    }
    out
}

#[inline(always)]
unsafe fn load_rk(rk: &RoundKeys) -> [uint8x16_t; ROUNDS] {
    load_rk_ptr(rk.as_ptr())
}

macro_rules! enc_block {
    ($rk:expr, $b:expr) => {{
        let rk = &$rk;
        let mut t = vaesmcq_u8(vaeseq_u8($b, rk[0]));
        t = vaesmcq_u8(vaeseq_u8(t, rk[1]));
        t = vaesmcq_u8(vaeseq_u8(t, rk[2]));
        t = vaesmcq_u8(vaeseq_u8(t, rk[3]));
        t = vaesmcq_u8(vaeseq_u8(t, rk[4]));
        t = vaesmcq_u8(vaeseq_u8(t, rk[5]));
        t = vaesmcq_u8(vaeseq_u8(t, rk[6]));
        t = vaesmcq_u8(vaeseq_u8(t, rk[7]));
        t = vaesmcq_u8(vaeseq_u8(t, rk[8]));
        t = vaeseq_u8(t, rk[9]);
        veorq_u8(t, rk[10])
    }};
}

#[target_feature(enable = "neon,aes")]
pub unsafe fn encrypt(rkb: &RoundKeys, state: &mut [u8; 16], buf: &mut [u8]) {
    let rk = load_rk(rkb);
    let mut s = vld1q_u8(state.as_ptr());
    for b in buf.iter_mut() {
        let t = enc_block!(rk, s);
        // Same trick as the x86 side, and cheaper here: `vext` already takes
        // lane 0 of its second operand as the new last byte, so xoring the
        // plaintext into the keystream vector gives the register update
        // directly — no umov/dup round trip through a general register.
        let u = veorq_u8(t, vsetq_lane_u8(*b, vdupq_n_u8(0), 0));
        *b = vgetq_lane_u8(u, 0);
        s = vextq_u8(s, u, 1);
    }
    vst1q_u8(state.as_mut_ptr(), s);
}

/// See the x86 counterpart for why these are raw pointers.
#[target_feature(enable = "neon,aes")]
pub unsafe fn encrypt8(streams: &[*mut Cfb8; LANES], bufs: &[*mut u8; LANES], n: usize) {
    let mut rk = [[vdupq_n_u8(0); ROUNDS]; LANES];
    let mut s = [vdupq_n_u8(0); LANES];
    let mut out = [core::ptr::null_mut::<u8>(); LANES];
    for i in 0..LANES {
        rk[i] = load_rk_ptr(core::ptr::addr_of!((*streams[i]).rk) as *const u8);
        s[i] = vld1q_u8(core::ptr::addr_of!((*streams[i]).state) as *const u8);
        out[i] = bufs[i];
    }

    for pos in 0..n {
        let mut t = [vdupq_n_u8(0); LANES];
        for i in 0..LANES {
            t[i] = vaesmcq_u8(vaeseq_u8(s[i], rk[i][0]));
        }
        for r in 1..(ROUNDS - 2) {
            for i in 0..LANES {
                t[i] = vaesmcq_u8(vaeseq_u8(t[i], rk[i][r]));
            }
        }
        for i in 0..LANES {
            let x = veorq_u8(vaeseq_u8(t[i], rk[i][ROUNDS - 2]), rk[i][ROUNDS - 1]);
            let p = out[i].add(pos);
            let u = veorq_u8(x, vsetq_lane_u8(*p, vdupq_n_u8(0), 0)); // see encrypt
            *p = vgetq_lane_u8(u, 0);
            s[i] = vextq_u8(s[i], u, 1);
        }
    }

    for i in 0..LANES {
        vst1q_u8(core::ptr::addr_of_mut!((*streams[i]).state) as *mut u8, s[i]);
    }
}

/// See the x86 counterpart for why decryption needs a scratch copy at all.
#[target_feature(enable = "neon,aes")]
pub unsafe fn decrypt(rkb: &RoundKeys, state: &mut [u8; 16], buf: &mut [u8]) {
    let rk = load_rk(rkb);
    let mut scratch = MaybeUninit::<[u8; 16 + CHUNK]>::uninit();
    let win = scratch.as_mut_ptr() as *mut u8;
    let mut s = vld1q_u8(state.as_ptr());

    for chunk in buf.chunks_mut(CHUNK) {
        let n = chunk.len();
        vst1q_u8(win, s);
        core::ptr::copy_nonoverlapping(chunk.as_ptr(), win.add(16), n);
        let out = chunk.as_mut_ptr();

        let mut i = 0usize;
        while i + 8 <= n {
            let p = win.add(i);
            let mut b0 = vld1q_u8(p.add(0));
            let mut b1 = vld1q_u8(p.add(1));
            let mut b2 = vld1q_u8(p.add(2));
            let mut b3 = vld1q_u8(p.add(3));
            let mut b4 = vld1q_u8(p.add(4));
            let mut b5 = vld1q_u8(p.add(5));
            let mut b6 = vld1q_u8(p.add(6));
            let mut b7 = vld1q_u8(p.add(7));
            macro_rules! round {
                ($k:expr) => {
                    b0 = vaesmcq_u8(vaeseq_u8(b0, $k)); b1 = vaesmcq_u8(vaeseq_u8(b1, $k));
                    b2 = vaesmcq_u8(vaeseq_u8(b2, $k)); b3 = vaesmcq_u8(vaeseq_u8(b3, $k));
                    b4 = vaesmcq_u8(vaeseq_u8(b4, $k)); b5 = vaesmcq_u8(vaeseq_u8(b5, $k));
                    b6 = vaesmcq_u8(vaeseq_u8(b6, $k)); b7 = vaesmcq_u8(vaeseq_u8(b7, $k));
                };
            }
            round!(rk[0]);
            round!(rk[1]);
            round!(rk[2]);
            round!(rk[3]);
            round!(rk[4]);
            round!(rk[5]);
            round!(rk[6]);
            round!(rk[7]);
            round!(rk[8]);
            macro_rules! last {
                ($v:expr) => {
                    veorq_u8(vaeseq_u8($v, rk[9]), rk[10])
                };
            }
            *out.add(i) ^= vgetq_lane_u8(last!(b0), 0);
            *out.add(i + 1) ^= vgetq_lane_u8(last!(b1), 0);
            *out.add(i + 2) ^= vgetq_lane_u8(last!(b2), 0);
            *out.add(i + 3) ^= vgetq_lane_u8(last!(b3), 0);
            *out.add(i + 4) ^= vgetq_lane_u8(last!(b4), 0);
            *out.add(i + 5) ^= vgetq_lane_u8(last!(b5), 0);
            *out.add(i + 6) ^= vgetq_lane_u8(last!(b6), 0);
            *out.add(i + 7) ^= vgetq_lane_u8(last!(b7), 0);
            i += 8;
        }
        while i < n {
            let t = enc_block!(rk, vld1q_u8(win.add(i)));
            *out.add(i) ^= vgetq_lane_u8(t, 0);
            i += 1;
        }
        s = vld1q_u8(win.add(n));
    }
    vst1q_u8(state.as_mut_ptr(), s);
}
