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

package com.velocitypowered.proxy.protocol.netty;

import com.velocitypowered.natives.encryption.VelocityCipherBatch;
import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FastThreadLocal;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects one event loop iteration's worth of outbound frames, across every encrypted channel on
 * that loop, and encrypts them in a single native call.
 *
 * <p>One instance per event loop thread, so no locking is needed anywhere here: netty pins channels
 * to loops, and every method below runs on the loop that owns the channels involved.</p>
 *
 * <p>The batch is assembled by a task submitted to the loop on the first deferred write of an
 * iteration. That task therefore runs after all of the reads that drove those writes have been
 * processed — the same trick netty's own {@code FlushConsolidationHandler} uses, except applied
 * across channels rather than within one.</p>
 */
final class CipherBatchCoordinator {

  private static final BatchingCipherEncoder[] EMPTY = new BatchingCipherEncoder[0];

  private static final FastThreadLocal<CipherBatchCoordinator> LOCAL =
      new FastThreadLocal<>() {
        @Override
        protected CipherBatchCoordinator initialValue() {
          return new CipherBatchCoordinator();
        }
      };

  static CipherBatchCoordinator get() {
    return LOCAL.get();
  }

  /**
   * Widest batch this JVM has assembled. Exists so tests can assert that frames from several
   * channels really were coalesced into one native call rather than trickling through one at a
   * time — a difference that is otherwise invisible from outside, because both produce identical
   * bytes. One plain field write per batch.
   */
  static volatile int widestBatch;

  private final List<BatchingCipherEncoder> registered = new ArrayList<>();
  private final Runnable task = this::run;
  private boolean scheduled;

  private long[] ctxs = new long[16];
  private long[] addrs = new long[16];
  private int[] lens = new int[16];

  void enqueue(BatchingCipherEncoder encoder, EventExecutor loop) {
    if (!encoder.registered) {
      encoder.registered = true;
      registered.add(encoder);
    }
    if (!scheduled) {
      scheduled = true;
      loop.execute(task);
    }
  }

  void remove(BatchingCipherEncoder encoder) {
    if (encoder.registered) {
      encoder.registered = false;
      registered.remove(encoder);
    }
  }

  private void run() {
    scheduled = false;
    if (registered.isEmpty()) {
      return;
    }

    // Snapshot before doing anything else. Completing a batch runs the rest of the outbound
    // pipeline, which can close a channel and re-enter remove() while we are iterating.
    final BatchingCipherEncoder[] batch = registered.toArray(EMPTY);
    for (BatchingCipherEncoder encoder : batch) {
      encoder.registered = false;
    }
    registered.clear();

    Throwable failure = null;
    try {
      // One round per queue depth. A channel with three frames queued contributes to rounds 0, 1
      // and 2 — never twice to the same round, because two lanes sharing a cipher would interleave
      // that stream's shift register. Successive rounds keep its frames in order.
      for (int round = 0; ; round++) {
        int count = 0;
        for (BatchingCipherEncoder encoder : batch) {
          if (encoder.isClosed()) {
            continue;
          }
          final ByteBuf buf = encoder.pendingAt(round);
          if (buf == null) {
            continue;
          }
          if (count == ctxs.length) {
            grow();
          }
          ctxs[count] = encoder.nativeHandle();
          addrs[count] = buf.memoryAddress() + buf.readerIndex();
          lens[count] = buf.readableBytes();
          count++;
        }
        if (count == 0) {
          break;
        }
        if (count > widestBatch) {
          widestBatch = count;
        }
        VelocityCipherBatch.process(ctxs, addrs, lens, count);
      }
    } catch (Throwable t) {
      failure = t;
    }

    for (BatchingCipherEncoder encoder : batch) {
      if (encoder.isClosed()) {
        continue;
      }
      if (failure == null) {
        encoder.completeBatch();
      } else {
        // Some frames may already be encrypted, and their stream state has advanced past frames we
        // are about to drop. Those connections cannot be recovered, only closed.
        encoder.discardPending(failure);
      }
    }
  }

  private void grow() {
    final int n = ctxs.length * 2;
    final long[] newCtxs = new long[n];
    final long[] newAddrs = new long[n];
    final int[] newLens = new int[n];
    System.arraycopy(ctxs, 0, newCtxs, 0, ctxs.length);
    System.arraycopy(addrs, 0, newAddrs, 0, addrs.length);
    System.arraycopy(lens, 0, newLens, 0, lens.length);
    ctxs = newCtxs;
    addrs = newAddrs;
    lens = newLens;
  }
}
