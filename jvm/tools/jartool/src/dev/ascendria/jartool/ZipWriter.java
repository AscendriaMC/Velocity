package dev.ascendria.jartool;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A zip writer fed already-compressed bytes, so entries taken unchanged from an input are copied
 * through as they were and entries compressed in parallel beforehand are just written. Every
 * entry carries the same fixed timestamp. Plain zip: fails rather than silently truncating past
 * 65535 entries or 4 GiB.
 */
final class ZipWriter implements Closeable {
  private record Central(String name, int method, long crc, long csize, long usize, long offset) {}

  private final OutputStream out;
  private final List<Central> central = new ArrayList<>();
  private final int dosTime;
  private final int dosDate;
  private long offset = 0;

  ZipWriter(Path path, LocalDateTime timestamp) throws IOException {
    out = new BufferedOutputStream(Files.newOutputStream(path), 1 << 16);
    dosTime = (timestamp.getHour() << 11) | (timestamp.getMinute() << 5) | (timestamp.getSecond() / 2);
    dosDate = ((timestamp.getYear() - 1980) << 9) | (timestamp.getMonthValue() << 5) | timestamp.getDayOfMonth();
  }

  void directory(String name) throws IOException {
    add(name, ZipReader.STORED, 0, 0, 0, new byte[0]);
  }

  void add(String name, int method, long crc, long csize, long usize, byte[] data) throws IOException {
    if (data.length != csize) {
      throw new IOException(name + ": compressed size " + csize + " but " + data.length + " bytes given");
    }
    byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
    ByteBuffer h = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
    h.putInt(0x04034b50);
    h.putShort((short) 20); // version needed: 2.0, deflate
    h.putShort((short) 0x0800); // names are UTF-8
    h.putShort((short) method);
    h.putShort((short) dosTime);
    h.putShort((short) dosDate);
    h.putInt((int) crc);
    h.putInt((int) csize);
    h.putInt((int) usize);
    h.putShort((short) nameBytes.length);
    h.putShort((short) 0);
    out.write(h.array());
    out.write(nameBytes);
    out.write(data);
    central.add(new Central(name, method, crc, csize, usize, offset));
    offset += 30 + nameBytes.length + data.length;
    if (offset >= 0xffffffffL) {
      throw new IOException("archive exceeds 4 GiB; zip64 is not supported");
    }
  }

  @Override
  public void close() throws IOException {
    if (central.size() >= 0xffff) {
      throw new IOException("archive has " + central.size() + " entries; zip64 is not supported");
    }
    long cdOffset = offset;
    for (Central c : central) {
      byte[] nameBytes = c.name().getBytes(StandardCharsets.UTF_8);
      ByteBuffer h = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
      h.putInt(0x02014b50);
      h.putShort((short) 20); // made by: 2.0
      h.putShort((short) 20); // needed
      h.putShort((short) 0x0800);
      h.putShort((short) c.method());
      h.putShort((short) dosTime);
      h.putShort((short) dosDate);
      h.putInt((int) c.crc());
      h.putInt((int) c.csize());
      h.putInt((int) c.usize());
      h.putShort((short) nameBytes.length);
      h.putShort((short) 0); // extra
      h.putShort((short) 0); // comment
      h.putShort((short) 0); // disk
      h.putShort((short) 0); // internal attrs
      h.putInt(0); // external attrs
      h.putInt((int) c.offset());
      out.write(h.array());
      out.write(nameBytes);
      offset += 46 + nameBytes.length;
    }
    ByteBuffer e = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
    e.putInt(0x06054b50);
    e.putShort((short) 0);
    e.putShort((short) 0);
    e.putShort((short) central.size());
    e.putShort((short) central.size());
    e.putInt((int) (offset - cdOffset));
    e.putInt((int) cdOffset);
    e.putShort((short) 0);
    out.write(e.array());
    out.close();
  }
}
