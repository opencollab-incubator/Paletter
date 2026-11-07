package org.geysermc.paletter.spec.infer;

import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/** Wire NBT flattened to leaves: {@code a.b[0].c -> (type, value)}; empty compounds and lists are leaves too. */
final class Wire {
    record Leaf(String type, Object value) {
        boolean numeric() {
            return value instanceof Number;
        }

        double number() {
            return ((Number) value).doubleValue();
        }

        @Override
        public String toString() {
            return value + " (" + type + ")";
        }
    }

    private Wire() {
    }

    static NbtMap read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > 1 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
            try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                bytes = gzip.readAllBytes();
            }
        }
        try (NBTInputStream input = NbtUtils.createReader(new ByteArrayInputStream(bytes))) {
            return (NbtMap) input.readTag();
        }
    }

    static Map<String, Leaf> flatten(NbtMap root) {
        Map<String, Leaf> leaves = new TreeMap<>();
        flatten("", root, leaves);
        return leaves;
    }

    private static void flatten(String path, Object value, Map<String, Leaf> leaves) {
        if (value instanceof NbtMap map) {
            if (map.isEmpty() && !path.isEmpty()) {
                leaves.put(path, new Leaf("compound", "{}"));
            }
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                flatten(path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(), entry.getValue(), leaves);
            }
        } else if (value instanceof NbtList<?> list) {
            if (list.isEmpty()) {
                leaves.put(path, new Leaf("list<" + typeName(list.getType()) + ">", "[]"));
            }
            for (int i = 0; i < list.size(); i++) {
                flatten(path + "[" + i + "]", list.get(i), leaves);
            }
        } else if (value instanceof int[] ints) {
            leaves.put(path, new Leaf("int_array", java.util.Arrays.toString(ints)));
        } else if (value instanceof byte[] bytes) {
            leaves.put(path, new Leaf("byte_array", java.util.Arrays.toString(bytes)));
        } else {
            leaves.put(path, new Leaf(typeOf(value), value));
        }
    }

    static String typeOf(Object value) {
        return switch (value) {
            case Byte b -> "byte";
            case Short s -> "short";
            case Integer i -> "int";
            case Long l -> "long";
            case Float f -> "float";
            case Double d -> "double";
            case String s -> "string";
            default -> value.getClass().getSimpleName();
        };
    }

    static String typeName(NbtType<?> type) {
        return type.getTagClass().getSimpleName().replace("NbtMap", "compound").toLowerCase(java.util.Locale.ROOT);
    }

    /** Leaves that differ between two views: path -> [before, after] (null = absent). */
    static Map<String, Leaf[]> diff(Map<String, Leaf> before, Map<String, Leaf> after) {
        Map<String, Leaf[]> diff = new LinkedHashMap<>();
        java.util.TreeSet<String> keys = new java.util.TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            Leaf a = before.get(key);
            Leaf b = after.get(key);
            if (a == null || b == null || !a.equals(b)) {
                diff.put(key, new Leaf[]{a, b});
            }
        }
        return diff;
    }

    /** {@code a[0].b[12]} -> {@code a[].b[]}. */
    static String generic(String path) {
        return path.replaceAll("\\[\\d+]", "[]");
    }

    static List<String> segments(String path) {
        return List.of(path.split("\\.(?![^\\[]*])"));
    }
}
