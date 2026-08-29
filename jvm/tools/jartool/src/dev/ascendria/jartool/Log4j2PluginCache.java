package dev.ascendria.jartool;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat}: the index
 * log4j-core's annotation processor writes per jar and which log4j reads instead of scanning.
 * Several jars each carry one; a fat jar needs their union, or every plugin outside the first
 * jar's index is invisible. Format (log4j-core's PluginCache): int categories; per category: UTF
 * name, int count; per plugin: UTF key, UTF class, UTF name, boolean printable, boolean defer.
 */
final class Log4j2PluginCache {
  private Log4j2PluginCache() {}

  record Plugin(String key, String className, String name, boolean printable, boolean defer) {}

  static byte[] merge(List<byte[]> parts, Relocator relocator) throws IOException {
    // category -> key -> plugin; first definition of a key wins, like the first-wins default.
    Map<String, Map<String, Plugin>> categories = new TreeMap<>();
    for (byte[] part : parts) {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(part));
      int categoryCount = in.readInt();
      for (int i = 0; i < categoryCount; i++) {
        Map<String, Plugin> plugins =
            categories.computeIfAbsent(in.readUTF(), k -> new TreeMap<>());
        int pluginCount = in.readInt();
        for (int j = 0; j < pluginCount; j++) {
          String key = in.readUTF();
          String className = relocator.className(in.readUTF());
          String name = in.readUTF();
          boolean printable = in.readBoolean();
          boolean defer = in.readBoolean();
          plugins.putIfAbsent(key, new Plugin(key, className, name, printable, defer));
        }
      }
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeInt(categories.size());
    for (Map.Entry<String, Map<String, Plugin>> category : categories.entrySet()) {
      out.writeUTF(category.getKey());
      out.writeInt(category.getValue().size());
      for (Plugin p : category.getValue().values()) {
        out.writeUTF(p.key());
        out.writeUTF(p.className());
        out.writeUTF(p.name());
        out.writeBoolean(p.printable());
        out.writeBoolean(p.defer());
      }
    }
    out.flush();
    return bytes.toByteArray();
  }
}
