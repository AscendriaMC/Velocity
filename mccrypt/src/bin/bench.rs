//! Throughput bench + self-check.
//!
//! Baseline to compare against, on the same box:
//!
//!     openssl speed -evp aes-128-cfb8
//!
//! Figures are per stream except `encrypt8`, which is aggregate over its 8
//! lanes — that is the number a proxy event loop actually cares about.

use std::time::{Duration, Instant};

use mccrypt::{encrypt8, encrypt_batch, Backend, Cfb8, Job, LANES};

const SIZES: [usize; 5] = [64, 256, 1024, 8192, 16384];
const KEY: [u8; 16] = *b"0123456789abcdef";

/// Measured core clock, in GHz.
///
/// Deliberately *not* the TSC: rdtsc ticks at the part's base frequency while
/// the core turbos, so a TSC-derived "cycles/byte" reads ~15% low and can land
/// below a throughput bound it cannot actually beat. A dependent `add` chain
/// retires exactly one per cycle on every core worth benchmarking, so timing one
/// against the wall clock gives the frequency the kernels really ran at — and it
/// works the same on aarch64.
fn core_ghz() -> f64 {
    // `black_box(x) + 1` is not the right tool: it forces the value through
    // memory, so the chain measures store-forwarding latency, not the clock.
    // A bare `add` in inline asm really is one cycle per link.
    #[inline(always)]
    unsafe fn dep_add(x: u64) -> u64 {
        let mut y = x;
        #[cfg(target_arch = "x86_64")]
        core::arch::asm!("add {0}, 1", inout(reg) y, options(nomem, nostack));
        #[cfg(target_arch = "aarch64")]
        core::arch::asm!("add {0}, {0}, #1", inout(reg) y,
                         options(nomem, nostack, preserves_flags));
        y
    }

    if cfg!(not(any(target_arch = "x86_64", target_arch = "aarch64"))) {
        return 0.0;
    }

    let iters: u64 = 100_000_000;
    let mut x = 0u64;
    let t0 = Instant::now();
    for _ in 0..iters {
        unsafe {
            x = dep_add(x);
            x = dep_add(x);
            x = dep_add(x);
            x = dep_add(x);
            x = dep_add(x);
            x = dep_add(x);
            x = dep_add(x);
            x = dep_add(x);
        }
    }
    std::hint::black_box(x);
    (iters * 8) as f64 / t0.elapsed().as_secs_f64() / 1e9
}

/// Run `f` until at least 250 ms have elapsed. Returns seconds per iteration.
fn time<F: FnMut()>(mut f: F) -> f64 {
    let mut iters: u64 = 1;
    loop {
        let t0 = Instant::now();
        for _ in 0..iters {
            f();
        }
        let dt = t0.elapsed();
        if dt >= Duration::from_millis(250) {
            return dt.as_secs_f64() / iters as f64;
        }
        iters *= 2;
    }
}

fn row(backend: Backend, label: &str, size: usize, secs: f64, bytes: usize, ghz: f64) {
    let bytes_per_sec = bytes as f64 / secs;
    print!(
        "  {:<9} {:<13} {:>6} B   {:>8.1} MB/s",
        backend.name(),
        label,
        size,
        bytes_per_sec / 1_000_000.0
    );
    if ghz > 0.0 {
        print!("   {:>6.1} cycles/byte", ghz * 1e9 / bytes_per_sec);
    }
    println!();
}

/// Cheap smoke test, so a bench run on unfamiliar hardware also proves the
/// kernel is correct there. Real coverage is `cargo test`.
fn self_check(backend: Backend) {
    let pt: Vec<u8> = (0..=255u8).collect();
    let mut ct = pt.clone();
    Cfb8::with_backend(&KEY, true, backend).process(&mut ct);
    assert_eq!(
        &ct[..4],
        &[0x72, 0x30, 0x1e, 0xd9],
        "{}: KAT failed, refusing to report numbers",
        backend.name()
    );
    let mut back = ct.clone();
    Cfb8::with_backend(&KEY, false, backend).process(&mut back);
    assert_eq!(back, pt, "{}: roundtrip failed", backend.name());
}

fn main() {
    // `bench [--hw] [size ...]` — the flag matrix drives this, so it needs to be
    // able to ask for one size on hardware backends only.
    let args: Vec<String> = std::env::args().skip(1).collect();
    let hw_only = args.iter().any(|a| a == "--hw");
    let want: Vec<usize> = args.iter().filter_map(|a| a.parse().ok()).collect();

    let ghz = core_ghz();
    println!(
        "mccrypt — backend selected in production here: {}",
        Backend::detect().name()
    );
    println!("core clock (measured): {:.2} GHz", ghz);
    println!("CFB8 needs 10 AES rounds/byte, so a core with a single AES pipe at");
    println!("one round/cycle floors at 10.0 cycles/byte; cores with more AES");
    println!("pipes (Apple, Zen, Ice Lake+) go below that.");
    println!();

    for backend in Backend::available() {
        if hw_only && !backend.is_hardware() {
            continue;
        }
        self_check(backend);
        // The portable path is ~50x slower; benching it at 16 KiB burns a minute
        // to tell us what we already know.
        let default: &[usize] = if backend.is_hardware() { &SIZES } else { &SIZES[..3] };
        let sizes: &[usize] = if want.is_empty() { default } else { &want };

        for &size in sizes {
            let mut buf = vec![0u8; size];

            let mut c = Cfb8::with_backend(&KEY, true, backend);
            row(backend, "encrypt", size, time(|| c.process(&mut buf)), size, ghz);

            let mut c = Cfb8::with_backend(&KEY, false, backend);
            row(backend, "decrypt", size, time(|| c.process(&mut buf)), size, ghz);

            let mut ciphers: Vec<Cfb8> = (0..LANES)
                .map(|_| Cfb8::with_backend(&KEY, true, backend))
                .collect();
            let mut bufs: Vec<Vec<u8>> = (0..LANES).map(|_| vec![0u8; size]).collect();
            let mut cs = [core::ptr::null_mut::<Cfb8>(); LANES];
            let mut bs = [core::ptr::null_mut::<u8>(); LANES];
            for i in 0..LANES {
                cs[i] = &mut ciphers[i];
                bs[i] = bufs[i].as_mut_ptr();
            }
            row(
                backend,
                "encrypt8/agg",
                size,
                time(|| unsafe { encrypt8(&cs, &bs, size) }),
                size * LANES,
                ghz,
            );
        }
        println!();

        // What the batching encoder will actually hand the scheduler: a varying
        // number of frames per event-loop wakeup, not a tidy multiple of eight.
        // k=1 is the quiet-proxy case and must not regress against `encrypt`.
        const BATCH_SIZE: usize = 1024;
        for k in [1usize, 2, 3, 5, 8, 16, 32, 64, 128, 256] {
            let mut ciphers: Vec<Cfb8> = (0..k)
                .map(|_| Cfb8::with_backend(&KEY, true, backend))
                .collect();
            let mut bufs: Vec<Vec<u8>> = (0..k).map(|_| vec![0u8; BATCH_SIZE]).collect();
            let mut jobs: Vec<Job> = Vec::with_capacity(k);
            for i in 0..k {
                jobs.push(Job {
                    cipher: &mut ciphers[i],
                    buf: bufs[i].as_mut_ptr(),
                    len: BATCH_SIZE,
                });
            }
            row(
                backend,
                &format!("batch/{}", k),
                BATCH_SIZE,
                time(|| unsafe { encrypt_batch(&jobs) }),
                BATCH_SIZE * k,
                ghz,
            );
        }
        println!();
    }
}
