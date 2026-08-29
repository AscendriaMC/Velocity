package dev.ascendria.jartool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * jartool: assemble one jar from many, with relocations, merges and a generated manifest.
 *
 * <pre>
 *   jartool --plan PLAN --out OUT.jar
 * </pre>
 *
 * PLAN is tab-separated, one directive per line:
 *
 * <pre>
 *   in              PATH                      an input jar; order matters
 *   relocate        FROM  TO  [EXCLUDE-GLOB...]  package prefix to move, excludes over dotted names
 *   exclude         GLOB                      drop matching entries (matched before relocation)
 *   merge           GLOB  POLICY              first | last | error | services | append | log4j2-plugin-cache
 *   duplicates      first | last | error      policy for everything no `merge` line matches
 *   module-info     drop | keep
 *   strip-signatures  true | false
 *   main-class      CLASS
 *   manifest        KEY  VALUE
 *   manifest-from-properties  FILE  PROPERTY  KEY
 * </pre>
 */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws IOException {
    Path plan = null;
    Path out = null;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--plan" -> plan = Path.of(args[++i]);
        case "--out" -> out = Path.of(args[++i]);
        default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
      }
    }
    if (plan == null || out == null) {
      throw new IllegalArgumentException("usage: jartool --plan PLAN --out OUT.jar");
    }
    Assembler assembler = new Assembler();
    for (String line : Files.readAllLines(plan, StandardCharsets.UTF_8)) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String[] f = line.split("\t");
      switch (f[0]) {
        case "in" -> assembler.inputs.add(Path.of(f[1]));
        case "relocate" -> {
          List<Glob> excludes = new ArrayList<>();
          for (int i = 3; i < f.length; i++) {
            excludes.add(new Glob(f[i]));
          }
          assembler.relocations.add(new Relocation(f[1], f[2], excludes));
        }
        case "exclude" -> assembler.excludes.add(new Glob(f[1]));
        case "merge" -> assembler.merges.add(new Assembler.MergeRule(new Glob(f[1]), Merge.parse(f[2])));
        case "duplicates" -> assembler.duplicates = Merge.parse(f[1]);
        case "module-info" -> assembler.dropModuleInfo = switch (f[1]) {
          case "drop" -> true;
          case "keep" -> false;
          default -> throw new IllegalArgumentException("module-info: " + f[1]);
        };
        case "strip-signatures" -> assembler.stripSignatures = Boolean.parseBoolean(f[1]);
        case "main-class" -> assembler.mainClass = f[1];
        case "manifest" -> assembler.manifest.put(f[1], f[2]);
        case "manifest-from-properties" -> {
          Properties p = new Properties();
          try (InputStream in = Files.newInputStream(Path.of(f[1]))) {
            p.load(in);
          }
          String value = p.getProperty(f[2]);
          if (value == null) {
            throw new IllegalArgumentException(f[1] + " has no property " + f[2]);
          }
          assembler.manifest.put(f[3], value);
        }
        default -> throw new IllegalArgumentException("unknown directive: " + f[0]);
      }
    }
    assembler.run(out);
  }
}
