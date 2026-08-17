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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.velocitypowered.natives.encryption.BatchingVelocityCipher;
import com.velocitypowered.natives.encryption.VelocityCipher;
import com.velocitypowered.natives.encryption.VelocityCipherBatch;
import com.velocitypowered.natives.util.Natives;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.local.LocalServerChannel;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BatchingCipherEncoder}.
 *
 * <p>These run on a real single-threaded event loop over the local transport rather than on an
 * {@link io.netty.channel.embedded.EmbeddedChannel}. That is not incidental: {@code
 * EmbeddedChannel} drains its task queue after every {@code write}, {@code flush} and {@code
 * close}, so the deferral this handler is built on could never happen there and every batch would
 * be a batch of one. A real loop is the only way to write all the channels first and let the
 * coordinator task run afterwards, which is the case worth testing.</p>
 */
class BatchingCipherEncoderTest {

  private static final int CHANNELS = 6;
  private static final int FRAMES = 3;
  private static final long TIMEOUT_MS = 10_000;

  private EventLoopGroup group;
  private EventLoop loop;
  private Channel server;
  private LocalAddress address;
  private final BlockingQueue<Channel> accepted = new LinkedBlockingQueue<>();
  private final Map<Channel, ByteArrayOutputStream> received = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() {
    Natives.cipher.getLoadedVariant();
    group = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
    loop = group.next();
    address = new LocalAddress("mccrypt-batch-" + UUID.randomUUID());
    server = new ServerBootstrap()
        .group(group)
        .channel(LocalServerChannel.class)
        .childHandler(new ChannelInitializer<Channel>() {
          @Override
          protected void initChannel(Channel ch) {
            final ByteArrayOutputStream sink = new ByteArrayOutputStream();
            received.put(ch, sink);
            ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
              @Override
              protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                final byte[] chunk = new byte[msg.readableBytes()];
                msg.readBytes(chunk);
                synchronized (sink) {
                  sink.writeBytes(chunk);
                }
              }
            });
            accepted.add(ch);
          }
        })
        .bind(address)
        .syncUninterruptibly()
        .channel();
  }

  @AfterEach
  void tearDown() {
    server.close().syncUninterruptibly();
    group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
  }

  private Channel connectClient(byte[] key) throws Exception {
    final VelocityCipher cipher =
        Natives.cipher.get().forEncryption(new SecretKeySpec(key, "AES"));
    assumeTrue(cipher instanceof BatchingVelocityCipher, "native cipher required");
    return new Bootstrap()
        .group(group)
        .channel(LocalChannel.class)
        .handler(new ChannelInitializer<Channel>() {
          @Override
          protected void initChannel(Channel ch) {
            ch.pipeline().addLast(new BatchingCipherEncoder((BatchingVelocityCipher) cipher));
          }
        })
        .connect(address)
        .sync()
        .channel();
  }

  private static byte[] jdkEncrypt(byte[] key, byte[] data) throws Exception {
    final Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
    return cipher.doFinal(data);
  }

  private static byte[] awaitBytes(ByteArrayOutputStream sink, int expected) {
    final long deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L;
    while (System.nanoTime() < deadline) {
      synchronized (sink) {
        if (sink.size() >= expected) {
          return sink.toByteArray();
        }
      }
      Thread.onSpinWait();
    }
    synchronized (sink) {
      return sink.toByteArray();
    }
  }

  /**
   * Every channel writes before anything is allowed to run, which is the shape of a single event
   * loop wakeup and the shape that fills the lanes. Output must be byte-for-byte what a
   * per-channel cipher would have produced.
   */
  @Test
  void batchedOutputMatchesPerChannelCipher() throws Exception {
    assumeTrue(VelocityCipherBatch.isSupported(), "native library without batch support");

    final Random rng = new Random(20260818L);
    final byte[][] keys = new byte[CHANNELS][16];
    final Channel[] clients = new Channel[CHANNELS];
    final Channel[] peers = new Channel[CHANNELS];
    final ByteArrayOutputStream[] plaintext = new ByteArrayOutputStream[CHANNELS];
    final byte[][][] frames = new byte[CHANNELS][FRAMES][];

    for (int c = 0; c < CHANNELS; c++) {
      rng.nextBytes(keys[c]);
      clients[c] = connectClient(keys[c]);
      peers[c] = accepted.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
      assertNotNull(peers[c], "server never accepted channel " + c);
      plaintext[c] = new ByteArrayOutputStream();
    }
    for (int c = 0; c < CHANNELS; c++) {
      for (int f = 0; f < FRAMES; f++) {
        frames[c][f] = new byte[1 + rng.nextInt(2000)];
        rng.nextBytes(frames[c][f]);
        plaintext[c].writeBytes(frames[c][f]);
      }
    }

    CipherBatchCoordinator.widestBatch = 0;

    loop.submit(() -> {
      // Interleaved by frame so every channel has work queued before the loop goes idle.
      for (int f = 0; f < FRAMES; f++) {
        for (int c = 0; c < CHANNELS; c++) {
          clients[c].write(Unpooled.wrappedBuffer(frames[c][f]));
        }
      }
      for (int c = 0; c < CHANNELS; c++) {
        clients[c].flush();
      }
    }).sync();

    for (int c = 0; c < CHANNELS; c++) {
      final byte[] got = awaitBytes(received.get(peers[c]), plaintext[c].size());
      assertArrayEquals(jdkEncrypt(keys[c], plaintext[c].toByteArray()), got,
          "channel " + c + " diverged from a per-channel cipher");
    }

    assertTrue(CipherBatchCoordinator.widestBatch >= CHANNELS,
        "frames were not coalesced across channels; widest batch was "
            + CipherBatchCoordinator.widestBatch + " of " + CHANNELS);
  }

  /**
   * The use-after-free case. A channel closes after its frame is queued but before the batch runs,
   * which frees its native cipher context — so the coordinator must have dropped it first, or the
   * batch call would dereference freed memory. Everything here happens inside one loop task, so
   * the close is guaranteed to land in that window.
   */
  @Test
  void channelClosedBeforeBatchDoesNotCorruptOthers() throws Exception {
    assumeTrue(VelocityCipherBatch.isSupported(), "native library without batch support");

    final int victim = 2;
    final Random rng = new Random(0xDEADBEEFL);
    final byte[][] keys = new byte[CHANNELS][16];
    final byte[][] frames = new byte[CHANNELS][];
    final Channel[] clients = new Channel[CHANNELS];
    final Channel[] peers = new Channel[CHANNELS];

    for (int c = 0; c < CHANNELS; c++) {
      rng.nextBytes(keys[c]);
      frames[c] = new byte[500 + rng.nextInt(500)];
      rng.nextBytes(frames[c]);
      clients[c] = connectClient(keys[c]);
      peers[c] = accepted.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
      assertNotNull(peers[c], "server never accepted channel " + c);
    }

    loop.submit(() -> {
      for (int c = 0; c < CHANNELS; c++) {
        clients[c].write(Unpooled.wrappedBuffer(frames[c]));
      }
      for (int c = 0; c < CHANNELS; c++) {
        clients[c].flush();
      }
      // Still inside the same task: the coordinator has not run yet.
      clients[victim].close();
    }).sync();

    for (int c = 0; c < CHANNELS; c++) {
      if (c == victim) {
        continue;
      }
      final byte[] got = awaitBytes(received.get(peers[c]), frames[c].length);
      assertArrayEquals(jdkEncrypt(keys[c], frames[c]), got,
          "channel " + c + " was corrupted by a neighbour closing mid-batch");
    }
    assertEquals(0, received.get(peers[victim]).size(), "closed channel emitted data");
  }
}
