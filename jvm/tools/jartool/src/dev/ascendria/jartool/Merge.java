package dev.ascendria.jartool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** What to do when several inputs provide the same entry. */
enum Merge {
  /** The first input's copy, in input order. Shadow's and the {@code jar} tool's behaviour. */
  FIRST,
  LAST,
  /** Fail the build. */
  ERROR,
  /** {@code META-INF/services}: union of provider lines, first occurrence wins, comments dropped. */
  SERVICES,
  /** Text files: concatenate. */
  APPEND,
  /** See {@link Log4j2PluginCache}. */
  LOG4J2_PLUGIN_CACHE;

  static Merge parse(String s) {
    return switch (s) {
      case "first" -> FIRST;
      case "last" -> LAST;
      case "error" -> ERROR;
      case "services" -> SERVICES;
      case "append" -> APPEND;
      case "log4j2-plugin-cache" -> LOG4J2_PLUGIN_CACHE;
      default -> throw new IllegalArgumentException("unknown merge policy: " + s);
    };
  }

  byte[] apply(String path, List<byte[]> parts, List<String> owners, Relocator relocator)
      throws IOException {
    if (parts.size() == 1) {
      return parts.get(0);
    }
    return switch (this) {
      case FIRST -> parts.get(0);
      case LAST -> parts.get(parts.size() - 1);
      case ERROR ->
          throw new IOException(path + " is provided by more than one input: " + owners);
      case SERVICES -> {
        Set<String> lines = new LinkedHashSet<>();
        for (byte[] part : parts) {
          for (String line : new String(part, StandardCharsets.UTF_8).split("\n")) {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#")) {
              lines.add(relocator.className(t));
            }
          }
        }
        yield (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
      }
      case APPEND -> {
        List<String> chunks = new ArrayList<>();
        for (byte[] part : parts) {
          String s = new String(part, StandardCharsets.UTF_8);
          chunks.add(s.endsWith("\n") ? s : s + "\n");
        }
        yield String.join("", chunks).getBytes(StandardCharsets.UTF_8);
      }
      case LOG4J2_PLUGIN_CACHE -> Log4j2PluginCache.merge(parts, relocator);
    };
  }
}
