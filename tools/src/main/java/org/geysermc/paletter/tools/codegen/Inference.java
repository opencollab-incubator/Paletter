package org.geysermc.paletter.tools.codegen;

import org.geysermc.paletter.tools.model.infer.Wire;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.data.PaletteDataCodec;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.data.VanillaItem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Step one: unions the shapes of every component observed in wire NBT into the inferred spec.
 * <p>
 * Wire NBT is the bundled vanilla data and any capture in {@code codegen/captures}. Geyser samples
 * ({@code codegen/geyser-samples.json}) only shape components no wire NBT shows; for the others they are checked
 * against the wire shape and may only make fields optional (Geyser omits them in production).
 */
public final class Inference {
    static final String ITEM_PROPERTIES = "item_properties";
    static final String BLOCK_DEFINITION = "block_definition";

    private final Map<String, Shape> wire = new TreeMap<>();
    private final Map<String, TreeSet<String>> seenIn = new TreeMap<>();
    private final Map<String, Shape> geyserOnly = new TreeMap<>();
    private final Map<String, List<String>> geyserReports = new TreeMap<>();
    private final TreeSet<String> sources = new TreeSet<>();
    /**
     * Base key to the protocol versions at which its wire shape changed ({@code protocolSplits} in the overrides).
     */
    private final Map<String, List<Integer>> splits = new TreeMap<>();
    /**
     * The protocol version of the source being read, -1 if unknown.
     */
    private int protocol = -1;

    static JsonObject infer(Path root, List<Path> extraCaptures, JsonObject overrides) throws IOException {
        Inference inference = new Inference();
        if (overrides.has("components")) {
            overrides.getAsJsonObject("components").entrySet().forEach(entry -> {
                JsonObject component = entry.getValue().getAsJsonObject();
                if (component.has("protocolSplits")) {
                    List<Integer> points = new ArrayList<>();
                    component.getAsJsonArray("protocolSplits").forEach(point -> points.add(point.getAsInt()));
                    points.sort(null);
                    inference.splits.put(entry.getKey(), points);
                }
            });
        }
        Path data = root.resolve("paletter/src/main/resources/paletter");
        try (Stream<Path> files = Files.list(data)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".nbt")).sorted().toList()) {
                VanillaData vanilla;
                try (InputStream stream = Files.newInputStream(file)) {
                    vanilla = PaletteDataCodec.read(stream);
                }
                inference.protocol = vanilla.version().protocolVersion();
                inference.addVanilla("vanilla " + vanilla.version().name(), vanilla);
            }
        }
        List<Path> captureDirs = new ArrayList<>();
        captureDirs.add(root.resolve("codegen/captures"));
        captureDirs.addAll(extraCaptures);
        for (Path captures : captureDirs) {
            if (!Files.isDirectory(captures)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(captures)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".nbt") || f.toString().endsWith(".dat")).sorted().toList()) {
                    String name = "capture " + captures.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                    NbtMap capture = readNbt(file);
                    inference.protocol = captureProtocol(captures, file);
                    if (capture.containsKey("format") && capture.containsKey("items") && capture.containsKey("blocks")) {
                        // A whole palette in the bundled format
                        VanillaData palette;
                        try (InputStream stream = Files.newInputStream(file)) {
                            palette = PaletteDataCodec.read(stream);
                        }
                        inference.protocol = palette.version().protocolVersion();
                        inference.addVanilla(name, palette);
                    } else {
                        inference.addCapture(name, capture);
                    }
                }
            }
        }
        Path samples = root.resolve("codegen/geyser-samples.json");
        inference.addGeyserSamples(Files.exists(samples)
            ? JsonParser.parseString(Files.readString(samples, StandardCharsets.UTF_8)).getAsJsonObject()
            : new JsonObject());
        inference.geyserOnly.values().forEach(Shape::finish);
        return inference.toJson();
    }

    /**
     * The protocol version of a capture: the {@code protocol} of the nearest {@code capture.json} (the capture module's
     * summary, or a hand written {@code {"protocol": 2225}}) in the capture's directory or one of its parents below the
     * captures root. -1 if there is none, which only matters for components with {@code protocolSplits}: such
     * observations count for the newest shape. ({@code referencePalette} is not used: it names the bundled palette the
     * capture was compared with, not the protocol of the server.)
     */
    static int captureProtocol(Path capturesRoot, Path file) throws IOException {
        for (Path dir = file.getParent(); dir != null && dir.startsWith(capturesRoot); dir = dir.getParent()) {
            Path summary = dir.resolve("capture.json");
            if (Files.exists(summary)) {
                JsonObject json = JsonParser.parseString(Files.readString(summary, StandardCharsets.UTF_8)).getAsJsonObject();
                if (json.has("protocol")) {
                    return json.get("protocol").getAsInt();
                }
            }
        }
        return -1;
    }

    /**
     * @return the base key of a spec key: {@code item:minecraft:damage@..2250} is a shape of {@code item:minecraft:damage}
     */
    static String baseKey(String key) {
        int at = key.indexOf('@');
        return at < 0 ? key : key.substring(0, at);
    }

    /**
     * @return {since, until} of the protocol window of a spec key's shape; 0 and {@link Integer#MAX_VALUE} if unbounded
     */
    int[] window(String key) {
        List<Integer> points = splits.getOrDefault(baseKey(key), List.of());
        if (points.isEmpty()) {
            return new int[]{0, Integer.MAX_VALUE};
        }
        int at = key.indexOf('@');
        if (at < 0) {
            return new int[]{points.getLast(), Integer.MAX_VALUE};
        }
        String[] bounds = key.substring(at + 1).split("\\.\\.", -1);
        return new int[]{bounds[0].isEmpty() ? 0 : Integer.parseInt(bounds[0]), Integer.parseInt(bounds[1])};
    }

    /**
     * @return the spec key of the shape a source of the current protocol version observed: the base key for the newest
     * shape (and for components whose shape never changed), {@code <base>@<since>..<until>} for older ones
     */
    private String windowKey(String key) {
        List<Integer> points = splits.get(key);
        if (points == null || protocol < 0 || protocol >= points.getLast()) {
            return key;
        }
        int since = 0;
        for (int point : points) {
            if (protocol < point) {
                return key + "@" + (since == 0 ? "" : since) + ".." + point;
            }
            since = point;
        }
        return key;
    }

    /**
     * Reads big or little endian NBT, gzipped or not.
     */
    private static NbtMap readNbt(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > 1 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
            try (InputStream gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
                bytes = gzip.readAllBytes();
            }
        }
        try (NBTInputStream input = NbtUtils.createReader(new java.io.ByteArrayInputStream(bytes))) {
            return (NbtMap) input.readTag();
        } catch (Exception bigEndian) {
            try (NBTInputStream input = NbtUtils.createReaderLE(new java.io.ByteArrayInputStream(bytes))) {
                return (NbtMap) input.readTag();
            }
        }
    }

    private void addVanilla(String source, VanillaData data) {
        sources.add(source);
        for (VanillaItem item : data.items()) {
            if (item.componentBased() && item.componentData() != null) {
                addItem(source, item.componentData());
            }
        }
        for (BlockPropertyData block : data.dataDrivenBlocks()) {
            addBlock(source, block.getProperties());
        }
    }

    /**
     * A capture is a compound with an {@code items} list (item component entries {@code {name, components}}, as in the
     * item component packet) and a {@code blocks} list (block property data {@code {name, components, permutations}},
     * as in the start game packet). Both are optional.
     */
    private void addCapture(String source, NbtMap capture) {
        sources.add(source);
        if (capture.containsKey("components") && !capture.containsKey("items")) {
            // A single item component entry
            addItem(source, capture);
            return;
        }
        for (NbtMap item : capture.getList("items", NbtType.COMPOUND, List.of())) {
            // capture module layout: {name, id, componentBased, version, data: {components}}
            NbtMap data = item.containsKey("data") ? item.getCompound("data") : item;
            if (!data.isEmpty()) {
                addItem(source, data);
            }
        }
        for (NbtMap block : capture.getList("blocks", NbtType.COMPOUND, List.of())) {
            // capture module layout: {name, properties: {block property data}}
            addBlock(source, block.containsKey("properties") && block.get("properties") instanceof NbtMap properties
                ? properties : block);
        }
    }

    private void addItem(String source, NbtMap componentData) {
        NbtMap components = componentData.containsKey("components") ? componentData.getCompound("components") : componentData;
        components.forEach((name, value) -> {
            if (name.equals("item_tags")) {
                // A bare list next to the components; ItemComponents handles it by hand
                return;
            }
            if (value instanceof NbtMap) {
                observe("item:" + name, source, value);
            }
        });
    }

    private void addBlock(String source, NbtMap properties) {
        // The block property data around the components, typed as its own pseudo component
        Map<String, Object> definition = new TreeMap<>(properties);
        definition.remove("components");
        definition.remove("permutations");
        definition.remove("name");
        observe("block:" + BLOCK_DEFINITION, source, NbtMap.fromMap(definition));
        addBlockComponents(source, properties.getCompound("components", NbtMap.EMPTY));
        for (NbtMap permutation : properties.getList("permutations", NbtType.COMPOUND, List.of())) {
            addBlockComponents(source, permutation.getCompound("components", NbtMap.EMPTY));
        }
    }

    private void addBlockComponents(String source, NbtMap components) {
        components.forEach((name, value) -> {
            if (name.startsWith("tag:")) {
                // Legacy tag components: the name is the tag, the value always empty
                return;
            }
            if (value instanceof NbtMap) {
                observe("block:" + name, source, value);
            }
        });
    }

    private void observe(String baseKey, String source, Object value) {
        String key = windowKey(baseKey);
        wire.computeIfAbsent(key, k -> new Shape()).observe(value);
        seenIn.computeIfAbsent(key, k -> new TreeSet<>()).add(source.replaceFirst("^vanilla ", ""));
    }

    private void addGeyserSamples(JsonObject json) {
        wire.values().forEach(Shape::finish);
        if (!json.has("samples")) {
            return;
        }
        for (JsonElement element : json.getAsJsonArray("samples")) {
            JsonObject sample = element.getAsJsonObject();
            String key = sample.get("kind").getAsString() + ":" + sample.get("name").getAsString();
            String where = sample.get("source").getAsString();
            Object value = toNbt(sample.get("value"), key);
            Shape shape = wire.get(key);
            if (shape == null) {
                geyserOnly.computeIfAbsent(key, k -> new Shape()).observe(value);
                geyserReports.computeIfAbsent(key, k -> new ArrayList<>()).add(where + ": only evidence");
                continue;
            }
            String problem = shape.incompatibility(value, "");
            if (problem == null) {
                // Geyser omitting a field in production proves the client accepts the omission
                shape.relaxFor(value, "Geyser omits it (" + where + ")");
                geyserReports.computeIfAbsent(key, k -> new ArrayList<>()).add(where + ": compatible");
            } else {
                geyserReports.computeIfAbsent(key, k -> new ArrayList<>()).add(where + ": DIFFERS " + problem.replaceFirst("^\\.", ""));
            }
        }
    }

    private JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("comment", "Generated by :codegen:generateComponents from wire NBT; do not edit. See codegen/README.md.");
        JsonArray sourceArray = new JsonArray();
        sources.forEach(sourceArray::add);
        root.add("sources", sourceArray);
        JsonObject components = new JsonObject();
        TreeMap<String, Shape> all = new TreeMap<>(geyserOnly);
        all.putAll(wire);
        all.forEach((key, shape) -> {
            JsonObject component = new JsonObject();
            boolean fromWire = wire.containsKey(key);
            component.addProperty("confidence", fromWire ? "high" : "medium");
            int[] window = window(key);
            if (window[0] != 0) {
                component.addProperty("sinceProtocol", window[0]);
            }
            if (window[1] != Integer.MAX_VALUE) {
                component.addProperty("untilProtocol", window[1]);
            }
            JsonArray seen = new JsonArray();
            if (fromWire) {
                seenIn.get(key).forEach(seen::add);
            } else {
                seen.add("geyser");
            }
            component.add("seenIn", seen);
            List<String> reports = geyserReports.get(key);
            if (reports != null) {
                JsonArray geyser = new JsonArray();
                reports.forEach(geyser::add);
                component.add("geyser", geyser);
            }
            component.add("shape", shape.toJson());
            components.add(key, component);
        });
        root.add("components", components);
        return root;
    }

    /**
     * Converts the typed JSON notation of the Geyser samples, see codegen/README.md.
     */
    @SuppressWarnings("unchecked")
    static Object toNbt(JsonElement json, String context) {
        if (json.isJsonObject()) {
            JsonObject object = json.getAsJsonObject();
            if (object.has("@int_array")) {
                JsonArray array = object.getAsJsonArray("@int_array");
                int[] ints = new int[array.size()];
                for (int i = 0; i < ints.length; i++) {
                    ints[i] = array.get(i).getAsInt();
                }
                return ints;
            }
            if (object.has("@list")) {
                NbtType<?> type = listType(object.get("@list").getAsString());
                List<Object> values = new ArrayList<>();
                if (object.has("values")) {
                    for (JsonElement value : object.getAsJsonArray("values")) {
                        values.add(toNbt(value, context));
                    }
                }
                return newList(type, values);
            }
            NbtMapBuilder builder = NbtMap.builder();
            object.entrySet().forEach(entry -> builder.put(entry.getKey(), toNbt(entry.getValue(), context)));
            return builder.build();
        }
        if (json.isJsonArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonElement value : json.getAsJsonArray()) {
                values.add(toNbt(value, context));
            }
            if (values.isEmpty()) {
                throw new IllegalArgumentException(context + ": write empty lists as {\"@list\": \"STRING\"}");
            }
            Object first = values.get(0);
            return newList(first instanceof NbtMap ? NbtType.COMPOUND : NbtType.byClass((Class<Object>) first.getClass()), values);
        }
        JsonPrimitive primitive = json.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return (byte) (primitive.getAsBoolean() ? 1 : 0);
        }
        if (primitive.isNumber()) {
            throw new IllegalArgumentException(context + ": numbers need a type prefix, e.g. \"i:" + primitive + "\"");
        }
        String string = primitive.getAsString();
        int colon = string.indexOf(':');
        String prefix = colon < 0 ? "" : string.substring(0, colon);
        String rest = colon < 0 ? string : string.substring(colon + 1);
        return switch (prefix) {
            case "b" -> Byte.parseByte(rest);
            case "s" -> Short.parseShort(rest);
            case "i" -> Integer.parseInt(rest);
            case "l" -> Long.parseLong(rest);
            case "f" -> Float.parseFloat(rest);
            case "d" -> Double.parseDouble(rest);
            case "str" -> rest;
            default -> string;
        };
    }

    private static NbtType<?> listType(String name) {
        return switch (name) {
            case "BYTE" -> NbtType.BYTE;
            case "SHORT" -> NbtType.SHORT;
            case "INT" -> NbtType.INT;
            case "LONG" -> NbtType.LONG;
            case "FLOAT" -> NbtType.FLOAT;
            case "DOUBLE" -> NbtType.DOUBLE;
            case "STRING" -> NbtType.STRING;
            case "COMPOUND" -> NbtType.COMPOUND;
            case "LIST" -> NbtType.LIST;
            default -> throw new IllegalArgumentException("Unknown list type " + name);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static NbtList<?> newList(NbtType type, List<Object> values) {
        return new NbtList(type, values);
    }
}
