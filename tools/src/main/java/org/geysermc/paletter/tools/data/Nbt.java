package org.geysermc.paletter.tools.data;

import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The one place the tooling reads and writes NBT: files (gzip detected, either byte order), little endian network payloads
 * (also as base64 inside JSON), and the indented, typed SNBT text of captures
 * ({@code 1b}, {@code 2s}, {@code 3}, {@code 4L}, {@code 5.0f}, {@code 6.0d}) in wire order, so a capture can be read and
 * diffed as text.
 */
public final class Nbt {
    private Nbt() {
    }

    /** Reads a file of big or little endian NBT, gzipped or not. */
    public static NbtMap read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > 1 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
            try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                bytes = gzip.readAllBytes();
            }
        }
        try (NBTInputStream input = NbtUtils.createReader(new ByteArrayInputStream(bytes))) {
            return (NbtMap) input.readTag();
        } catch (Exception bigEndian) {
            try (NBTInputStream input = NbtUtils.createReaderLE(new ByteArrayInputStream(bytes))) {
                return (NbtMap) input.readTag();
            }
        }
    }

    /** Reads a gzipped big endian NBT file (the bundled and captured format). */
    public static NbtMap readGzip(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file); NBTInputStream input = NbtUtils.createGZIPReader(stream)) {
            return (NbtMap) input.readTag();
        }
    }

    /** Reads little endian network NBT from {@code length} bytes at {@code offset}. */
    public static NbtMap readLe(byte[] bytes, int offset, int length) throws IOException {
        try (NBTInputStream input = NbtUtils.createReaderLE(new ByteArrayInputStream(bytes, offset, length))) {
            return (NbtMap) input.readTag();
        }
    }

    /** Reads little endian network NBT from base64 (the {@code *_b64} fields of captures). */
    public static NbtMap readLeBase64(String base64) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(base64);
        return readLe(bytes, 0, bytes.length);
    }

    /** Little endian network NBT bytes. */
    public static byte[] writeLe(NbtMap tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (NBTOutputStream output = NbtUtils.createWriterLE(bytes)) {
            output.writeTag(tag);
        }
        return bytes.toByteArray();
    }

    public static String writeLeBase64(NbtMap tag) throws IOException {
        return Base64.getEncoder().encodeToString(writeLe(tag));
    }

    public static void writeGzip(Path file, NbtMap tag) throws IOException {
        try (OutputStream stream = Files.newOutputStream(file); NBTOutputStream output = NbtUtils.createGZIPWriter(stream)) {
            output.writeTag(tag);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // SNBT

    public static String snbt(Object tag) {
        StringBuilder out = new StringBuilder();
        snbt(out, tag, 0);
        return out.toString();
    }

    private static void snbt(StringBuilder out, Object tag, int indent) {
        if (tag instanceof NbtMap map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                pad(out, indent + 1).append(quote(entry.getKey())).append(": ");
                snbt(out, entry.getValue(), indent + 1);
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
                    snbt(out, list.get(i), indent);
                }
                out.append(']');
                return;
            }
            out.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                pad(out, indent + 1);
                snbt(out, list.get(i), indent + 1);
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
