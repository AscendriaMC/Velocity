# jvm//

Buck2 rules for building JVM code without Gradle, plus the tool that assembles shaded jars.
Written as a cell so it can be lifted into its own repository and consumed as an external cell;
nothing in here loads from `root//` or `prelude//`.

## Contract with the consumer

- `toolchains//:java` — an instance of `java_toolchain` (rules/java.bzl). `java`, `javac` and
  `jar` are targets providing `RunInfo`, taken as exec deps: a buck2.nix `flake.package` (then
  the tool is an input of every action, and is realised on whichever machine runs it), or a
  `system_tool` naming a PATH entry (then pass `version` so a JDK change still invalidates).
- Dependencies are flat: a target's `deps` is its classpath, in order. The consumer produces those
  lists however it likes (Velocity derives them from Gradle's resolution; see
  `tools/gen-third-party.py` there).

## Rules (`rules/java.bzl`)

| rule | what |
|---|---|
| `remote_jar(url, sha256)` | a jar fetched by checksum |
| `prebuilt_jar(jar)` | a jar already in the tree |
| `java_library(srcs, resources, deps, processors, processor_options, javac_flags)` | javac + jar; `resources` = {root: files} |
| `java_binary(main_class, deps)` | a main class and its classpath (a provider, not a launcher) |
| `jar_assembly(inputs, resource_files, relocations, relocation_excludes, excludes, merges, duplicates, module_info, strip_signatures, main_class, manifest, manifest_from_properties)` | one jar from many; see below |

## jar_assembly

What Gradle's shadow plugin does, as a build step with declared inputs and deterministic output:

- **relocations** `{"org.bstats": "com.velocitypowered.proxy.bstats"}` — moves classes, resources
  and service declarations below the package, rewrites references in class files (ASM) and string
  constants naming the package in dotted or slashed form (reflection needs that; an unrelated
  string that starts with the package moves too). Applies under `META-INF/versions/N/` as well.
  `relocation_excludes` are globs over dotted class names that stay put.
- **resource_files** `{path-in-jar: file}` — loose files at explicit paths (built artifacts, e.g.
  native libraries at the names a loader expects).
- **excludes** — Ant globs over entry paths, matched before relocation.
- **merges** `{glob: policy}` — what to do when several inputs provide the same entry:
  `first` (shadow's default), `last`, `error`, `services` (union of providers),
  `append` (text concatenation), `log4j2-plugin-cache` (union of `Log4j2Plugins.dat`).
  `duplicates` is the policy for everything no glob matches.
- **manifest** — generated; `main_class`, explicit attributes, `manifest_from_properties`
  `{"Implementation-Version": (file, "version")}` for values kept in a properties file,
  `Multi-Release: true` added automatically when versioned entries exist.
- Signature files are stripped and `module-info.class` dropped unless told otherwise.
- Output entries are sorted and timestamped 1980-02-01, so identical inputs give identical bytes.
- Entries that come through unchanged are copied as stored -- never inflated -- and only
  rewritten or merged entries are compressed, in parallel; the tool has its own zip reader and
  writer for that (plain zip: no zip64, fails loudly past 65535 entries or 4 GiB).

The tool itself (`tools/jartool`) is ~500 lines of Java on ASM, built by these same rules; its
plan format is documented in `Main.java`.
