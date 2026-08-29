package dev.ascendria.jartool;

import java.util.regex.Pattern;

/** Ant-style path glob: {@code **} spans directories, {@code *} and {@code ?} stay within one. */
final class Glob {
  private final String source;
  private final Pattern pattern;

  Glob(String glob) {
    this.source = glob;
    StringBuilder re = new StringBuilder("^");
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (c == '*') {
        if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
          i++;
          // "**/" matches zero or more directories; a bare "**" matches anything.
          if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
            i++;
            re.append("(?:.*/)?");
          } else {
            re.append(".*");
          }
        } else {
          re.append("[^/]*");
        }
      } else if (c == '?') {
        re.append("[^/]");
      } else {
        re.append(Pattern.quote(String.valueOf(c)));
      }
    }
    this.pattern = Pattern.compile(re.append('$').toString());
  }

  boolean matches(String path) {
    return pattern.matcher(path).matches();
  }

  @Override
  public String toString() {
    return source;
  }
}
