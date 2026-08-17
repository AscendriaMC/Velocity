/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.natives.encryption;

/**
 * Batched AES-CFB8 encryption: many connections encrypted in a single native call.
 *
 * <p>CFB8 encryption of one stream is a dependency chain — each block operation consumes the
 * ciphertext byte the previous one produced — so a single connection can never saturate the CPU's
 * AES unit. Several connections are independent of each other, though, and a proxy has them by the
 * hundred. Handing a whole event loop iteration's worth of frames over at once lets the native
 * side interleave them across lanes, which turns a latency-bound problem into a throughput-bound
 * one.</p>
 *
 * <p>Not every native cipher library implements this. {@link #isSupported()} probes for it; if the
 * loaded library predates the batch entry points the JVM raises {@link UnsatisfiedLinkError} on
 * first call, which is caught once and cached.</p>
 */
public final class VelocityCipherBatch {

  private VelocityCipherBatch() {
    throw new AssertionError();
  }

  // Deliberately a holder: the probe must not run until the native library has actually been
  // loaded by Natives.cipher, and class initialization gives us that ordering for free.
  private static final class Probe {
    static final boolean SUPPORTED;
    static final int LANES;

    static {
      boolean supported;
      int lanes = 0;
      try {
        lanes = OpenSslCipherImpl.batchLanes();
        supported = lanes > 0;
      } catch (Throwable t) {
        // No batch entry point in this library (or no native library at all).
        supported = false;
      }
      SUPPORTED = supported;
      LANES = lanes;
    }
  }

  /**
   * Returns whether the loaded native cipher library supports batched encryption.
   *
   * @return true if {@link #process} may be called
   */
  public static boolean isSupported() {
    return Probe.SUPPORTED;
  }

  /**
   * Returns how many streams the native side processes in lockstep, or 0 if unsupported.
   *
   * @return the lane count
   */
  public static int lanes() {
    return Probe.LANES;
  }

  /**
   * Encrypts {@code count} frames in place, one per entry of the parallel arrays.
   *
   * <p>The contexts must be pairwise distinct within a call: two lanes sharing a cipher would
   * interleave that stream's shift register and corrupt the connection. Callers with several
   * frames queued for one connection must therefore spread them over successive calls, which also
   * preserves their order.</p>
   *
   * @param ctxs native cipher handles, pairwise distinct
   * @param addrs memory addresses of the frames to encrypt
   * @param lens lengths of those frames
   * @param count how many entries of each array to use
   */
  public static void process(long[] ctxs, long[] addrs, int[] lens, int count) {
    OpenSslCipherImpl.processBatch(ctxs, addrs, lens, count);
  }
}
