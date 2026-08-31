package dev.ascendria.javacworker;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Just enough protobuf for Bazel's persistent worker protocol: varint-delimited WorkRequest and
 * WorkResponse on a byte stream. Hand-rolled so the worker needs nothing but a JDK.
 *
 * WorkRequest: arguments = 1 (string), request_id = 3 (int32), cancel = 4 (bool),
 * verbosity = 5 (int32), sandbox_dir = 6 (string). Inputs (2) are skipped.
 * WorkResponse: exit_code = 1 (int32), output = 2 (string), request_id = 3 (int32).
 */
final class Proto {
  private Proto() {}

  record WorkRequest(List<String> arguments, int requestId, boolean cancel, String sandboxDir) {}

  static long readVarint(InputStream in) throws IOException {
    long result = 0;
    int shift = 0;
    while (true) {
      int b = in.read();
      if (b < 0) {
        throw new EOFException();
      }
      result |= (long) (b & 0x7f) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
      shift += 7;
      if (shift >= 64) {
        throw new IOException("varint too long");
      }
    }
  }

  static void writeVarint(OutputStream out, long value) throws IOException {
    while ((value & ~0x7fL) != 0) {
      out.write((int) ((value & 0x7f) | 0x80));
      value >>>= 7;
    }
    out.write((int) value);
  }

  /** Reads one varint-delimited WorkRequest; null on clean end of stream. */
  static WorkRequest readRequest(InputStream in) throws IOException {
    long size;
    try {
      size = readVarint(in);
    } catch (EOFException e) {
      return null;
    }
    byte[] buf = in.readNBytes((int) size);
    if (buf.length != size) {
      throw new EOFException("truncated WorkRequest");
    }
    List<String> arguments = new ArrayList<>();
    int requestId = 0;
    boolean cancel = false;
    String sandboxDir = "";
    int p = 0;
    while (p < buf.length) {
      long tag = 0;
      int shift = 0;
      while (true) {
        int b = buf[p++] & 0xff;
        tag |= (long) (b & 0x7f) << shift;
        if ((b & 0x80) == 0) {
          break;
        }
        shift += 7;
      }
      int field = (int) (tag >>> 3);
      int wire = (int) (tag & 7);
      switch (wire) {
        case 0 -> { // varint
          long v = 0;
          shift = 0;
          while (true) {
            int b = buf[p++] & 0xff;
            v |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
              break;
            }
            shift += 7;
          }
          if (field == 3) {
            requestId = (int) v;
          } else if (field == 4) {
            cancel = v != 0;
          }
        }
        case 2 -> { // length-delimited
          long len = 0;
          shift = 0;
          while (true) {
            int b = buf[p++] & 0xff;
            len |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
              break;
            }
            shift += 7;
          }
          if (field == 1) {
            arguments.add(new String(buf, p, (int) len, StandardCharsets.UTF_8));
          } else if (field == 6) {
            sandboxDir = new String(buf, p, (int) len, StandardCharsets.UTF_8);
          }
          p += (int) len;
        }
        default -> throw new IOException("unsupported wire type " + wire + " for field " + field);
      }
    }
    return new WorkRequest(arguments, requestId, cancel, sandboxDir);
  }

  static void writeResponse(OutputStream out, int exitCode, String output, int requestId)
      throws IOException {
    byte[] text = output.getBytes(StandardCharsets.UTF_8);
    // Sizes: field 1 varint, field 2 length-delimited, field 3 varint.
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    if (exitCode != 0) {
      body.write(1 << 3);
      writeVarint(body, exitCode);
    }
    if (text.length > 0) {
      body.write((2 << 3) | 2);
      writeVarint(body, text.length);
      body.write(text, 0, text.length);
    }
    if (requestId != 0) {
      body.write(3 << 3);
      writeVarint(body, requestId);
    }
    byte[] b = body.toByteArray();
    writeVarint(out, b.length);
    out.write(b);
    out.flush();
  }
}
