# mccrypt — AES-128-CFB8 for the Minecraft protocol

A drop-in replacement for `velocity-natives`' OpenSSL cipher. Built as a
`cdylib` it exports exactly the three symbols
`com.velocitypowered.natives.encryption.OpenSslCipherImpl` declares, so it
installs as `velocity-cipher.so` with **no Java changes**.

## Why this exists

The protocol mandates AES-128-CFB8 in both directions, with the key reused as
the IV. Clients are vanilla, so the mode is not negotiable — the only freedom
left is the implementation.

CFB8 runs a full 10-round AES block operation per *byte*. No libcrypto has an
optimised path for it: OpenSSL, BoringSSL and AWS-LC all run a generic per-byte
loop around an indirect block-function call, so swapping in a different
`-lcrypto` buys nothing. A bespoke kernel does, because of an asymmetry the
generic loop can't see:

- **Encryption is latency-bound.** The shift register ingests the ciphertext byte
  it just produced, so every block op depends on the last one. ~10x AESENC
  latency per byte, and no amount of vector width fixes it for one stream.
- **Decryption is not.** The register for byte `i` is exactly the sliding window
  `(IV || C)[i..i+16]`, and the whole ciphertext is in hand the moment the packet
  arrives. Every block op is independent — pipeline eight and the AES unit
  saturates.
- **Encryption parallelises across connections.** A proxy has hundreds of them,
  and eight independent streams give eight independent dependency chains. That
  is `encrypt8`, the kernel the batching encoder (step 2) will feed.

## Measured

Baseline in each table is `openssl speed -evp aes-128-cfb8` on that same box.
Core clocks are measured, not nameplate (see below). 8 KiB buffers.

**AMD EPYC 9254 (Zen 4), ~3.85 GHz, two AES pipes + VAES.** Shared VM, so ~10%
run-to-run spread; figures are the middle of two runs.

| path | throughput | cycles/byte | vs OpenSSL |
|---|---|---|---|
| OpenSSL EVP aes-128-cfb8 | 47.7 MB/s | ~81 | 1.0x |
| `encrypt` (serial, one stream) | 86 MB/s | 45.0 | **1.8x** |
| `decrypt`, AES-NI | 508 MB/s | 7.6 | **10.6x** |
| `decrypt`, VAES-512 | 975 MB/s | 3.9 | **20x** |
| `encrypt8` (8 streams, aggregate) | 400 MB/s | 9.5 | **8.4x** |

**Intel i7-6700 (Skylake), 3.60 GHz, one AES pipe, no VAES.**

| path | throughput | cycles/byte | vs OpenSSL |
|---|---|---|---|
| OpenSSL EVP aes-128-cfb8 | 54.3 MB/s | ~66 | 1.0x |
| `encrypt` (serial, one stream) | 80.4 MB/s | 44.8 | **1.48x** |
| `decrypt` (8-way pipelined) | 343.5 MB/s | 10.5 | **6.3x** |
| `encrypt8` (8 streams, aggregate) | 367.7 MB/s | 9.8 | **6.8x** |

CFB8 needs 10 AES rounds per byte, so a core with a single AES pipe at one
round/cycle cannot beat 10 cycles/byte — that is Skylake's hard floor for *any*
AES-CFB8 implementation, and both parallel paths sit on it. They are not "fast",
they are finished; the only way past is more AES pipes. Zen 4 demonstrates
exactly that: two pipes take the same xmm decrypt kernel to 7.6, and VAES-512
takes it to 3.9.

The serial path is bounded by AESENC *latency* instead — 10 rounds x 4 cycles =
40, against ~45 measured on both x86 parts — which is why it barely moves between
microarchitectures and why batching across connections, not vector width, is what
the outbound direction needs.

Apple M-series (ARMv8 crypto ext, 3.23 GHz): 114 MB/s encrypt (31.1 c/B),
1157 MB/s decrypt (3.1 c/B), 818 MB/s encrypt8 aggregate (4.4 c/B). Also below
10 c/B, for the same reason — several AES pipes.

### On measurement

`cargo run --release --bin bench` calibrates the core clock with an inline-asm
dependent `add` chain rather than reading the TSC. This matters: rdtsc ticks at
the part's *base* frequency while the core turbos, so a TSC-derived cycles/byte
reads ~15% low — low enough, on this box, to put `encrypt8` at "9.4 cycles/byte",
under a bound it cannot actually beat. Anything claiming to be at a hardware
limit has to be measured against the clock that was really running.

Build flags were measured, not assumed. Across `-C target-cpu=native`,
`-C target-feature=+avx`, thin vs fat LTO, 1 vs 16 codegen units and opt-level
2 vs 3, every path stayed within ~2% — noise. Hand-written intrinsics leave LLVM
almost nothing to decide, so the shipped profile is just fat LTO + one codegen
unit, and no CPU-specific builds are worth shipping.

The one change that did pay was algorithmic. The obvious way to advance the CFB8
shift register — extract the keystream byte to a general register, xor, push the
ciphertext byte back with `pinsrb` — puts ~5 cycles of xmm<->GPR latency on the
critical path of a chain that already owes 40. Keeping it in vector registers
(`palignr(u, s, 1)` *is* the register update; `vext` on ARM) removed 3.3
cycles/byte from serial encrypt (+7%) and ~1 from `encrypt8` (+10%), and on ARM
+23% on both.

## Backends

Selected at runtime, once, by CPU feature detection — a missing feature must
never become an `UnsatisfiedLinkError`, because Velocity picks its cipher variant
before it can ask us anything.

| backend | requires | notes |
|---|---|---|
| `aes-ni` | AES + SSE4.1 | every x86_64 since Westmere (2010) |
| `vaes-512` | VAES + AVX-512F | 4 CFB8 windows per zmm, decrypt only |
| `armv8-crypto` | ARMv8 AES ext | |
| `portable` | — | S-box + `xtime`; correct, slow, and the test oracle |

`MCCRYPT_BACKEND=soft` forces the portable path; `MCCRYPT_VERBOSE=1` prints the
selection to stderr (useful during rollout — Velocity's own log line will still
read "OpenSSL local", since installing under that name is what buys us the
zero-Java-changes property).

All four backends are hardware-verified: AES-NI and VAES-512 on Zen 4, AES-NI on
Skylake, ARMv8 on Apple silicon, portable everywhere. `MCCRYPT_BACKEND=aesni`
downgrades off VAES if a part ever turns out to misbehave.

One caveat not yet measured: on Intel AVX-512 parts, sustained 512-bit work can
pull the core clock down and that penalty would land on the whole proxy, not just
the cipher. VAES is integer-domain (the light license level) so the effect should
be small, but if mccrypt ever runs on Ice Lake/Sapphire Rapids in anger, check it
— a 256-bit VAES path would be the fix.

## Correctness

`cargo test` — everything runs on every backend the host CPU supports.

- **KAT vs OpenSSL.** Vectors generated by `openssl enc -aes-128-cfb8` (3.5.5) —
  the exact code path being replaced. If these go red the proxy is emitting a
  stream clients cannot read.
- **FIPS-197 C.1** for the key schedule and raw block function.
- **Differential vs `portable`** across the lengths the kernels branch at: the
  8-wide pipeline boundary, the 64-byte VAES stride, the 4 KiB scratch chunking,
  and every length from 0 to 72.
- **Split-call state**, fixed and random split points — netty hands over whatever
  it happened to read, so stream state has to survive call boundaries exactly.
- **`encrypt8` == 8 independent streams**, which is the invariant the batching
  encoder will rest on.
- **`jvmtest/`** — end-to-end through the real JNI ABI: symbol resolution, raw
  off-heap addresses, in-place and out-of-place, the `IllegalArgumentException`
  contract on a bad key length, all cross-checked against the JDK's own
  `AES/CFB8/NoPadding`.

  ```
  javac -d jvmtest/out jvmtest/com/velocitypowered/natives/encryption/CipherAbiTest.java
  java --enable-native-access=ALL-UNNAMED -cp jvmtest/out \
      com.velocitypowered.natives.encryption.CipherAbiTest \
      ../native/src/main/resources/linux_x86_64/velocity-cipher.so
  ```

Seeded xorshift rather than a `proptest` dependency: failures reproduce from a
printed seed and length, which was the part that mattered, and the crate keeps a
single dependency (`jni-sys`, types only).

## Building

```sh
./build-native.sh        # host
./build-native.sh all    # host + Linux x86_64/aarch64 via cargo-zigbuild
```

Linux artifacts are cross-built against glibc 2.17 (the produced `.so` needs no
symbol newer than GLIBC_2.14), matching the portability of upstream's
Ubuntu-20.04-built natives.

Installed as `<platform>/velocity-cipher.so`. `Natives.java` tries that name
*first* and falls through to the OpenSSL-built variants if it fails to load, so
the old path stays a live safety net — delete the file and the proxy is back on
OpenSSL with no rebuild.

**musl is not covered yet.** `NativeConstraints` routes musl hosts only to the
`-ossl30x-musl` names, so shipping there means either clobbering an
OpenSSL-named file or adding a variant to `Natives.java`. Alpine deployments stay
on OpenSSL for now.

## Not constant-time

`portable` indexes an S-box with secret data, and the SIMD paths leak length. This
is fine *here*: the protocol's AES-CFB8-with-IV-equal-to-key is not secure against
an attacker who can measure anything at all. See the essay in Velocity's
`jni_cipher_openssl.c`.

## Batched encryption

`encrypt8` only pays if something actually fills the eight lanes, and Velocity encrypts one channel
at a time. `BatchingCipherEncoder` (proxy side) holds outbound frames for the rest of the current
event loop iteration and hands the whole loop's worth of them over in one call, so the lanes are
fed by unrelated *connections* — which is the only source of parallelism CFB8 encryption has.

Off by default. Enable with `-Dvelocity.enable-cipher-batching=true`; it additionally requires a
native library implementing the batch entry points, probed by calling `batchLanes()` and catching
the `UnsatisfiedLinkError` an OpenSSL-built library raises.

Measured with 1 KiB frames, `k` = frames per batch:

| k | Skylake | Zen 4 |
|---|---|---|
| 1 | 80.4 MB/s (41.2 c/B) | 85.9 MB/s (43.2 c/B) |
| 2 | 90.5 | 93.9 |
| 3 | 132.6 | 142.4 |
| 5 | 224.7 | 266.7 |
| 8 | 360.1 (9.2 c/B) | 426.6 (8.7 c/B) |
| 16-256 | ~383 (8.6 c/B) | ~430 (8.5 c/B) |

So a busy event loop encrypts outbound traffic **4.8x** (Skylake) to **5.0x** (Zen 4) faster than
the one-channel-at-a-time path, or 7-9x OpenSSL, and it stays flat out to 256 frames. `k=1` is the
quiet-proxy case and deliberately costs nothing: the scheduler routes a lone job to the serial
kernel, because 8-wide work is ~80 cycles per byte-position no matter how many lanes are idle while
serial is ~45 cycles/byte, so padding a single stream out to eight lanes would be a slowdown.

### How the pieces fit

* `CipherBatchCoordinator` — one per event loop thread (`FastThreadLocal`), so nothing here needs
  locking. The first deferred write of an iteration submits a task to the loop; because netty runs
  submitted tasks after the current callback, that task sees every write the wakeup produced. Same
  idea as netty's own `FlushConsolidationHandler`, applied across channels instead of within one.
* **Rounds.** Two lanes must never share a cipher — they would interleave its shift register — so a
  channel contributes at most one frame per native call. A channel with three queued frames takes
  part in rounds 0, 1 and 2, which also keeps its frames in order.
* **Lane scheduling** (`batch.rs`) — run every active lane to the shortest remaining boundary, then
  refill whichever drained, per Intel's multibuffer pattern. Idle lanes point at a shared dummy
  stream and scratch buffer, which is why the kernels take raw pointers rather than `&mut`.

### Sharp edges, and what handles them

* **Channel death mid-batch.** `handlerRemoved` deregisters from the coordinator and drops queued
  frames *before* `cipher.close()` frees the native context; the coordinator also skips any encoder
  that closed while it was completing an earlier one. Covered by
  `BatchingCipherEncoderTest.channelClosedBeforeBatchDoesNotCorruptOthers`, which closes a channel
  inside the same loop task that queued its frame — the exact window in which a stale context would
  be dereferenced.
* **Buffer lifetime.** Frames are retained across the write-to-batch gap and released on every
  failure path, including a partially completed batch.
* **Backpressure.** Deferral lasts less than one loop iteration, and by the end of it every frame
  has reached the outbound buffer, so writability accounting is unchanged. A `MAX_DEFERRED_BYTES`
  cap encrypts immediately if one wakeup ever produces an unusual burst.

Note that the tests run on a real event loop over the local transport, not `EmbeddedChannel`:
`EmbeddedChannel` drains its task queue after every `write`, `flush` and `close`, so deferral could
never happen there and every batch would be a batch of one. The test asserts on
`CipherBatchCoordinator.widestBatch` to prove frames from several channels really were coalesced,
since batched and unbatched output are byte-identical by construction.

## Next

`encrypt8` exists and is tested but nothing feeds it yet — Velocity encrypts one
channel at a time. Step 2 is the batching encoder: a per-event-loop coordinator
that collects the writes of one loop iteration and hands all of them to a single
`processBatch` JNI call, so the 8 lanes are actually filled. The kernel is
already pinned to `encrypt8 == 8 independent streams` by the test suite, so that
work cannot silently change the wire format.

Two things to pick up there: purging a dying channel's queued entries in
`handlerRemoved` *before* `cipher.close()` (otherwise the batch task dereferences
a freed context), and holding a `retain()` across the write->batch gap.

**VAES multibuffer.** On Zen 4, decrypt now runs at 3.6 cycles/byte while batched encrypt sits at
8.5 — outbound is the slower direction, and it is still running the xmm kernel because VAES today
only covers decrypt. Four streams per zmm, two zmm for the eight lanes, is the largest single win
left. The lanes are now actually being filled, so it would pay immediately.

**End-to-end measurement.** Everything above is kernel- and handler-level. What is not yet measured
is a real proxy under real player load: how many frames a wakeup actually carries in production
decides where on that `k` curve a deployment sits, and that is the number that matters. The
`widestBatch` counter is the obvious thing to export while testing that.

**musl**, still, and the Intel AVX-512 downclock question from the backend section.
