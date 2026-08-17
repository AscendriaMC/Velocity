//! Multibuffer scheduling for a batch of outbound frames.
//!
//! [`crate::cfb8::encrypt8`] is a fixed 8-wide lockstep kernel: it advances
//! eight streams by the same number of bytes. Real batches are nothing like
//! that — a proxy event loop wakes up holding frames of wildly different sizes
//! for a number of connections that is rarely a multiple of eight. This module
//! is the adapter, following the pattern from Intel's multibuffer work: run all
//! active lanes to the *shortest* remaining boundary, then refill whichever
//! lanes drained and go again.
//!
//! Two decisions worth stating, both from the measured cost model. On a core
//! with one AES pipe the 8-wide kernel costs ~80 cycles per byte-position
//! regardless of how many lanes are doing useful work, while the serial kernel
//! costs ~45 cycles/byte per stream:
//!
//! * **One job left: run it serially.** 80 > 45, so a single stream is faster
//!   on the serial path than padded out to eight lanes.
//! * **Two or more: pad to eight.** 80 <= 45k for k >= 2, and the margin grows
//!   fast (k=8 is 80 versus 360).
//!
//! Padding lanes all point at one dummy stream and one shared scratch buffer.
//! They compute garbage over each other, which is exactly what padding means.

use crate::cfb8::{encrypt8, Cfb8, LANES};

/// Bound on how far a padded round runs before rechecking, so the shared
/// scratch buffer stays a fixed, stack-sized thing.
const PAD_CHUNK: usize = 1024;

/// One frame to encrypt: a stream and the bytes to push through it.
#[derive(Clone, Copy)]
pub struct Job {
    pub cipher: *mut Cfb8,
    pub buf: *mut u8,
    pub len: usize,
}

/// Encrypt every job, using all eight lanes wherever there is work to fill them.
///
/// # Safety
/// Every `cipher` must be a live encryptor and every `buf` writable for `len`.
/// **The `cipher` pointers must be pairwise distinct** — two lanes sharing a
/// stream would interleave its shift register and corrupt the connection. The
/// Java side guarantees this by emitting at most one frame per channel per
/// round; see `CipherBatchCoordinator`.
pub unsafe fn encrypt_batch(jobs: &[Job]) {
    // Fast paths worth having: a batch of one is the common case on a quiet
    // proxy, and it must not pay for any of the machinery below.
    if jobs.is_empty() {
        return;
    }
    if jobs.len() == 1 {
        let j = jobs[0];
        if j.len > 0 {
            (*j.cipher).process(core::slice::from_raw_parts_mut(j.buf, j.len));
        }
        return;
    }

    let backend = (*jobs[0].cipher).backend();
    // Padding targets. The dummy stream is built per call: key expansion is a
    // few hundred nanoseconds against a batch that is doing kilobytes of AES,
    // and it keeps this function free of shared mutable state.
    let mut dummy = Cfb8::with_backend(&[0u8; 16], true, backend);
    let mut scratch = [0u8; PAD_CHUNK];
    let dummy_ptr: *mut Cfb8 = &mut dummy;
    let scratch_ptr: *mut u8 = scratch.as_mut_ptr();

    let mut lane_cipher = [core::ptr::null_mut::<Cfb8>(); LANES];
    let mut lane_buf = [core::ptr::null_mut::<u8>(); LANES];
    let mut lane_left = [0usize; LANES];
    let mut next = 0usize;

    loop {
        // Refill drained lanes. Zero-length jobs are skipped outright.
        for l in 0..LANES {
            while lane_left[l] == 0 && next < jobs.len() {
                let j = jobs[next];
                next += 1;
                if j.len > 0 {
                    lane_cipher[l] = j.cipher;
                    lane_buf[l] = j.buf;
                    lane_left[l] = j.len;
                }
            }
        }

        let active = lane_left.iter().filter(|&&r| r > 0).count();
        if active == 0 {
            return;
        }

        if active == 1 {
            // Only reachable once the queue is drained, so finishing this lane
            // outright cannot starve the others.
            let l = lane_left.iter().position(|&r| r > 0).unwrap();
            (*lane_cipher[l]).process(core::slice::from_raw_parts_mut(lane_buf[l], lane_left[l]));
            lane_left[l] = 0;
            continue;
        }

        let mut n = lane_left.iter().filter(|&&r| r > 0).copied().min().unwrap();
        if active < LANES {
            n = n.min(PAD_CHUNK);
        }

        let mut cs = [dummy_ptr; LANES];
        let mut bs = [scratch_ptr; LANES];
        for l in 0..LANES {
            if lane_left[l] > 0 {
                cs[l] = lane_cipher[l];
                bs[l] = lane_buf[l];
            }
        }
        encrypt8(&cs, &bs, n);

        for l in 0..LANES {
            if lane_left[l] > 0 {
                lane_left[l] -= n;
                lane_buf[l] = lane_buf[l].add(n);
            }
        }
    }
}
