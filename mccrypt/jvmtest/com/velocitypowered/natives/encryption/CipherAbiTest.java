package com.velocitypowered.natives.encryption;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * End-to-end check of the mccrypt shared library through the real JNI ABI.
 *
 * Deliberately standalone (javac + java, no Gradle, no netty): what it verifies
 * is the boundary Velocity actually crosses — the JVM resolving our three
 * symbols by name, raw off-heap addresses in and out, and the exception
 * contract on a bad key — against the JDK's own AES/CFB8 as the oracle.
 *
 *   javac -d out jvmtest/com/velocitypowered/natives/encryption/CipherAbiTest.java
 *   java -cp out com.velocitypowered.natives.encryption.CipherAbiTest <path-to-lib>
 */
public final class CipherAbiTest {

  private static int checks = 0;

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      System.err.println("usage: CipherAbiTest <path to velocity-cipher.{so,dylib}>");
      System.exit(2);
    }
    System.load(java.nio.file.Path.of(args[0]).toAbsolutePath().normalize().toString());

    knownAnswer();
    againstJdk();
    splitCalls();
    outOfPlace();
    badKeyLength();
    nullKey();
    freeNullIsHarmless();
    batchLanes();
    batchMatchesPerStream();
    batchPreservesStateAcrossCalls();
    batchRejectsShortArrays();

    System.out.println("OK — " + checks + " checks passed");
  }

  /** The OpenSSL-derived vector the Rust test suite also pins. */
  private static void knownAnswer() {
    byte[] key = "0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    byte[] pt = new byte[256];
    for (int i = 0; i < 256; i++) {
      pt[i] = (byte) i;
    }
    byte[] ct = run(key, true, pt);
    String prefix = HexFormat.of().formatHex(Arrays.copyOf(ct, 4));
    expect(prefix.equals("72301ed9"), "KAT prefix was " + prefix);
    expect(Arrays.equals(run(key, false, ct), pt), "KAT roundtrip");
  }

  /** javax.crypto AES/CFB8 is the same stream vanilla clients speak. */
  private static void againstJdk() throws Exception {
    Random rng = new Random(0xC0FFEE);
    for (int len : new int[] {1, 2, 7, 8, 15, 16, 17, 63, 64, 65, 255, 4095, 4096, 4097, 20000}) {
      byte[] key = new byte[16];
      byte[] data = new byte[len];
      rng.nextBytes(key);
      rng.nextBytes(data);
      expect(Arrays.equals(run(key, true, data), jdk(key, Cipher.ENCRYPT_MODE, data)),
          "encrypt mismatch vs JDK at len=" + len);
      expect(Arrays.equals(run(key, false, data), jdk(key, Cipher.DECRYPT_MODE, data)),
          "decrypt mismatch vs JDK at len=" + len);
    }
  }

  /** Velocity hands us whatever netty read; stream state must span calls. */
  private static void splitCalls() throws Exception {
    byte[] key = new byte[16];
    new SecureRandom(new byte[] {1}).nextBytes(key);
    byte[] data = new byte[5000];
    new Random(7).nextBytes(data);
    byte[] want = jdk(key, Cipher.ENCRYPT_MODE, data);

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment seg = arena.allocate(data.length);
      MemorySegment.copy(data, 0, seg, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, data.length);
      long ctx = OpenSslCipherImpl.init(key, true);
      long base = seg.address();
      int off = 0;
      Random split = new Random(99);
      while (off < data.length) {
        int n = Math.min(1 + split.nextInt(700), data.length - off);
        OpenSslCipherImpl.process(ctx, base + off, n, base + off);
        off += n;
      }
      OpenSslCipherImpl.free(ctx);
      byte[] got = seg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
      expect(Arrays.equals(got, want), "split-call stream state");
    }
  }

  /** Velocity always passes source == dest, but the C impl accepted both. */
  private static void outOfPlace() throws Exception {
    byte[] key = new byte[16];
    new Random(3).nextBytes(key);
    byte[] data = new byte[300];
    new Random(4).nextBytes(data);

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment src = arena.allocate(data.length);
      MemorySegment dst = arena.allocate(data.length);
      MemorySegment.copy(data, 0, src, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, data.length);
      long ctx = OpenSslCipherImpl.init(key, true);
      OpenSslCipherImpl.process(ctx, src.address(), data.length, dst.address());
      OpenSslCipherImpl.free(ctx);
      expect(Arrays.equals(dst.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
          jdk(key, Cipher.ENCRYPT_MODE, data)), "out-of-place process");
    }
  }

  private static void badKeyLength() {
    try {
      OpenSslCipherImpl.init(new byte[15], true);
      expect(false, "15-byte key should have thrown");
    } catch (IllegalArgumentException e) {
      expect("cipher not 16 bytes".equals(e.getMessage()),
          "wrong message: " + e.getMessage());
    }
  }

  private static void nullKey() {
    try {
      OpenSslCipherImpl.init(null, true);
      expect(false, "null key should have thrown");
    } catch (IllegalArgumentException e) {
      checks++;
    }
  }

  private static void freeNullIsHarmless() {
    OpenSslCipherImpl.free(0);
    checks++;
  }

  private static void batchLanes() {
    expect(OpenSslCipherImpl.batchLanes() == 8, "expected 8 lanes");
  }

  /**
   * The whole point of the batch path: same bytes on the wire as encrypting each
   * frame on its own, just scheduled across lanes. Ragged lengths and a job count
   * that is not a multiple of the lane width, because that is what an event-loop
   * wakeup actually looks like.
   */
  private static void batchMatchesPerStream() throws Exception {
    for (int count : new int[] {1, 2, 3, 7, 8, 9, 40, 100}) {
      Random rng = new Random(count);
      byte[][] keys = new byte[count][16];
      byte[][] data = new byte[count][];
      for (int i = 0; i < count; i++) {
        rng.nextBytes(keys[i]);
        data[i] = new byte[1 + rng.nextInt(3000)];
        rng.nextBytes(data[i]);
      }

      try (Arena arena = Arena.ofConfined()) {
        long[] ctxs = new long[count];
        long[] addrs = new long[count];
        int[] lens = new int[count];
        MemorySegment[] segs = new MemorySegment[count];
        for (int i = 0; i < count; i++) {
          segs[i] = arena.allocate(data[i].length);
          MemorySegment.copy(data[i], 0, segs[i], java.lang.foreign.ValueLayout.JAVA_BYTE, 0,
              data[i].length);
          ctxs[i] = OpenSslCipherImpl.init(keys[i], true);
          addrs[i] = segs[i].address();
          lens[i] = data[i].length;
        }

        OpenSslCipherImpl.processBatch(ctxs, addrs, lens, count);

        for (int i = 0; i < count; i++) {
          OpenSslCipherImpl.free(ctxs[i]);
          byte[] got = segs[i].toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
          expect(Arrays.equals(got, jdk(keys[i], Cipher.ENCRYPT_MODE, data[i])),
              "batch of " + count + " diverged on stream " + i);
        }
      }
    }
  }

  /**
   * The coordinator emits at most one frame per channel per call, so a channel
   * with several queued frames spans successive calls and its stream state has
   * to carry across them.
   */
  private static void batchPreservesStateAcrossCalls() throws Exception {
    final int streams = 5;
    final int rounds = 4;
    final int chunk = 700;
    Random rng = new Random(4242);
    byte[][] keys = new byte[streams][16];
    byte[][] whole = new byte[streams][];
    for (int i = 0; i < streams; i++) {
      rng.nextBytes(keys[i]);
      whole[i] = new byte[chunk * rounds];
      rng.nextBytes(whole[i]);
    }

    try (Arena arena = Arena.ofConfined()) {
      long[] ctxs = new long[streams];
      MemorySegment[] segs = new MemorySegment[streams];
      for (int i = 0; i < streams; i++) {
        segs[i] = arena.allocate(whole[i].length);
        MemorySegment.copy(whole[i], 0, segs[i], java.lang.foreign.ValueLayout.JAVA_BYTE, 0,
            whole[i].length);
        ctxs[i] = OpenSslCipherImpl.init(keys[i], true);
      }

      long[] addrs = new long[streams];
      int[] lens = new int[streams];
      for (int r = 0; r < rounds; r++) {
        for (int i = 0; i < streams; i++) {
          addrs[i] = segs[i].address() + (long) r * chunk;
          lens[i] = chunk;
        }
        OpenSslCipherImpl.processBatch(ctxs, addrs, lens, streams);
      }

      for (int i = 0; i < streams; i++) {
        OpenSslCipherImpl.free(ctxs[i]);
        expect(Arrays.equals(segs[i].toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
            jdk(keys[i], Cipher.ENCRYPT_MODE, whole[i])),
            "stream " + i + " broke across batch rounds");
      }
    }
  }

  private static void batchRejectsShortArrays() {
    try {
      OpenSslCipherImpl.processBatch(new long[1], new long[1], new int[1], 4);
      expect(false, "short arrays should have thrown");
    } catch (IllegalArgumentException e) {
      checks++;
    }
  }

  private static byte[] run(byte[] key, boolean encrypt, byte[] data) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment seg = arena.allocate(Math.max(1, data.length));
      MemorySegment.copy(data, 0, seg, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, data.length);
      long ctx = OpenSslCipherImpl.init(key, encrypt);
      OpenSslCipherImpl.process(ctx, seg.address(), data.length, seg.address());
      OpenSslCipherImpl.free(ctx);
      byte[] out = new byte[data.length];
      MemorySegment.copy(seg, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, out, 0, data.length);
      return out;
    }
  }

  private static byte[] jdk(byte[] key, int mode, byte[] data) throws Exception {
    Cipher c = Cipher.getInstance("AES/CFB8/NoPadding");
    c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
    return c.doFinal(data);
  }

  private static void expect(boolean ok, String what) {
    if (!ok) {
      throw new AssertionError(what);
    }
    checks++;
  }
}

/** Same fully-qualified name as Velocity's, so the JVM binds our symbols. */
final class OpenSslCipherImpl {
  static native long init(byte[] key, boolean encrypt);

  static native void process(long ctx, long source, int len, long dest);

  static native void free(long ptr);

  static native void processBatch(long[] ctxs, long[] addrs, int[] lens, int count);

  static native int batchLanes();
}
