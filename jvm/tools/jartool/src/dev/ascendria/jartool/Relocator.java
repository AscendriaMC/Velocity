package dev.ascendria.jartool;

import java.util.List;
import org.objectweb.asm.commons.Remapper;

/**
 * Applies every relocation to class references, string constants, resource paths and service
 * declarations. String constants are rewritten on a prefix match in either dotted or slashed
 * form, as shadow and jarjar do, because that is what reflection-driven code needs; the cost is
 * that an unrelated string that happens to start with a relocated package moves with it.
 */
final class Relocator extends Remapper {
  private final List<Relocation> relocations;

  Relocator(List<Relocation> relocations) {
    this.relocations = relocations;
  }

  boolean isEmpty() {
    return relocations.isEmpty();
  }

  /** Resource or class-file path; returns the input when nothing applies. */
  String path(String path) {
    for (Relocation r : relocations) {
      String moved = r.relocatePath(path);
      if (moved != null) {
        return moved;
      }
    }
    return path;
  }

  /** Dotted class name; returns the input when nothing applies. */
  String className(String dotted) {
    for (Relocation r : relocations) {
      String moved = r.relocateClass(dotted);
      if (moved != null) {
        return moved;
      }
    }
    return dotted;
  }

  @Override
  public String map(String internalName) {
    return path(internalName);
  }

  @Override
  public Object mapValue(Object value) {
    if (value instanceof String s) {
      String moved = className(s);
      if (!moved.equals(s)) {
        return moved;
      }
      moved = path(s);
      if (!moved.equals(s)) {
        return moved;
      }
      return s;
    }
    return super.mapValue(value);
  }
}
