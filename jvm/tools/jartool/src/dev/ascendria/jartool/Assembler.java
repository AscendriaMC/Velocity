package dev.ascendria.jartool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Builds one jar out of many. Entries are taken from the inputs in order, relocated, filtered,
 * merged where a policy says so, and written sorted with a fixed timestamp, so the same inputs
 * produce the same bytes.
 *
 * An entry that comes through unchanged is copied as stored -- its deflated bytes are never
 * inflated -- and only rewritten or merged entries are compressed, in parallel. That is most of
 * the difference between this and a jar tool: a 42 MB proxy jar is almost entirely untouched
 * third-party classes.
 */
final class Assembler {
  /** Gradle's choice too: the earliest date every zip tool accepts. */
  private static final LocalDateTime FIXED_TIME = LocalDateTime.of(1980, 2, 1, 0, 0, 0);

  private static final Pattern SIGNATURE =
      Pattern.compile("META-INF/(?:[^/]+\\.(?:SF|DSA|RSA|EC)|SIG-[^/]+|INDEX\\.LIST)");
  private static final Pattern MULTI_RELEASE = Pattern.compile("META-INF/versions/(\\d+)/(.*)");
  private static final String SERVICES = "META-INF/services/";

  /** One input's copy of an entry: as stored (reader + entry), or already-materialised bytes. */
  record Part(ZipReader reader, ZipReader.Entry entry, byte[] data, String owner) {
    boolean untouched() {
      return data == null;
    }

    byte[] content() throws IOException {
      return data != null ? data : reader.content(entry);
    }
  }

  /** What to write for one output path. */
  private sealed interface Out {}

  /** Copy this input entry's stored bytes. */
  private record Raw(ZipReader reader, ZipReader.Entry entry) implements Out {}

  /** Compress these bytes. */
  private record Fresh(byte[] data) implements Out {}

  private record Compressed(int method, long crc, long csize, long usize, byte[] data) {}

  record MergeRule(Glob glob, Merge policy) {}

  final List<Path> inputs = new ArrayList<>();
  final Map<String, Path> files = new LinkedHashMap<>();
  final List<Relocation> relocations = new ArrayList<>();
  final List<Glob> excludes = new ArrayList<>();
  final List<MergeRule> merges = new ArrayList<>();
  Merge duplicates = Merge.FIRST;
  boolean dropModuleInfo = true;
  boolean stripSignatures = true;
  String mainClass;
  final Map<String, String> manifest = new LinkedHashMap<>();

  private Relocator relocator;

  void run(Path out) throws IOException {
    relocator = new Relocator(relocations);
    List<ZipReader> readers = new ArrayList<>();
    try {
      Map<String, List<Part>> entries = new TreeMap<>();
      for (Path input : inputs) {
        ZipReader reader = new ZipReader(input);
        readers.add(reader);
        collect(reader, input.getFileName().toString(), entries);
      }
      for (Map.Entry<String, Path> f : files.entrySet()) {
        entries.computeIfAbsent(f.getKey(), k -> new ArrayList<>())
            .add(new Part(null, null, Files.readAllBytes(f.getValue()), f.getValue().toString()));
      }

      Map<String, Out> resolved = new TreeMap<>();
      for (Map.Entry<String, List<Part>> e : entries.entrySet()) {
        resolved.put(e.getKey(), resolve(e.getKey(), e.getValue()));
      }
      resolved.put("META-INF/MANIFEST.MF", new Fresh(manifest(resolved.keySet())));

      write(out, resolved);
    } finally {
      for (ZipReader r : readers) {
        r.close();
      }
    }
  }

  private Out resolve(String path, List<Part> parts) throws IOException {
    Merge policy = policyFor(path);
    Part chosen = switch (policy) {
      case FIRST -> parts.get(0);
      case LAST -> parts.get(parts.size() - 1);
      default -> parts.size() == 1 ? parts.get(0) : null;
    };
    if (chosen != null) {
      return chosen.untouched() ? new Raw(chosen.reader(), chosen.entry()) : new Fresh(chosen.data());
    }
    List<byte[]> data = new ArrayList<>();
    List<String> owners = new ArrayList<>();
    for (Part p : parts) {
      data.add(p.content());
      owners.add(p.owner());
    }
    return new Fresh(policy.apply(path, data, owners, relocator));
  }

  private Merge policyFor(String path) {
    for (MergeRule rule : merges) {
      if (rule.glob().matches(path)) {
        return rule.policy();
      }
    }
    return duplicates;
  }

  private void collect(ZipReader reader, String owner, Map<String, List<Part>> entries) throws IOException {
    for (ZipReader.Entry entry : reader.entries) {
      String name = entry.name();
      if (name.endsWith("/") || !keep(name)) {
        continue;
      }
      String target = relocate(name);
      byte[] data = null;
      if (!relocator.isEmpty() && needsRewrite(target)) {
        data = transform(target, reader.content(entry));
      }
      entries.computeIfAbsent(target, k -> new ArrayList<>()).add(new Part(reader, entry, data, owner));
    }
  }

  private boolean keep(String name) {
    if (name.equals("META-INF/MANIFEST.MF")) {
      return false; // ours is generated
    }
    if (stripSignatures && SIGNATURE.matcher(name).matches()) {
      return false;
    }
    if (dropModuleInfo && versionless(name).equals("module-info.class")) {
      return false;
    }
    for (Glob g : excludes) {
      if (g.matches(name)) {
        return false;
      }
    }
    return true;
  }

  private static String versionless(String name) {
    var m = MULTI_RELEASE.matcher(name);
    return m.matches() ? m.group(2) : name;
  }

  /** Relocates the path, including below META-INF/versions/N/ and service file names. */
  private String relocate(String name) {
    var m = MULTI_RELEASE.matcher(name);
    String prefix = "";
    String rest = name;
    if (m.matches()) {
      prefix = "META-INF/versions/" + m.group(1) + "/";
      rest = m.group(2);
    }
    if (rest.startsWith(SERVICES)) {
      rest = SERVICES + relocator.className(rest.substring(SERVICES.length()));
    } else {
      rest = relocator.path(rest);
    }
    return prefix + rest;
  }

  private static boolean needsRewrite(String target) {
    String rest = versionless(target);
    return rest.endsWith(".class") || rest.startsWith(SERVICES);
  }

  /** Rewrites class references inside class files and service declarations. */
  private byte[] transform(String target, byte[] data) {
    String rest = versionless(target);
    if (rest.endsWith(".class")) {
      var reader = new org.objectweb.asm.ClassReader(data);
      var writer = new org.objectweb.asm.ClassWriter(0);
      reader.accept(new org.objectweb.asm.commons.ClassRemapper(writer, relocator), 0);
      return writer.toByteArray();
    }
    StringBuilder sb = new StringBuilder();
    for (String line : new String(data, StandardCharsets.UTF_8).split("\n", -1)) {
      String t = line.strip();
      sb.append(t.isEmpty() || t.startsWith("#") ? line : relocator.className(t)).append('\n');
    }
    // split(-1) keeps a trailing empty segment; drop the newline it would add.
    sb.setLength(sb.length() - 1);
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  private byte[] manifest(Iterable<String> paths) throws IOException {
    Manifest m = new Manifest();
    Attributes a = m.getMainAttributes();
    a.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    for (Map.Entry<String, String> e : manifest.entrySet()) {
      a.putValue(e.getKey(), e.getValue());
    }
    if (mainClass != null) {
      a.put(Attributes.Name.MAIN_CLASS, mainClass);
    }
    boolean multiRelease = false;
    for (String p : paths) {
      if (p.startsWith("META-INF/versions/")) {
        multiRelease = true;
        break;
      }
    }
    if (multiRelease && a.getValue("Multi-Release") == null) {
      a.putValue("Multi-Release", "true");
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    m.write(bytes);
    return bytes.toByteArray();
  }

  private static Compressed compress(byte[] data) {
    CRC32 crc = new CRC32();
    crc.update(data);
    Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
    try {
      deflater.setInput(data);
      deflater.finish();
      ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 2));
      byte[] buf = new byte[1 << 16];
      while (!deflater.finished()) {
        int n = deflater.deflate(buf);
        out.write(buf, 0, n);
      }
      byte[] compressed = out.toByteArray();
      // Stored is smaller for incompressible data; jar tools make the same call.
      if (compressed.length >= data.length) {
        return new Compressed(ZipReader.STORED, crc.getValue(), data.length, data.length, data);
      }
      return new Compressed(ZipReader.DEFLATED, crc.getValue(), compressed.length, data.length, compressed);
    } finally {
      deflater.end();
    }
  }

  private void write(Path out, Map<String, Out> resolved) throws IOException {
    // Compress everything that has to be compressed, in parallel; the writing itself is serial
    // and in name order, so the result does not depend on scheduling.
    Map<String, Compressed> fresh = new TreeMap<>();
    resolved.entrySet().parallelStream()
        .filter(e -> e.getValue() instanceof Fresh)
        .map(e -> Map.entry(e.getKey(), compress(((Fresh) e.getValue()).data())))
        .forEachOrdered(e -> {
          synchronized (fresh) {
            fresh.put(e.getKey(), e.getValue());
          }
        });

    try (ZipWriter zip = new ZipWriter(out, FIXED_TIME)) {
      TreeSet<String> dirs = new TreeSet<>();
      // Manifest first, as JarInputStream expects.
      directory(zip, "META-INF/", dirs);
      put(zip, "META-INF/MANIFEST.MF", resolved.get("META-INF/MANIFEST.MF"), fresh);
      for (Map.Entry<String, Out> e : resolved.entrySet()) {
        String name = e.getKey();
        if (name.equals("META-INF/MANIFEST.MF")) {
          continue;
        }
        int slash = -1;
        while ((slash = name.indexOf('/', slash + 1)) >= 0) {
          directory(zip, name.substring(0, slash + 1), dirs);
        }
        put(zip, name, e.getValue(), fresh);
      }
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  private static void put(ZipWriter zip, String name, Out out, Map<String, Compressed> fresh) throws IOException {
    switch (out) {
      case Raw r -> zip.add(name, r.entry().method(), r.entry().crc(), r.entry().csize(), r.entry().usize(), r.reader().raw(r.entry()));
      case Fresh f -> {
        Compressed c = fresh.get(name);
        zip.add(name, c.method(), c.crc(), c.csize(), c.usize(), c.data());
      }
    }
  }

  private static void directory(ZipWriter zip, String name, TreeSet<String> written) throws IOException {
    if (written.add(name)) {
      zip.directory(name);
    }
  }
}
