package org.geysermc.paletter.capture;

import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;

import java.util.Map;

/**
 * Indented, typed SNBT ({@code 1b}, {@code 2s}, {@code 3}, {@code 4L}, {@code 5.0f}, {@code 6.0d}) in wire order, so a
 * capture can be read and diffed as text.
 */
final class Snbt {
    private Snbt() {
    }

    static String write(Object tag) {
        StringBuilder out = new StringBuilder();
        write(out, tag, 0);
        return out.toString();
    }

    private static void write(StringBuilder out, Object tag, int indent) {
        if (tag instanceof NbtMap map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                pad(out, indent + 1).append(quote(entry.getKey())).append(": ");
                write(out, entry.getValue(), indent + 1);
                out.append(++i < map.size() ? ",\n" : "\n");
            }
            pad(out, indent).append('}');
        } else if (tag instanceof NbtList<?> list) {
            if (list.isEmpty()) {
                out.append("[] /* ").append(list.getType().getTypeName()).append(" */");
                return;
            }
            boolean simple = !(list.get(0) instanceof NbtMap) && !(list.get(0) instanceof NbtList);
            if (simple) {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) out.append(", ");
                    write(out, list.get(i), indent);
                }
                out.append(']');
                return;
            }
            out.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                pad(out, indent + 1);
                write(out, list.get(i), indent + 1);
                out.append(i + 1 < list.size() ? ",\n" : "\n");
            }
            pad(out, indent).append(']');
        } else if (tag instanceof String string) {
            out.append(quote(string));
        } else if (tag instanceof Byte b) {
            out.append(b).append('b');
        } else if (tag instanceof Short s) {
            out.append(s).append('s');
        } else if (tag instanceof Integer i) {
            out.append(i);
        } else if (tag instanceof Long l) {
            out.append(l).append('L');
        } else if (tag instanceof Float f) {
            out.append(f).append('f');
        } else if (tag instanceof Double d) {
            out.append(d).append('d');
        } else if (tag instanceof byte[] bytes) {
            out.append("[B;");
            for (int i = 0; i < bytes.length; i++) out.append(i == 0 ? " " : ", ").append(bytes[i]).append('b');
            out.append(']');
        } else if (tag instanceof int[] ints) {
            out.append("[I;");
            for (int i = 0; i < ints.length; i++) out.append(i == 0 ? " " : ", ").append(ints[i]);
            out.append(']');
        } else if (tag instanceof long[] longs) {
            out.append("[L;");
            for (int i = 0; i < longs.length; i++) out.append(i == 0 ? " " : ", ").append(longs[i]).append('L');
            out.append(']');
        } else {
            out.append(tag);
        }
    }

    private static StringBuilder pad(StringBuilder out, int indent) {
        return out.append("  ".repeat(indent));
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }
}
