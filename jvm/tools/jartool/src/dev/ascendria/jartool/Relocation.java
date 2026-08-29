package dev.ascendria.jartool;

import java.util.List;

/**
 * One {@code relocate from to} rule with shadow's semantics: {@code from} is a package prefix;
 * classes below it, resources below the matching path, and string constants naming either move
 * to {@code to}. {@code excludes} are globs over dotted class names that stay where they are.
 */
final class Relocation {
  private final String fromClass; // "org.bstats."
  private final String toClass; // "com.velocitypowered.proxy.bstats."
  private final String fromPath; // "org/bstats/"
  private final String toPath;
  private final List<Glob> excludes;

  Relocation(String from, String to, List<Glob> excludes) {
    this.fromClass = from + ".";
    this.toClass = to + ".";
    this.fromPath = from.replace('.', '/') + "/";
    this.toPath = to.replace('.', '/') + "/";
    this.excludes = excludes;
  }

  private boolean excluded(String dottedClass) {
    for (Glob g : excludes) {
      if (g.matches(dottedClass)) {
        return true;
      }
    }
    return false;
  }

  /** Internal-name or resource path form ({@code org/bstats/Foo}); null if not ours. */
  String relocatePath(String path) {
    if (!path.startsWith(fromPath)) {
      return null;
    }
    if (excluded(path.replace('/', '.'))) {
      return null;
    }
    return toPath + path.substring(fromPath.length());
  }

  /** Dotted form ({@code org.bstats.Foo}); null if not ours. */
  String relocateClass(String dotted) {
    if (!dotted.startsWith(fromClass)) {
      return null;
    }
    if (excluded(dotted)) {
      return null;
    }
    return toClass + dotted.substring(fromClass.length());
  }
}
