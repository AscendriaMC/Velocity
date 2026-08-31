package dev.ascendria.javacworker;

import dev.ascendria.jartool.ZipWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * The compile step of jvm//'s java_library: javac in-process, then a deterministic jar. One
 * binary, two modes with byte-identical output:
 *
 *   Main @plan                     one shot -- what the action runs locally or on a cache miss
 *   Main --persistent_worker      Bazel persistent-worker protocol on stdio -- the executor
 *                                 keeps this JVM (and its JIT-warm compiler) across actions
 *
 * The plan is one token per line:
 *
 *   --classes DIR  --gen DIR  --jar FILE  [--res DIR]  --  <javac arguments and sources...>
 */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0 || (args.length == 1 && args[0].equals("--persistent_worker"))) {
      runWorker();
      return;
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    int exit = runOne(expand(List.of(args), Path.of(".")), Path.of("."), output);
    System.err.write(output.toByteArray());
    System.exit(exit);
  }

  private static void runWorker() throws IOException {
    InputStream in = System.in;
    OutputStream out = System.out;
    // The protocol owns stdout; anything the compiler (or a library) prints must not corrupt it.
    System.setOut(new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8));
    while (true) {
      Proto.WorkRequest request = Proto.readRequest(in);
      if (request == null) {
        return;
      }
      if (request.cancel()) {
        Proto.writeResponse(out, 0, "", request.requestId());
        continue;
      }
      Path root = request.sandboxDir().isEmpty() ? Path.of(".") : Path.of(request.sandboxDir());
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      int exit;
      try {
        exit = runOne(expand(request.arguments(), root), root, output);
      } catch (Throwable t) {
        t.printStackTrace(new PrintStream(output, false, StandardCharsets.UTF_8));
        exit = 1;
      }
      Proto.writeResponse(out, exit, output.toString(StandardCharsets.UTF_8), request.requestId());
    }
  }

  /** Expands {@code @file} arguments (one token per line), resolving paths against {@code root}. */
  private static List<String> expand(List<String> args, Path root) throws IOException {
    List<String> out = new ArrayList<>();
    for (String a : args) {
      if (a.startsWith("@")) {
        Path f = root.resolve(a.substring(1));
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
          if (!line.isEmpty()) {
            out.add(line);
          }
        }
      } else {
        out.add(a);
      }
    }
    return out;
  }

  private static int runOne(List<String> args, Path root, ByteArrayOutputStream output)
      throws IOException {
    Path classes = null;
    Path gen = null;
    Path jar = null;
    Path res = null;
    int i = 0;
    while (i < args.size() && !args.get(i).equals("--")) {
      switch (args.get(i)) {
        case "--classes" -> classes = root.resolve(args.get(++i));
        case "--gen" -> gen = root.resolve(args.get(++i));
        case "--jar" -> jar = root.resolve(args.get(++i));
        case "--res" -> res = root.resolve(args.get(++i));
        default -> throw new IllegalArgumentException("unknown plan option: " + args.get(i));
      }
      i++;
    }
    if (classes == null || gen == null || jar == null || i == args.size()) {
      throw new IllegalArgumentException("plan needs --classes, --gen, --jar and a -- separator");
    }
    List<String> javacArgs = new ArrayList<>(args.subList(i + 1, args.size()));
    // Paths inside the javac arguments are project-root-relative; under the persistent worker
    // the process cwd is not the action's root, so resolve by running javac against absolute
    // -d/-s and leaving the rest to a cwd change below. javac has no chdir, so instead every
    // relative path argument is made absolute against root.
    List<String> resolved = new ArrayList<>(javacArgs.size() + 4);
    resolved.add("-d");
    resolved.add(classes.toString());
    resolved.add("-s");
    resolved.add(gen.toString());
    for (String a : javacArgs) {
      resolved.add(resolvePaths(a, root));
    }
    Files.createDirectories(classes);
    Files.createDirectories(gen);

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    PrintStream diag = new PrintStream(output, false, StandardCharsets.UTF_8);
    int exit = compiler.run(InputStream.nullInputStream(), diag, diag, resolved.toArray(String[]::new));
    diag.flush();
    if (exit != 0) {
      return exit;
    }
    pack(jar, classes, res);
    return 0;
  }

  /**
   * Makes path-shaped arguments absolute against the action root. javac arguments are either
   * options (start with '-'), source files (end in .java), or option values; of the values, the
   * path-valued ones are classpaths and directories. Anything containing a path separator or
   * ending in .java/.jar is treated as paths; single tokens without separators pass through.
   */
  private static String resolvePaths(String arg, Path root) {
    if (arg.startsWith("-") || root.toString().equals(".")) {
      return arg;
    }
    // Classpath-like: resolve each element.
    if (arg.contains(":") && (arg.contains("/") || arg.endsWith(".jar"))) {
      StringBuilder sb = new StringBuilder();
      for (String part : arg.split(":", -1)) {
        if (!sb.isEmpty()) {
          sb.append(':');
        }
        sb.append(part.isEmpty() || part.startsWith("/") ? part : root.resolve(part));
      }
      return sb.toString();
    }
    if (arg.contains("/") || arg.endsWith(".java") || arg.endsWith(".jar")) {
      return arg.startsWith("/") ? arg : root.resolve(arg).toString();
    }
    return arg;
  }

  /** Gradle's choice too: the earliest date every zip tool accepts. */
  private static final LocalDateTime FIXED_TIME = LocalDateTime.of(1980, 2, 1, 0, 0, 0);

  /** A deterministic jar of the classes (and resources) directories: sorted, fixed time. */
  private static void pack(Path jar, Path classes, Path res) throws IOException {
    Map<String, Path> files = new TreeMap<>();
    collect(classes, files);
    if (res != null) {
      collect(res, files);
    }
    try (ZipWriter zip = new ZipWriter(jar, FIXED_TIME)) {
      TreeSet<String> dirs = new TreeSet<>();
      for (Map.Entry<String, Path> e : files.entrySet()) {
        String name = e.getKey();
        int slash = -1;
        while ((slash = name.indexOf('/', slash + 1)) >= 0) {
          String dir = name.substring(0, slash + 1);
          if (dirs.add(dir)) {
            zip.directory(dir);
          }
        }
        byte[] data = Files.readAllBytes(e.getValue());
        CRC32 crc = new CRC32();
        crc.update(data);
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
          deflater.setInput(data);
          deflater.finish();
          ByteArrayOutputStream compressed = new ByteArrayOutputStream(Math.max(64, data.length / 2));
          byte[] buf = new byte[1 << 16];
          while (!deflater.finished()) {
            compressed.write(buf, 0, deflater.deflate(buf));
          }
          byte[] c = compressed.toByteArray();
          if (c.length >= data.length) {
            zip.add(name, 0, crc.getValue(), data.length, data.length, data);
          } else {
            zip.add(name, 8, crc.getValue(), c.length, data.length, c);
          }
        } finally {
          deflater.end();
        }
      }
    }
  }

  private static void collect(Path rootDir, Map<String, Path> files) throws IOException {
    if (!Files.isDirectory(rootDir)) {
      return;
    }
    try (var stream = Files.walk(rootDir)) {
      stream.filter(Files::isRegularFile).forEach(f -> files.put(rootDir.relativize(f).toString().replace('\\', '/'), f));
    }
  }
}
