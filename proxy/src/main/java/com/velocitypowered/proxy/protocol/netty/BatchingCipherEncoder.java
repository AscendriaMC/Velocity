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

import com.google.common.base.Preconditions;
import com.velocitypowered.natives.encryption.BatchingVelocityCipher;
import com.velocitypowered.natives.encryption.VelocityCipherBatch;
import com.velocitypowered.natives.util.MoreByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Encrypts outbound packets like {@link MinecraftCipherEncoder}, but defers the work so that every
 * encrypted channel on this event loop can be encrypted together.
 *
 * <p>Encrypting one CFB8 stream is latency-bound and cannot be made faster; encrypting eight
 * unrelated streams at once is throughput-bound and roughly five times cheaper per byte. This
 * handler exists to turn the former into the latter. Frames are held here for the remainder of the
 * current event loop iteration, then handed to {@link CipherBatchCoordinator} in one go.</p>
 *
 * <p>Off by default. Enable with {@code -Dvelocity.enable-cipher-batching=true}, and only if the
 * loaded native cipher library implements the batch entry points.</p>
 */
public class BatchingCipherEncoder extends ChannelOutboundHandlerAdapter {

  /**
   * Cap on how many bytes may sit here, outside the channel's outbound buffer, before we give up on
   * batching and encrypt immediately. Deferral is normally invisible because it lasts less than one
   * event loop iteration, but until a frame reaches the outbound buffer it does not count towards
   * the channel's writability, and backpressure must not be allowed to drift for a large burst.
   */
  private static final int MAX_DEFERRED_BYTES = 256 * 1024;

  private static final boolean PROPERTY_ENABLED =
      Boolean.getBoolean("velocity.enable-cipher-batching");

  /**
   * Returns whether batched encryption should be used.
   *
   * @return true if enabled by system property and supported by the loaded native library
   */
  public static boolean isEnabled() {
    return PROPERTY_ENABLED && VelocityCipherBatch.isSupported();
  }

  private final BatchingVelocityCipher cipher;
  private final List<ByteBuf> pending = new ArrayList<>(4);
  private final List<ChannelPromise> promises = new ArrayList<>(4);
  private @Nullable ChannelHandlerContext ctx;
  /**
   * The coordinator this encoder registered with, remembered rather than looked up again on
   * removal. The lookup is thread-local, so resolving it a second time would silently return a
   * different coordinator if this ever ran off the channel's event loop — and a missed
   * deregistration means the batch call dereferences a freed cipher context.
   */
  private @Nullable CipherBatchCoordinator coordinator;
  private int deferredBytes;
  private boolean flushRequested;
  private boolean closed;

  /** Coordinator bookkeeping. Confined to the event loop thread, like everything else here. */
  boolean registered;

  public BatchingCipherEncoder(BatchingVelocityCipher cipher) {
    this.cipher = Preconditions.checkNotNull(cipher, "cipher");
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) {
    this.ctx = ctx;
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
    if (!(msg instanceof ByteBuf buf)) {
      ctx.write(msg, promise);
      return;
    }
    if (closed) {
      buf.release();
      promise.tryFailure(new ClosedChannelException());
      return;
    }

    final ByteBuf compatible;
    try {
      compatible = MoreByteBufUtils.ensureCompatible(ctx.alloc(), cipher, buf);
    } catch (Throwable t) {
      buf.release();
      promise.tryFailure(t);
      return;
    }
    // ensureCompatible either retained buf or copied out of it; either way we are done with the
    // incoming message and own `compatible` until it reaches the outbound buffer.
    buf.release();

    pending.add(compatible);
    promises.add(promise);
    deferredBytes += compatible.readableBytes();

    if (deferredBytes >= MAX_DEFERRED_BYTES) {
      if (coordinator != null) {
        coordinator.remove(this);
      }
      for (ByteBuf queued : pending) {
        cipher.process(queued);
      }
      completeBatch();
      return;
    }

    if (coordinator == null) {
      coordinator = CipherBatchCoordinator.get();
    }
    coordinator.enqueue(this, ctx.executor());
  }

  @Override
  public void flush(ChannelHandlerContext ctx) {
    if (pending.isEmpty()) {
      ctx.flush();
      return;
    }
    // Held back and coalesced into the batch completion, later in this same iteration.
    flushRequested = true;
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) {
    closed = true;
    if (coordinator != null) {
      coordinator.remove(this);
    }
    // Order matters. Dropping our queued frames before closing the cipher is what guarantees the
    // coordinator can never hand a freed native context to the batch call.
    discardPending(null);
    cipher.close();
  }

  boolean isClosed() {
    return closed;
  }

  long nativeHandle() {
    return cipher.nativeHandle();
  }

  /**
   * Returns this channel's {@code index}-th queued frame, or null if it has fewer than that.
   */
  @Nullable ByteBuf pendingAt(int index) {
    return index < pending.size() ? pending.get(index) : null;
  }

  /** Hands the (now encrypted) frames to the outbound buffer, in the order they were written. */
  void completeBatch() {
    final int n = pending.size();
    int handed = 0;
    try {
      while (handed < n) {
        final ByteBuf buf = pending.get(handed);
        final ChannelPromise promise = promises.get(handed);
        handed++;
        ctx.write(buf, promise);
      }
    } finally {
      for (int i = handed; i < n; i++) {
        pending.get(i).release();
        promises.get(i).tryFailure(new IllegalStateException("cipher batch aborted"));
      }
      pending.clear();
      promises.clear();
      deferredBytes = 0;
      final boolean flush = flushRequested;
      flushRequested = false;
      if (flush) {
        ctx.flush();
      }
    }
  }

  /**
   * Releases every queued frame and fails its promise. A non-null {@code cause} means the stream
   * has been left inconsistent, so the channel is closed too.
   */
  void discardPending(@Nullable Throwable cause) {
    if (!pending.isEmpty()) {
      final Throwable failure = cause != null ? cause : new ClosedChannelException();
      for (int i = 0; i < pending.size(); i++) {
        pending.get(i).release();
        promises.get(i).tryFailure(failure);
      }
      pending.clear();
      promises.clear();
      deferredBytes = 0;
      flushRequested = false;
    }
    if (cause != null && ctx != null) {
      ctx.close();
    }
  }
}
