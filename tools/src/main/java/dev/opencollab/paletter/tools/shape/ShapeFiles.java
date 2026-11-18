package dev.opencollab.paletter.tools.shape;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The voxel shape definitions of BDS's own behavior packs: {@code <pack>/__brarchive/shapes.brarchive} (the vanilla
 * pack ships its {@code shapes/} directory as one archive of compiled files) and plain {@code <pack>/shapes/*.json}.
 * <p>
 * A {@code .brarchive} is: 8 magic bytes, entry count and version (little endian int32), then per entry 256 bytes (a
 * length byte, the name padded to 247 bytes, offset and length of its content as int32), then the contents. A compiled
 * shape file starts with {@code 7F 'M' 'C' 'B'}, the format version as int16, int16, int32 ({@code 1.21.110}), the schema
 * name {@code minecraft:voxel_shape}, the shape identifier (both with a length byte), the number of boxes as a byte and
 * six little endian floats per box: min x, y, z, max x, y, z in pixels.
 */
public final class ShapeFiles {
    private static final byte[] COMPILED = {0x7f, 'M', 'C', 'B'};
    private static final String SCHEMA = "minecraft:voxel_shape";

    private ShapeFiles() {
    }

    /**
     * The shapes of every behavior pack of a server that has some: pack directory name to shape identifier to boxes,
     * both sorted. A pack that has the archive and the directory (BDS 1.26.4x ships both) must say the same in both.
     */
    public static Map<String, Map<String, List<ShapeAddon.Box>>> ofServer(Path server) throws IOException {
        Map<String, Map<String, List<ShapeAddon.Box>>> packs = new TreeMap<>();
        List<Path> directories;
        try (Stream<Path> list = Files.list(server.resolve("behavior_packs"))) {
            directories = list.sorted().toList();
        }
        for (Path pack : directories) {
            Map<String, List<ShapeAddon.Box>> archived = new TreeMap<>();
            Path archive = pack.resolve("__brarchive").resolve("shapes.brarchive");
            if (Files.exists(archive)) {
                for (byte[] file : archive(Files.readAllBytes(archive))) {
                    put(archived, file, archive);
                }
            }
            Map<String, List<ShapeAddon.Box>> plain = new TreeMap<>();
            Path directory = pack.resolve("shapes");
            if (Files.isDirectory(directory)) {
                try (Stream<Path> files = Files.walk(directory)) {
                    for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                        put(plain, Files.readAllBytes(file), file);
                    }
                }
            }
            if (!archived.isEmpty() && !plain.isEmpty() && !archived.equals(plain)) {
                throw new IOException("The shapes of " + archive + " and of " + directory + " differ");
            }
            if (!archived.isEmpty() || !plain.isEmpty()) {
                packs.put(pack.getFileName().toString(), archived.isEmpty() ? plain : archived);
            }
        }
        return packs;
    }

    private static void put(Map<String, List<ShapeAddon.Box>> shapes, byte[] file, Path source) throws IOException {
        Map.Entry<String, List<ShapeAddon.Box>> shape = read(file);
        if (shapes.put(shape.getKey(), shape.getValue()) != null) {
            throw new IOException("Two definitions of the voxel shape " + shape.getKey() + " in " + source);
        }
    }

    /** The contents of the files of a {@code .brarchive}. */
    static List<byte[]> archive(byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int count = buffer.getInt(8);
        int version = buffer.getInt(12);
        if (version != 1) {
            throw new IOException("Unknown .brarchive version " + version);
        }
        int contents = 16 + count * 256;
        List<byte[]> files = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int entry = 16 + i * 256;
            int offset = buffer.getInt(entry + 248);
            int length = buffer.getInt(entry + 252);
            byte[] file = new byte[length];
            buffer.get(contents + offset, file);
            files.add(file);
        }
        return files;
    }

    /** One shape file, compiled or JSON: its identifier and boxes. */
    static Map.Entry<String, List<ShapeAddon.Box>> read(byte[] file) throws IOException {
        List<ShapeAddon.Box> boxes = new ArrayList<>();
        if (file.length < 4 || !java.util.Arrays.equals(file, 0, 4, COMPILED, 0, 4)) {
            JsonObject shape = JsonParser.parseString(new String(file, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject(SCHEMA);
            for (JsonElement element : shape.getAsJsonObject("shape").getAsJsonArray("boxes")) {
                var min = element.getAsJsonObject().getAsJsonArray("min");
                var max = element.getAsJsonObject().getAsJsonArray("max");
                boxes.add(new ShapeAddon.Box(min.get(0).getAsFloat(), min.get(1).getAsFloat(), min.get(2).getAsFloat(),
                    max.get(0).getAsFloat(), max.get(1).getAsFloat(), max.get(2).getAsFloat()));
            }
            return Map.entry(shape.getAsJsonObject("description").get("identifier").getAsString(), boxes);
        }
        ByteBuffer buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).position(12);
        String schema = string(buffer);
        if (!schema.equals(SCHEMA)) {
            throw new IOException("A compiled " + schema + " file among the voxel shapes");
        }
        String identifier = string(buffer);
        int count = Byte.toUnsignedInt(buffer.get());
        for (int i = 0; i < count; i++) {
            boxes.add(new ShapeAddon.Box(buffer.getFloat(), buffer.getFloat(), buffer.getFloat(),
                buffer.getFloat(), buffer.getFloat(), buffer.getFloat()));
        }
        if (buffer.hasRemaining()) {
            throw new IOException(buffer.remaining() + " bytes left after the " + count + " boxes of " + identifier);
        }
        return Map.entry(identifier, boxes);
    }

    private static String string(ByteBuffer buffer) {
        byte[] bytes = new byte[Byte.toUnsignedInt(buffer.get())];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
