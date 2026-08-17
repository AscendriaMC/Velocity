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

import com.velocitypowered.natives.encryption.BatchingVelocityCipher;
import com.velocitypowered.natives.encryption.VelocityCipher;
import com.velocitypowered.natives.encryption.VelocityCipherBatch;
import com.velocitypowered.natives.util.Natives;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;

/**
 * Throughput harness for outbound encryption through the real netty write path.
 *
 * <p>The kernel benchmarks in {@code mccrypt} measure the cipher in isolation. This measures what a
 * proxy actually pays: allocation, the pipeline, the JNI crossing, the socket, and the cipher —
 * with many connections on one event loop, which is the arrangement batching exists to exploit.
 * The headline number is CPU nanoseconds burned on the event loop thread per byte encrypted,
 * because on a CPU-bound proxy that is the resource that runs out first.</p>
 *
 * <p>Run both ways and compare:</p>
 *
 * <pre>
 *   ./gradlew :velocity-proxy:cipherHarness -Dvelocity.enable-cipher-batching=false
 *   ./gradlew :velocity-proxy:cipherHarness -Dvelocity.enable-cipher-batching=true
 * </pre>
 */
public final class CipherBatchHarness {

  private CipherBatchHarness() {
    throw new AssertionError();
  }

  private static int arg(String name, int fallback) {
    final String value = System.getProperty("mccrypt.harness." + name);
    return value == null ? fallback : Integer.parseInt(value);
  }

  /** Runs the harness; see the class Javadoc for the {@code -Dmccrypt.harness.*} knobs. */
  public static void main(String[] args) throws Exception {
    final int connections = arg("connections", 200);
    final int frameBytes = arg("frame", 256);
    final int framesPerWakeup = arg("frames", 2);
    final int seconds = arg("seconds", 10);
    final int warmupSeconds = arg("warmup", 5);

    Natives.cipher.getLoadedVariant();
    final boolean batching = BatchingCipherEncoder.isEnabled();
    System.out.println("cipher variant   : " + Natives.cipher.getLoadedVariant());
    System.out.println("batch supported  : " + VelocityCipherBatch.isSupported());
    System.out.println("batching enabled : " + batching);
    System.out.printf("load             : %d connections x %d frames x %d B per wakeup%n",
        connections, framesPerWakeup, frameBytes);

    // Separate groups so the receiving side's CPU is not attributed to the proxy loop, and exactly
    // one proxy loop so its CPU time is a single, attributable number.
    final EventLoopGroup sinkGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    final EventLoopGroup proxyGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    try {
      final Channel server = new ServerBootstrap()
          .group(sinkGroup)
          .channel(NioServerSocketChannel.class)
          .childHandler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
              ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                  // Discard: we are measuring the sender.
                }
              });
            }
          })
          .bind(new InetSocketAddress("127.0.0.1", 0))
          .syncUninterruptibly()
          .channel();
      final InetSocketAddress address = (InetSocketAddress) server.localAddress();

      final SecureRandom random = new SecureRandom();
      final List<Channel> clients = new ArrayList<>(connections);
      for (int i = 0; i < connections; i++) {
        final byte[] key = new byte[16];
        random.nextBytes(key);
        final VelocityCipher cipher =
            Natives.cipher.get().forEncryption(new SecretKeySpec(key, "AES"));
        final ChannelHandler encoder =
            batching && cipher instanceof BatchingVelocityCipher bc
                ? new BatchingCipherEncoder(bc)
                : new MinecraftCipherEncoder(cipher);
        clients.add(new Bootstrap()
            .group(proxyGroup)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<Channel>() {
              @Override
              protected void initChannel(Channel ch) {
                ch.pipeline().addLast(encoder);
              }
            })
            .connect(address)
            .syncUninterruptibly()
            .channel());
      }

      final EventLoop loop = clients.get(0).eventLoop();
      final long loopThreadId = loop.submit(() -> Thread.currentThread().threadId()).get();
      final ThreadMXBean threads = ManagementFactory.getThreadMXBean();

      final byte[] payload = new byte[frameBytes];
      random.nextBytes(payload);

      System.out.printf("warming up %ds...%n", warmupSeconds);
      drive(clients, payload, framesPerWakeup, warmupSeconds * 1000L);

      CipherBatchCoordinator.widestBatch = 0;
      final long cpuBefore = threads.getThreadCpuTime(loopThreadId);
      final long wallBefore = System.nanoTime();
      final long bytes = drive(clients, payload, framesPerWakeup, seconds * 1000L);
      final long wall = System.nanoTime() - wallBefore;
      final long cpu = threads.getThreadCpuTime(loopThreadId) - cpuBefore;

      System.out.println();
      System.out.printf("bytes encrypted  : %.1f MB%n", bytes / 1e6);
      System.out.printf("wall time        : %.2f s%n", wall / 1e9);
      System.out.printf("throughput       : %.1f MB/s%n", bytes / (wall / 1e9) / 1e6);
      System.out.printf("event loop CPU   : %.2f s (%.0f%% of wall)%n",
          cpu / 1e9, 100.0 * cpu / wall);
      System.out.printf("CPU per byte     : %.2f ns%n", (double) cpu / bytes);
      System.out.println("widest batch     : " + CipherBatchCoordinator.widestBatch);

      for (Channel channel : clients) {
        channel.close();
      }
      server.close().syncUninterruptibly();
    } finally {
      proxyGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
      sinkGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }
  }

  /**
   * Drives load for {@code millis}, one "wakeup" at a time: every connection writes its frames
   * inside a single event loop task, which is the shape a real read-driven proxy produces and the
   * only shape from which a batch can be assembled. Waiting for the round to reach the socket
   * before starting the next one is what paces the harness against the receiver.
   */
  private static long drive(List<Channel> clients, byte[] payload, int framesPerWakeup, long millis)
      throws Exception {
    final EventLoop loop = clients.get(0).eventLoop();
    final ByteBufAllocator alloc = clients.get(0).alloc();
    final long deadline = System.nanoTime() + millis * 1_000_000L;
    long bytes = 0;

    while (System.nanoTime() < deadline) {
      loop.submit(() -> {
        for (int f = 0; f < framesPerWakeup; f++) {
          for (Channel channel : clients) {
            final ByteBuf buf = alloc.directBuffer(payload.length);
            buf.writeBytes(payload);
            channel.write(buf);
          }
        }
        for (Channel channel : clients) {
          channel.flush();
        }
      }).get();
      // The coordinator task is queued behind the writes; this round is only finished once it has
      // run, which a subsequent no-op task on the same loop guarantees.
      loop.submit(() -> {
      }).get();
      bytes += (long) payload.length * framesPerWakeup * clients.size();
    }
    return bytes;
  }
}
