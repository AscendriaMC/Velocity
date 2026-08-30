package dev.ascendria.jartool;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A zip reader that exposes an entry's compressed bytes as stored, so an entry that is copied
 * through unchanged never gets inflated and deflated again -- the JDK's ZipFile only hands out
 * inflated streams. The whole file is read once and sliced from memory: an assembly touches
 * tens of thousands of entries, and one read per entry was 60 s on a remote worker's virtual
 * disk against 2 s here. Plain zip only (no zip64, no encryption), which is every jar we
 * assemble.
 */
final class ZipReader implements Closeable {
  static final int STORED = 0;
  static final int DEFLATED = 8;

  record Entry(String name, int method, long crc, long csize, long usize, long localHeaderOffset) {}

  private final byte[] file;
  final List<Entry> entries = new ArrayList<>();

  ZipReader(Path path) throws IOException {
    file = Files.readAllBytes(path);
    parse(path);
  }

  private void parse(Path path) throws IOException {
    int length = file.length;
    // The end-of-central-directory record is the last thing in the file, followed only by a
    // comment of at most 65535 bytes; scan back for its signature.
    int eocd = -1;
    for (int i = length - 22; i >= Math.max(0, length - 22 - 65535); i--) {
      if (file[i] == 0x50 && file[i + 1] == 0x4b && file[i + 2] == 0x05 && file[i + 3] == 0x06) {
        eocd = i;
        break;
      }
    }
    if (eocd < 0) {
      throw new IOException(path + ": not a zip file (no end-of-central-directory record)");
    }
    ByteBuffer e = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
    int count = e.getShort(eocd + 10) & 0xffff;
    long cdSize = e.getInt(eocd + 12) & 0xffffffffL;
    long cdOffset = e.getInt(eocd + 16) & 0xffffffffL;
    if (count == 0xffff || cdSize == 0xffffffffL || cdOffset == 0xffffffffL) {
      throw new IOException(path + ": zip64 archives are not supported");
    }
    ByteBuffer c = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
    int p = (int) cdOffset;
    for (int i = 0; i < count; i++) {
      if (c.getInt(p) != 0x02014b50) {
        throw new IOException(path + ": corrupt central directory");
      }
      int flags = c.getShort(p + 8) & 0xffff;
      int method = c.getShort(p + 10) & 0xffff;
      long crc = c.getInt(p + 16) & 0xffffffffL;
      long csize = c.getInt(p + 20) & 0xffffffffL;
      long usize = c.getInt(p + 24) & 0xffffffffL;
      int nameLen = c.getShort(p + 28) & 0xffff;
      int extraLen = c.getShort(p + 30) & 0xffff;
      int commentLen = c.getShort(p + 32) & 0xffff;
      long offset = c.getInt(p + 42) & 0xffffffffL;
      String name = new String(file, p + 46, nameLen, StandardCharsets.UTF_8);
      if ((flags & 1) != 0) {
        throw new IOException(path + ": " + name + " is encrypted");
      }
      if (method != STORED && method != DEFLATED) {
        throw new IOException(path + ": " + name + " uses compression method " + method);
      }
      entries.add(new Entry(name, method, crc, csize, usize, offset));
      p += 46 + nameLen + extraLen + commentLen;
    }
  }

  /** The entry's bytes exactly as stored in the archive (deflated or not). */
  byte[] raw(Entry entry) throws IOException {
    int h = (int) entry.localHeaderOffset();
    ByteBuffer b = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
    if (b.getInt(h) != 0x04034b50) {
      throw new IOException(entry.name() + ": corrupt local header");
    }
    int nameLen = b.getShort(h + 26) & 0xffff;
    int extraLen = b.getShort(h + 28) & 0xffff;
    int start = h + 30 + nameLen + extraLen;
    return Arrays.copyOfRange(file, start, start + (int) entry.csize());
  }

  /** The entry's content, inflated if need be. */
  byte[] content(Entry entry) throws IOException {
    byte[] raw = raw(entry);
    if (entry.method() == STORED) {
      return raw;
    }
    Inflater inflater = new Inflater(true);
    try {
      inflater.setInput(raw);
      byte[] out = new byte[(int) entry.usize()];
      int n = 0;
      while (n < out.length) {
        int got = inflater.inflate(out, n, out.length - n);
        if (got == 0 && (inflater.finished() || inflater.needsInput())) {
          break;
        }
        n += got;
      }
      if (n != out.length) {
        throw new IOException(entry.name() + ": inflated to " + n + " bytes, expected " + out.length);
      }
      return out;
    } catch (DataFormatException e) {
      throw new IOException(entry.name() + ": " + e.getMessage(), e);
    } finally {
      inflater.end();
    }
  }

  @Override
  public void close() {}
}
