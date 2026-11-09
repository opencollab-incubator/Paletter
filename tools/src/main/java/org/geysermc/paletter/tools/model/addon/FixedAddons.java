package org.geysermc.paletter.tools.model.addon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * The hand-written capture add-ons, as modes of the add-on generator: the capture add-on (every item and block component,
 * for wire captures; definitions in {@code addons/capture.json}), the creative order probe ({@code addons/creative.json})
 * and the large creative order probe (700 random blocks from a fixed seed). They replace the Python scripts that used to
 * write {@code data/addons/capture} and {@code data/addons/creative}; the committed packs are their output.
 */
public final class FixedAddons {
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** The format version of the committed packs; a build older than it gets packs of its own version ({@code writeCapture(out, formatVersion)}). */
    public static final String FORMAT_VERSION = "1.26.60";
    private static final String NAMESPACE = "paletter";
    private static final String GEOMETRY = "geometry.paletter_cube";

    private FixedAddons() {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The capture add-on

    /** Writes {@code behavior_pack} and {@code resource_pack} of the capture add-on into {@code out}. */
    public static void writeCapture(Path out) throws IOException {
        writeCapture(out, FORMAT_VERSION);
    }

    /**
     * @param formatVersion the format version of the items and blocks and the minimum engine version of the packs: BDS
     *                      refuses packs of a newer engine, so a run on an older build passes
     *                      {@link AddonGenerator#formatVersionFor}
     */
    public static void writeCapture(Path out, String formatVersion) throws IOException {
        Path bp = out.resolve("behavior_pack");
        Path rp = out.resolve("resource_pack");
        delete(bp);
        delete(rp);
        JsonObject definitions = resource("capture.json");
        for (JsonElement element : definitions.getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            String name = item.get("name").getAsString();
            JsonObject description = new JsonObject();
            description.addProperty("identifier", NAMESPACE + ":" + name);
            if (item.has("description")) {
                item.getAsJsonObject("description").entrySet().forEach(e -> description.add(e.getKey(), e.getValue()));
            }
            if (!description.has("menu_category")) {
                description.add("menu_category", object("category", "items"));
            }
            JsonObject body = new JsonObject();
            body.add("description", description);
            body.add("components", item.get("components"));
            write(bp.resolve("items/" + name + ".json"), versioned(formatVersion, "minecraft:item", body));
        }
        for (JsonElement element : definitions.getAsJsonArray("blocks")) {
            JsonObject block = element.getAsJsonObject();
            if (block.has("skip")) {
                continue;
            }
            String name = block.get("name").getAsString();
            JsonObject extra = block.has("description") ? block.getAsJsonObject("description") : new JsonObject();
            JsonObject description = new JsonObject();
            description.addProperty("identifier", NAMESPACE + ":" + name);
            description.add("menu_category", extra.has("menu_category") ? extra.get("menu_category") : object("category", "construction"));
            for (String key : List.of("states", "traits")) {
                if (extra.has(key)) {
                    description.add(key, extra.get(key));
                }
            }
            JsonObject body = new JsonObject();
            body.add("description", description);
            body.add("components", block.get("components"));
            if (extra.has("permutations")) {
                body.add("permutations", extra.get("permutations"));
            }
            write(bp.resolve("blocks/" + name + ".json"), versioned(formatVersion, "minecraft:block", body));
        }
        write(bp.resolve("loot_tables/paletter_empty.json"), json("{\"pools\": []}"));
        write(bp.resolve("manifest.json"), manifest("Paletter capture", "Custom items and blocks with every component, for wire captures",
            "a1e5c0de-0000-4000-8000-00000000b001", "a1e5c0de-0000-4000-8000-00000000b002", "data",
            "a1e5c0de-0000-4000-8000-00000000c001", formatVersion));

        write(rp.resolve("manifest.json"), json("""
            {"format_version": 2,
             "header": {"name": "Paletter capture resources", "description": "Textures and geometry for the capture pack",
                        "uuid": "a1e5c0de-0000-4000-8000-00000000c001", "version": [1, 0, 0], "min_engine_version": %s},
             "modules": [{"type": "resources", "uuid": "a1e5c0de-0000-4000-8000-00000000c002", "version": [1, 0, 0]}]}""".formatted(
            AddonGenerator.minEngine(formatVersion))));
        write(rp.resolve("textures/terrain_texture.json"), json("""
            {"resource_pack_name": "paletter", "texture_name": "atlas.terrain", "padding": 8, "num_mip_levels": 4,
             "texture_data": {"paletter_tex": {"textures": "textures/blocks/paletter_tex"},
                              "paletter_tex_alt": {"textures": "textures/blocks/paletter_tex_alt"}}}"""));
        write(rp.resolve("textures/item_texture.json"), json("""
            {"resource_pack_name": "paletter", "texture_name": "atlas.items",
             "texture_data": {"paletter_icon": {"textures": "textures/items/paletter_icon"},
                              "paletter_icon_alt": {"textures": "textures/items/paletter_icon_alt"}}}"""));
        png(rp.resolve("textures/blocks/paletter_tex.png"), 200, 60, 60, 255);
        png(rp.resolve("textures/blocks/paletter_tex_alt.png"), 60, 200, 60, 255);
        png(rp.resolve("textures/items/paletter_icon.png"), 60, 60, 200, 255);
        png(rp.resolve("textures/items/paletter_icon_alt.png"), 200, 200, 60, 255);
        write(rp.resolve("models/blocks/paletter_cube.geo.json"), json("""
            {"format_version": "1.21.0",
             "minecraft:geometry": [{"description": {"identifier": "%s", "texture_width": 16, "texture_height": 16},
               "bones": [{"name": "bone_a", "pivot": [0, 0, 0], "cubes": [{"origin": [-8, 0, -8], "size": [16, 8, 16], "uv": [0, 0]}]},
                         {"name": "bone_b", "pivot": [0, 8, 0], "cubes": [{"origin": [-4, 8, -4], "size": [8, 8, 8], "uv": [0, 0]}]}]}]}"""
            .formatted(GEOMETRY)));
        write(rp.resolve("block_culling/paletter_culling.json"), json("""
            {"format_version": "1.21.80",
             "minecraft:block_culling_rules": {"description": {"identifier": "paletter:culling"},
               "rules": [{"geometry_part": {"bone": "bone_a", "cube": 0, "face": "down"}, "direction": "down"}]}}"""));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The creative order probe

    /**
     * Writes the creative order probe add-on ({@code behavior_pack} only) into {@code out}: custom items and blocks with every
     * kind of menu_category placement, to see where BDS puts them in the creative inventory (data/bds/CREATIVE-ORDER.md).
     * Item file names are deliberately unrelated to the identifiers; BDS numbers custom blocks in descending file name order
     * and Paletter (no files) by descending identifier, so the block files are named b&lt;rank in identifier order&gt;.
     */
    public static void writeCreative(Path out) throws IOException {
        writeCreative(out, FORMAT_VERSION);
    }

    /** @param formatVersion see {@link #writeCapture(Path, String)} */
    public static void writeCreative(Path out, String formatVersion) throws IOException {
        Path bp = out.resolve("behavior_pack");
        delete(bp);
        JsonObject definitions = resource("creative.json");
        for (JsonElement element : definitions.getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            JsonObject description = new JsonObject();
            description.add("identifier", item.get("identifier"));
            if (item.has("menu_category")) {
                description.add("menu_category", item.get("menu_category"));
            }
            JsonObject components = item.getAsJsonObject("components");
            // an item needs at least one component, or BDS registers it without its description
            if (components.isEmpty()) {
                components = object("minecraft:max_stack_size", 64);
            }
            JsonObject body = new JsonObject();
            body.add("description", description);
            body.add("components", components);
            write(bp.resolve("items/" + item.get("file").getAsString() + ".json"), versioned(formatVersion, "minecraft:item", body));
        }
        List<JsonObject> blocks = new ArrayList<>();
        definitions.getAsJsonArray("blocks").forEach(b -> blocks.add(b.getAsJsonObject()));
        blocks.sort(Comparator.comparing((JsonObject b) -> b.get("identifier").getAsString()));
        for (int rank = 0; rank < blocks.size(); rank++) {
            JsonObject block = blocks.get(rank);
            JsonObject description = new JsonObject();
            description.add("identifier", block.get("identifier"));
            if (block.has("menu_category")) {
                description.add("menu_category", block.get("menu_category"));
            }
            JsonObject body = new JsonObject();
            body.add("description", description);
            body.add("components", new JsonObject());
            write(bp.resolve("blocks/" + String.format("b%02d", rank + 1) + ".json"), versioned(formatVersion, "minecraft:block", body));
        }
        write(bp.resolve("manifest.json"), manifest("Paletter creative order probe", "Custom items and blocks with every menu_category placement",
            "a1e5c0de-0000-4000-8000-00000000d001", "a1e5c0de-0000-4000-8000-00000000d002", "data", null, formatVersion));
    }

    /**
     * Writes the large creative order probe into {@code out/behavior_pack}: 700 blocks and 40 items with random identifiers and
     * menu categories (fixed seed 7, reproducing Python's {@code random}; block files named by identifier rank, items randomly),
     * so the data driven block map of BDS grows past 512 buckets. Captured in data/bds/1.26.63.1/creative-large.
     */
    public static void writeCreativeLarge(Path out) throws IOException {
        Path bp = out.resolve("behavior_pack");
        delete(bp);
        PythonRandom random = new PythonRandom(7);
        String[] spaces = {"aaa", "exp", "qq", "zzz"};
        String[] names = new String[700];
        for (int i = 0; i < names.length; i++) {
            names[i] = spaces[random.below(spaces.length)] + ":b" + random.randint(0, 1_000_000) + "_" + i;
        }
        // Unused (block files are named by identifier rank) but kept so the random sequence stays the same
        int[] files = new int[700];
        for (int i = 0; i < files.length; i++) {
            files[i] = i;
        }
        random.shuffle(files);
        List<String> sorted = new ArrayList<>(List.of(names));
        sorted.sort(null);
        Map<String, Integer> rank = new TreeMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            rank.put(sorted.get(i), i);
        }
        String[] categories = {"construction", "nature", "equipment", "items"};
        for (String name : names) {
            JsonObject menu = object("category", categories[random.below(categories.length)]);
            double r = random.random();
            if (r < 0.2) {
                menu.addProperty("group", "minecraft:itemGroup.name.sword");
            } else if (r < 0.4) {
                menu.addProperty("group", "big:itemGroup.g" + random.randint(0, 5));
            }
            JsonObject description = new JsonObject();
            description.addProperty("identifier", name);
            description.add("menu_category", menu);
            JsonObject body = new JsonObject();
            body.add("description", description);
            body.add("components", new JsonObject());
            writeCompact(bp.resolve(String.format("blocks/x%04d.json", rank.get(name))), versioned(FORMAT_VERSION, "minecraft:block", body));
        }
        for (int i = 0; i < 40; i++) {
            JsonObject menu = object("category", categories[random.below(categories.length)]);
            if (random.random() < 0.5) {
                menu.addProperty("group", "big:itemGroup.g" + random.randint(0, 5));
            }
            JsonObject description = new JsonObject();
            description.addProperty("identifier", "big:i" + random.randint(0, 999) + "_" + i);
            description.add("menu_category", menu);
            JsonObject body = new JsonObject();
            body.add("description", description);
            body.add("components", object("minecraft:max_stack_size", 64));
            writeCompact(bp.resolve(String.format("items/y%05d_%d.json", random.randint(0, 99999), i)),
                versioned(FORMAT_VERSION, "minecraft:item", body));
        }
        writeCompact(bp.resolve("manifest.json"), json("""
            {"format_version": 2, "header": {"name": "big", "description": "big", "uuid": "a1e5c0de-0000-4000-8000-00000000e001",
                                              "version": [1, 0, 0], "min_engine_version": [1, 26, 60]},
             "modules": [{"type": "data", "uuid": "a1e5c0de-0000-4000-8000-00000000e002", "version": [1, 0, 0]}]}"""));
    }

    /** The identifiers of the large probe, for checking the generator against a capture of the pack. */
    public static List<String> largeIdentifiers(Path directory) throws IOException {
        List<String> identifiers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json") && !f.getFileName().toString().equals("manifest.json")).toList()) {
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                JsonObject body = root.has("minecraft:block") ? root.getAsJsonObject("minecraft:block") : root.getAsJsonObject("minecraft:item");
                identifiers.add(body.getAsJsonObject("description").get("identifier").getAsString());
            }
        }
        identifiers.sort(null);
        return identifiers;
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static JsonObject manifest(String name, String description, String uuid, String module, String type, String dependency,
                                       String formatVersion) {
        JsonObject header = new JsonObject();
        header.addProperty("name", name);
        header.addProperty("description", description);
        header.addProperty("uuid", uuid);
        header.add("version", json("[1, 0, 0]"));
        header.add("min_engine_version", json(AddonGenerator.minEngine(formatVersion)));
        JsonObject moduleObject = new JsonObject();
        moduleObject.addProperty("type", type);
        moduleObject.addProperty("uuid", module);
        moduleObject.add("version", json("[1, 0, 0]"));
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format_version", 2);
        manifest.add("header", header);
        manifest.add("modules", array(moduleObject));
        if (dependency != null) {
            JsonObject dep = new JsonObject();
            dep.addProperty("uuid", dependency);
            dep.add("version", json("[1, 0, 0]"));
            manifest.add("dependencies", array(dep));
        }
        return manifest;
    }

    private static JsonObject versioned(String formatVersion, String key, JsonObject body) {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", formatVersion);
        root.add(key, body);
        return root;
    }

    private static JsonObject resource(String name) throws IOException {
        try (InputStream stream = FixedAddons.class.getResourceAsStream("/addons/" + name)) {
            if (stream == null) {
                throw new IOException("Missing resource /addons/" + name);
            }
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static JsonObject object(String key, Object value) {
        JsonObject object = new JsonObject();
        if (value instanceof Number number) {
            object.addProperty(key, number);
        } else {
            object.addProperty(key, value.toString());
        }
        return object;
    }

    private static JsonArray array(JsonElement element) {
        JsonArray array = new JsonArray();
        array.add(element);
        return array;
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static void delete(Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            try (Stream<Path> walk = Files.walk(directory)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    /** Indented like Python's {@code json.dump(indent=2)}, LF line ends, a final newline. */
    private static void write(Path path, JsonElement data) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, PRETTY.toJson(data) + "\n", StandardCharsets.UTF_8);
    }

    /** Like Python's {@code json.dump} without indent: {@code ", "} and {@code ": "} separators, no final newline. */
    private static void writeCompact(Path path, JsonElement data) throws IOException {
        Files.createDirectories(path.getParent());
        StringBuilder out = new StringBuilder();
        compact(out, data);
        Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
    }

    private static void compact(StringBuilder out, JsonElement element) {
        if (element instanceof JsonObject object) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                out.append(first ? "" : ", ").append(new Gson().toJson(entry.getKey())).append(": ");
                compact(out, entry.getValue());
                first = false;
            }
            out.append('}');
        } else if (element instanceof JsonArray array) {
            out.append('[');
            for (int i = 0; i < array.size(); i++) {
                out.append(i == 0 ? "" : ", ");
                compact(out, array.get(i));
            }
            out.append(']');
        } else {
            out.append(new GsonBuilder().disableHtmlEscaping().create().toJson(element));
        }
    }

    /** A 16x16 RGBA PNG of one colour, as the Python script wrote it. */
    private static void png(Path path, int r, int g, int b, int a) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int row = 0; row < 16; row++) {
            raw.write(0);
            for (int column = 0; column < 16; column++) {
                raw.write(r);
                raw.write(g);
                raw.write(b);
                raw.write(a);
            }
        }
        Deflater deflater = new Deflater(6);
        deflater.setInput(raw.toByteArray());
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream headerData = new DataOutputStream(header);
        headerData.writeInt(16);
        headerData.writeInt(16);
        headerData.write(new byte[]{8, 6, 0, 0, 0});
        chunk(png, "IHDR", header.toByteArray());
        chunk(png, "IDAT", compressed.toByteArray());
        chunk(png, "IEND", new byte[0]);
        Files.createDirectories(path.getParent());
        Files.write(path, png.toByteArray());
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        DataOutputStream stream = new DataOutputStream(out);
        stream.writeInt(data.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        stream.write(typeBytes);
        stream.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        stream.writeInt((int) crc.getValue());
    }

    /** Python's Mersenne Twister ({@code random.seed(int)}) and the {@code random} methods the probes use. */
    static final class PythonRandom {
        private final int[] mt = new int[624];
        private int index;

        PythonRandom(int seed) {
            // init_genrand(19650218), then init_by_array([seed])
            mt[0] = 19650218;
            for (int i = 1; i < 624; i++) {
                mt[i] = 1812433253 * (mt[i - 1] ^ (mt[i - 1] >>> 30)) + i;
            }
            int[] key = {seed};
            int i = 1;
            int j = 0;
            for (int k = Math.max(624, key.length); k > 0; k--) {
                mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1664525)) + key[j] + j;
                i++;
                j++;
                if (i >= 624) {
                    mt[0] = mt[623];
                    i = 1;
                }
                if (j >= key.length) {
                    j = 0;
                }
            }
            for (int k = 623; k > 0; k--) {
                mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1566083941)) - i;
                i++;
                if (i >= 624) {
                    mt[0] = mt[623];
                    i = 1;
                }
            }
            mt[0] = 0x80000000;
            index = 624;
        }

        private int next32() {
            if (index >= 624) {
                for (int k = 0; k < 624; k++) {
                    int y = (mt[k] & 0x80000000) | (mt[(k + 1) % 624] & 0x7fffffff);
                    int value = mt[(k + 397) % 624] ^ (y >>> 1);
                    mt[k] = (y & 1) != 0 ? value ^ 0x9908b0df : value;
                }
                index = 0;
            }
            int y = mt[index++];
            y ^= y >>> 11;
            y ^= (y << 7) & 0x9d2c5680;
            y ^= (y << 15) & 0xefc60000;
            y ^= y >>> 18;
            return y;
        }

        /** {@code _randbelow}: a uniform integer in [0, n) by rejection on {@code getrandbits(n.bit_length())}. */
        int below(int n) {
            int bits = 32 - Integer.numberOfLeadingZeros(n);
            int r = next32() >>> (32 - bits);
            while (r >= n) {
                r = next32() >>> (32 - bits);
            }
            return r;
        }

        int randint(int low, int high) {
            return low + below(high - low + 1);
        }

        double random() {
            long a = (next32() & 0xffffffffL) >>> 5;
            long b = (next32() & 0xffffffffL) >>> 6;
            return (a * 67108864.0 + b) / 9007199254740992.0;
        }

        void shuffle(int[] values) {
            for (int i = values.length - 1; i >= 1; i--) {
                int j = below(i + 1);
                int tmp = values[i];
                values[i] = values[j];
                values[j] = tmp;
            }
        }
    }
}
