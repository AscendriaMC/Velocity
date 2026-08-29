package dev.ascendria.jartool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Builds one jar out of many. Entries are taken from the inputs in order, relocated, filtered,
 * merged where a policy says so, and written sorted with a fixed timestamp, so the same inputs
 * produce the same bytes.
 */
final class Assembler {
  /** Gradle's choice too: the earliest date every zip tool accepts. */
  private static final LocalDateTime FIXED_TIME = LocalDateTime.of(1980, 2, 1, 0, 0, 0);

  private static final Pattern SIGNATURE =
      Pattern.compile("META-INF/(?:[^/]+\\.(?:SF|DSA|RSA|EC)|SIG-[^/]+|INDEX\\.LIST)");
  private static final Pattern MULTI_RELEASE = Pattern.compile("META-INF/versions/(\\d+)/(.*)");
  private static final String SERVICES = "META-INF/services/";

  record Part(byte[] data, String owner) {}

  record MergeRule(Glob glob, Merge policy) {}

  final List<Path> inputs = new ArrayList<>();
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
    Map<String, List<Part>> entries = new TreeMap<>();
    for (Path input : inputs) {
      collect(input, entries);
    }

    Map<String, byte[]> resolved = new TreeMap<>();
    for (Map.Entry<String, List<Part>> e : entries.entrySet()) {
      List<Part> parts = e.getValue();
      List<byte[]> data = parts.stream().map(Part::data).toList();
      List<String> owners = parts.stream().map(Part::owner).toList();
      resolved.put(e.getKey(), policyFor(e.getKey()).apply(e.getKey(), data, owners, relocator));
    }

    write(out, resolved);
  }

  private Merge policyFor(String path) {
    for (MergeRule rule : merges) {
      if (rule.glob().matches(path)) {
        return rule.policy();
      }
    }
    return duplicates;
  }

  private void collect(Path input, Map<String, List<Part>> entries) throws IOException {
    String owner = input.getFileName().toString();
    try (ZipFile zip = new ZipFile(input.toFile())) {
      Enumeration<? extends ZipEntry> en = zip.entries();
      while (en.hasMoreElements()) {
        ZipEntry entry = en.nextElement();
        if (entry.isDirectory()) {
          continue;
        }
        String name = entry.getName();
        if (!keep(name)) {
          continue;
        }
        byte[] data;
        try (InputStream in = zip.getInputStream(entry)) {
          data = in.readAllBytes();
        }
        String target = relocate(name);
        if (!relocator.isEmpty()) {
          data = transform(target, data);
        }
        entries.computeIfAbsent(target, k -> new ArrayList<>()).add(new Part(data, owner));
      }
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

  /** Rewrites class references inside class files and service declarations. */
  private byte[] transform(String target, byte[] data) {
    String rest = versionless(target);
    if (rest.endsWith(".class")) {
      var reader = new org.objectweb.asm.ClassReader(data);
      var writer = new org.objectweb.asm.ClassWriter(0);
      reader.accept(new org.objectweb.asm.commons.ClassRemapper(writer, relocator), 0);
      return writer.toByteArray();
    }
    if (rest.startsWith(SERVICES)) {
      StringBuilder sb = new StringBuilder();
      for (String line : new String(data, StandardCharsets.UTF_8).split("\n", -1)) {
        String t = line.strip();
        sb.append(t.isEmpty() || t.startsWith("#") ? line : relocator.className(t)).append('\n');
      }
      // split(-1) keeps a trailing empty segment; drop the newline it would add.
      sb.setLength(sb.length() - 1);
      return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
    return data;
  }

  private byte[] manifest(Map<String, byte[]> resolved) throws IOException {
    Manifest m = new Manifest();
    Attributes a = m.getMainAttributes();
    a.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    for (Map.Entry<String, String> e : manifest.entrySet()) {
      a.putValue(e.getKey(), e.getValue());
    }
    if (mainClass != null) {
      a.put(Attributes.Name.MAIN_CLASS, mainClass);
    }
    boolean multiRelease = resolved.keySet().stream().anyMatch(k -> k.startsWith("META-INF/versions/"));
    if (multiRelease && a.getValue("Multi-Release") == null) {
      a.putValue("Multi-Release", "true");
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    m.write(bytes);
    return bytes.toByteArray();
  }

  private void write(Path out, Map<String, byte[]> resolved) throws IOException {
    byte[] manifestBytes = manifest(resolved);
    try (OutputStream fs = Files.newOutputStream(out);
        ZipOutputStream zip = new ZipOutputStream(fs)) {
      TreeSet<String> written = new TreeSet<>();
      // Manifest first, as JarInputStream expects.
      directory(zip, "META-INF/", written);
      file(zip, "META-INF/MANIFEST.MF", manifestBytes);
      for (Map.Entry<String, byte[]> e : resolved.entrySet()) {
        String name = e.getKey();
        int slash = -1;
        while ((slash = name.indexOf('/', slash + 1)) >= 0) {
          directory(zip, name.substring(0, slash + 1), written);
        }
        file(zip, name, e.getValue());
      }
    }
  }

  private static void directory(ZipOutputStream zip, String name, TreeSet<String> written)
      throws IOException {
    if (!written.add(name)) {
      return;
    }
    ZipEntry e = new ZipEntry(name);
    e.setTimeLocal(FIXED_TIME);
    zip.putNextEntry(e);
    zip.closeEntry();
  }

  private static void file(ZipOutputStream zip, String name, byte[] data) throws IOException {
    ZipEntry e = new ZipEntry(name);
    e.setTimeLocal(FIXED_TIME);
    zip.putNextEntry(e);
    zip.write(data);
    zip.closeEntry();
  }
}
