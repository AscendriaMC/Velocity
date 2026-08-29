# jvm//

Buck2 rules for building JVM code without Gradle, plus the tool that assembles shaded jars.
Written as a cell so it can be lifted into its own repository and consumed as an external cell;
nothing in here loads from `root//` or `prelude//`.

## Contract with the consumer

- `toolchains//:java` — an instance of `java_toolchain` (rules/java.bzl). Tools are bare names
  looked up on PATH; `version` is threaded into every action's env so a JDK change invalidates
  what it should. Use `read_root_config` in the consumer's `toolchains/BUCK` so a
  `.buckconfig.local` in the project root can supply it.
- Dependencies are flat: a target's `deps` is its classpath, in order. The consumer produces those
  lists however it likes (Velocity derives them from Gradle's resolution; see
  `tools/gen-third-party.py` there).

## Rules (`rules/java.bzl`)

| rule | what |
|---|---|
| `remote_jar(url, sha256)` | a jar fetched by checksum |
| `prebuilt_jar(jar)` | a jar already in the tree |
| `java_library(srcs, resources, resource_files, deps, processors, processor_options, javac_flags)` | javac + jar; `resources` = {root: files}, `resource_files` = {path-in-jar: file} |
| `java_binary(main_class, deps)` | a main class and its classpath (a provider, not a launcher) |
| `jar_assembly(inputs, relocations, relocation_excludes, excludes, merges, duplicates, module_info, strip_signatures, main_class, manifest, manifest_from_properties)` | one jar from many; see below |

## jar_assembly

What Gradle's shadow plugin does, as a build step with declared inputs and deterministic output:

- **relocations** `{"org.bstats": "com.velocitypowered.proxy.bstats"}` — moves classes, resources
  and service declarations below the package, rewrites references in class files (ASM) and string
  constants naming the package in dotted or slashed form (reflection needs that; an unrelated
  string that starts with the package moves too). Applies under `META-INF/versions/N/` as well.
  `relocation_excludes` are globs over dotted class names that stay put.
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

The tool itself (`tools/jartool`) is ~500 lines of Java on ASM, built by these same rules; its
plan format is documented in `Main.java`.
