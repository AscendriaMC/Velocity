//! Randomised differential tests against the portable backend.
//!
//! `soft` is a wholly independent implementation — S-box and `xtime`, no AES
//! instruction anywhere — and it is pinned to OpenSSL by `tests/vectors.rs`.
//! That makes it a legitimate oracle for the SIMD kernels, where the interesting
//! bugs live at lane boundaries (the 8-wide pipeline, the 64-byte VAES stride,
//! the 4 KiB scratch chunking) rather than in the AES round function.
//!
//! Seeded xorshift instead of a proptest dependency: every failure prints a seed
//! and a length that reproduce it exactly, which is the part that mattered.

mod common;
use common::{to_hex, Rng};
use mccrypt::{cfb8, encrypt8, encrypt_batch, Backend, Cfb8, Job, LANES};

const SEED: u64 = 0x5eed_1234_abcd_0001;

/// Lengths that sit on the boundaries the kernels actually branch at.
fn edge_lengths() -> Vec<usize> {
    let mut v: Vec<usize> = (0..=72).collect();
    for base in [cfb8::CHUNK, 2 * cfb8::CHUNK, 64, 128, 256] {
        for d in [-2i64, -1, 0, 1, 2] {
            let n = base as i64 + d;
            if n > 0 {
                v.push(n as usize);
            }
        }
    }
    v.push(9001);
    v
}

fn run(backend: Backend, encrypt: bool, key: &[u8; 16], data: &[u8]) -> Vec<u8> {
    let mut buf = data.to_vec();
    Cfb8::with_backend(key, encrypt, backend).process(&mut buf);
    buf
}

#[test]
fn hardware_backends_match_portable_on_edge_lengths() {
    let mut rng = Rng::new(SEED);
    let hw: Vec<Backend> = Backend::available()
        .into_iter()
        .filter(|b| b.is_hardware())
        .collect();
    if hw.is_empty() {
        eprintln!("no hardware backend on this CPU; portable path only");
        return;
    }

    for len in edge_lengths() {
        let key = rng.key();
        let mut data = vec![0u8; len];
        rng.fill(&mut data);
        for &encrypt in &[true, false] {
            let want = run(Backend::Soft, encrypt, &key, &data);
            for &b in &hw {
                assert_eq!(
                    to_hex(&run(b, encrypt, &key, &data)),
                    to_hex(&want),
                    "{} disagrees with portable: len={} encrypt={}",
                    b.name(),
                    len,
                    encrypt
                );
            }
        }
    }
}

#[test]
fn hardware_backends_match_portable_under_random_splits() {
    let mut rng = Rng::new(SEED ^ 0x9e37);
    let hw: Vec<Backend> = Backend::available()
        .into_iter()
        .filter(|b| b.is_hardware())
        .collect();
    if hw.is_empty() {
        return;
    }

    for case in 0..200 {
        let len = 1 + rng.below(2048);
        let key = rng.key();
        let mut data = vec![0u8; len];
        rng.fill(&mut data);
        let encrypt = case % 2 == 0;

        let want = run(Backend::Soft, encrypt, &key, &data);

        for &b in &hw {
            let mut c = Cfb8::with_backend(&key, encrypt, b);
            let mut buf = data.clone();
            let mut off = 0;
            while off < len {
                let n = 1 + rng.below(len - off);
                c.process(&mut buf[off..off + n]);
                off += n;
            }
            assert_eq!(
                to_hex(&buf),
                to_hex(&want),
                "{} disagrees with portable under split writes: case={} len={} encrypt={}",
                b.name(),
                case,
                len,
                encrypt
            );
        }
    }
}

#[test]
fn roundtrip_across_backends() {
    let mut rng = Rng::new(SEED ^ 0xdead);
    let backends = Backend::available();
    for len in [1usize, 7, 64, 1500, cfb8::CHUNK + 33] {
        let key = rng.key();
        let mut pt = vec![0u8; len];
        rng.fill(&mut pt);
        for &enc_b in &backends {
            for &dec_b in &backends {
                let ct = run(enc_b, true, &key, &pt);
                let back = run(dec_b, false, &key, &ct);
                assert_eq!(
                    to_hex(&back),
                    to_hex(&pt),
                    "encrypt with {} then decrypt with {} lost data at len={}",
                    enc_b.name(),
                    dec_b.name(),
                    len
                );
            }
        }
    }
}

/// The multibuffer kernel must be indistinguishable from running each stream on
/// its own — it is the same wire format, just scheduled differently. This is the
/// invariant the batching encoder in step 2 rests on.
#[test]
fn encrypt8_matches_independent_streams() {
    let mut rng = Rng::new(SEED ^ 0xb00b);
    for backend in Backend::available() {
        for &n in &[1usize, 8, 63, 64, 65, 1024] {
            let keys: Vec<[u8; 16]> = (0..LANES).map(|_| rng.key()).collect();
            let mut datas: Vec<Vec<u8>> = (0..LANES)
                .map(|_| {
                    let mut d = vec![0u8; n];
                    rng.fill(&mut d);
                    d
                })
                .collect();

            let want: Vec<Vec<u8>> = (0..LANES)
                .map(|i| {
                    let mut b = datas[i].clone();
                    Cfb8::with_backend(&keys[i], true, backend).process(&mut b);
                    b
                })
                .collect();

            let mut ciphers: Vec<Cfb8> = keys
                .iter()
                .map(|k| Cfb8::with_backend(k, true, backend))
                .collect();

            {
                let mut cs = [core::ptr::null_mut::<Cfb8>(); LANES];
                let mut bs = [core::ptr::null_mut::<u8>(); LANES];
                for i in 0..LANES {
                    cs[i] = &mut ciphers[i];
                    bs[i] = datas[i].as_mut_ptr();
                }
                unsafe { encrypt8(&cs, &bs, n) };
            }

            for i in 0..LANES {
                assert_eq!(
                    to_hex(&datas[i]),
                    to_hex(&want[i]),
                    "{}: encrypt8 lane {} diverged at n={}",
                    backend.name(),
                    i,
                    n
                );
            }
        }
    }
}

/// The batch scheduler must be indistinguishable from encrypting each frame on
/// its own — same bytes on the wire, just scheduled differently. This is the
/// invariant the whole batching encoder rests on, so it is exercised across the
/// job counts that select different strategies inside the scheduler (1 = serial,
/// 2..7 = padded lanes, 8 = exact, >8 = refill) and with ragged lengths, which
/// is what a real event-loop wakeup looks like.
#[test]
fn batch_matches_per_stream_encryption() {
    let mut rng = Rng::new(SEED ^ 0xba7c);
    for backend in Backend::available() {
        for &count in &[1usize, 2, 3, 5, 7, 8, 9, 16, 23] {
            let keys: Vec<[u8; 16]> = (0..count).map(|_| rng.key()).collect();
            // Ragged, and deliberately including empty frames: the scheduler has
            // to skip those without consuming a lane.
            let lens: Vec<usize> = (0..count)
                .map(|i| if i % 7 == 3 { 0 } else { 1 + rng.below(3000) })
                .collect();
            let datas: Vec<Vec<u8>> = lens
                .iter()
                .map(|&n| {
                    let mut d = vec![0u8; n];
                    rng.fill(&mut d);
                    d
                })
                .collect();

            let want: Vec<Vec<u8>> = (0..count)
                .map(|i| {
                    let mut b = datas[i].clone();
                    Cfb8::with_backend(&keys[i], true, backend).process(&mut b);
                    b
                })
                .collect();

            let mut ciphers: Vec<Cfb8> = keys
                .iter()
                .map(|k| Cfb8::with_backend(k, true, backend))
                .collect();
            let mut got = datas.clone();
            let mut jobs: Vec<Job> = Vec::with_capacity(count);
            for i in 0..count {
                jobs.push(Job {
                    cipher: &mut ciphers[i],
                    buf: got[i].as_mut_ptr(),
                    len: got[i].len(),
                });
            }
            unsafe { encrypt_batch(&jobs) };

            for i in 0..count {
                assert_eq!(
                    to_hex(&got[i]),
                    to_hex(&want[i]),
                    "{}: batch of {} diverged on job {} (len {})",
                    backend.name(),
                    count,
                    i,
                    lens[i]
                );
            }
        }
    }
}

/// A stream split across several batches must still be one continuous stream —
/// the coordinator emits at most one frame per channel per round, so a channel
/// with three queued frames is three successive `encrypt_batch` calls.
#[test]
fn batch_preserves_stream_state_across_rounds() {
    let mut rng = Rng::new(SEED ^ 0x2222);
    for backend in Backend::available() {
        let key = rng.key();
        let mut whole = vec![0u8; 4000];
        rng.fill(&mut whole);

        let mut want = whole.clone();
        Cfb8::with_backend(&key, true, backend).process(&mut want);

        // Same stream, fed one frame per round alongside unrelated traffic.
        let mut c = Cfb8::with_backend(&key, true, backend);
        let mut other: Vec<Cfb8> = (0..3)
            .map(|_| Cfb8::with_backend(&rng.key(), true, backend))
            .collect();
        let mut got = whole.clone();
        let mut off = 0;
        let mut round = 0;
        while off < got.len() {
            let n = (1 + rng.below(900)).min(got.len() - off);
            let mut filler: Vec<Vec<u8>> = (0..3).map(|_| vec![0u8; 100 + round]).collect();
            let mut jobs = vec![Job {
                cipher: &mut c,
                buf: unsafe { got.as_mut_ptr().add(off) },
                len: n,
            }];
            for (j, o) in other.iter_mut().enumerate() {
                jobs.push(Job {
                    cipher: o,
                    buf: filler[j].as_mut_ptr(),
                    len: filler[j].len(),
                });
            }
            unsafe { encrypt_batch(&jobs) };
            off += n;
            round += 1;
        }
        assert_eq!(to_hex(&got), to_hex(&want), "{}: stream broken across batch rounds", backend.name());
    }
}
