package org.geysermc.paletter.tools.codegen.constants;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.internal.creative.RawLayout;
import org.geysermc.paletter.internal.creative.RawNode;
import org.geysermc.paletter.internal.creative.RawStack;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.data.PaletteDataCodec;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.data.VanillaItem;
import org.geysermc.paletter.internal.data.VersionFallbacks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The constants generator on a small synthetic bundle, compared byte for byte with the committed expected sources in
 * {@code src/test/resources/constants-golden}. The fixture has bool, int (range and list) and string states, a value set
 * shared by two states, a name collision settled by names.json, a block that needs a lookup table, a removed block, a
 * renamed block, a removed item with a curated fallback, and a preview-only block and item.
 * <p>
 * After an intended change of the output: {@code ./gradlew :tools:test -PupdateGolden=true}, then review the diff.
 */
class ConstantsGoldenTest {
    private static final Path GOLDEN = Path.of("src/test/resources/constants-golden");
    private static final String PREFIX = ConstantsGenerator.PACKAGE_DIR + "/";

    // ---- Fixture -------------------------------------------------------------------------------------------------

    private static NbtMap state(String name, Object... keyValues) {
        Map<String, Object> states = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            states.put((String) keyValues[i], keyValues[i + 1]);
        }
        return BlockStates.of(name, states);
    }

    /** Blocks of the release, each a list of states in palette order. */
    private static List<List<NbtMap>> releaseBlocks(boolean withLamp) {
        List<List<NbtMap>> blocks = new ArrayList<>();
        blocks.add(List.of(state("minecraft:air")));
        blocks.add(List.of(state("minecraft:unknown")));
        // bool + string, cardinal direction varying fastest
        List<NbtMap> door = new ArrayList<>();
        for (byte open = 0; open <= 1; open++) {
            for (String direction : List.of("south", "west", "north", "east")) {
                door.add(state("minecraft:test_door", "minecraft:cardinal_direction", direction, "open_bit", open));
            }
        }
        blocks.add(door);
        // Two states with the same values: one shared enum, named in names.json
        List<NbtMap> wall = new ArrayList<>();
        for (String east : List.of("none", "short", "tall")) {
            for (String west : List.of("none", "short", "tall")) {
                wall.add(state("minecraft:test_wall", "wall_east", east, "wall_west", west));
            }
        }
        blocks.add(wall);
        // Name collision: test.block and test_block are both TEST_BLOCK
        blocks.add(List.of(state("minecraft:test.block")));
        blocks.add(List.of(state("minecraft:test_block", "facing", 0), state("minecraft:test_block", "facing", 1)));
        // A string state whose name collides with the int facing once the namespace is dropped
        blocks.add(List.of(state("minecraft:test_face", "minecraft:facing", "up"), state("minecraft:test_face", "minecraft:facing", "down")));
        // Not a full product: a lookup table
        blocks.add(List.of(state("minecraft:test_sparse", "age", 0, "lit", (byte) 0), state("minecraft:test_sparse", "age", 2, "lit", (byte) 0),
            state("minecraft:test_sparse", "age", 5, "lit", (byte) 1)));
        if (withLamp) {
            // New in the second generation: an int range
            List<NbtMap> lamp = new ArrayList<>();
            for (int level = 0; level < 4; level++) {
                lamp.add(state("minecraft:test_lamp", "level", level, "lit", (byte) 0));
            }
            for (int level = 0; level < 4; level++) {
                lamp.add(state("minecraft:test_lamp", "level", level, "lit", (byte) 1));
            }
            blocks.add(lamp);
        }
        return blocks;
    }

    private static List<VanillaItem> releaseItems(boolean withLamp) {
        List<VanillaItem> items = new ArrayList<>(List.of(
            new VanillaItem("minecraft:apple", 1, 0, false, null),
            new VanillaItem("minecraft:potion", 2, 0, false, null),
            new VanillaItem("minecraft:test_door", 3, 0, false, null),
            new VanillaItem("minecraft:item.test_door", 4, 0, false, null)));
        if (withLamp) {
            items.add(new VanillaItem("minecraft:test_lamp", 5, 0, false, null));
        }
        return items;
    }

    private static VanillaData data(String name, int protocol, List<List<NbtMap>> blocks, List<VanillaItem> items) {
        Map<CreativeCategory, List<RawNode>> creative = new EnumMap<>(CreativeCategory.class);
        for (CreativeCategory category : CreativeCategory.values()) {
            creative.put(category, List.of());
        }
        creative.put(CreativeCategory.ITEMS, List.of(RawStack.of("minecraft:potion"), RawStack.of("minecraft:potion").withDamage(5),
            RawStack.of("minecraft:potion").withDamage(7)));
        List<NbtMap> states = new ArrayList<>();
        blocks.forEach(states::addAll);
        return new VanillaData(new DataVersion(name, protocol), states, items, List.of(), RawLayout.of(creative), Map.of(),
            VersionFallbacks.NONE, Map.of());
    }

    private static void write(Path dir, List<VanillaData> versions, List<Boolean> previews) throws IOException {
        Files.createDirectories(dir);
        StringBuilder index = new StringBuilder("# test\n");
        for (int i = 0; i < versions.size(); i++) {
            VanillaData data = versions.get(i);
            try (OutputStream stream = Files.newOutputStream(dir.resolve(data.version().name() + ".nbt"))) {
                PaletteDataCodec.write(data, stream);
            }
            index.append(data.version().name()).append(' ').append(data.version().protocolVersion())
                .append(previews.get(i) ? " preview" : "").append('\n');
        }
        Files.writeString(dir.resolve("versions.txt"), index);
    }

    private static JsonObject names() {
        return JsonParser.parseString("""
            {
              "blocks": {"minecraft:test.block": "TEST_DOT_BLOCK"},
              "items": {},
              "states": {"minecraft:facing": "MINECRAFT_FACING"},
              "enums": {"wall_east": "WallSide"},
              "renamed": {"blocks": {"minecraft:old_door": "minecraft:test_door"}, "items": {}},
              "fallbacks": {"blocks": {}, "items": {"minecraft:old_stick": "minecraft:apple"}}
            }
            """).getAsJsonObject();
    }

    private static JsonObject entry(String identifier) {
        JsonObject entry = new JsonObject();
        entry.addProperty("identifier", identifier);
        entry.addProperty("since", "1.0.0");
        return entry;
    }

    /**
     * Two generations: 1.0.0 from a release without the lamp (plus history entries of content that is gone since), then
     * 2.1.0 from the full release and a preview.
     *
     * @param shuffle reorders the blocks of the palette, the items and the keys of names.json and the history
     */
    private static ConstantsGenerator.Result generate(Path temp, UnaryOperator<List<List<NbtMap>>> shuffleBlocks,
                                                      UnaryOperator<JsonObject> shuffleJson, UnaryOperator<List<VanillaItem>> shuffleItems)
        throws IOException {
        Path first = temp.resolve("first");
        write(first, List.of(data("1.0", 100, shuffleBlocks.apply(releaseBlocks(false)), shuffleItems.apply(releaseItems(false)))), List.of(false));
        JsonObject history = JsonParser.parseString(ConstantsGenerator.generate(first, shuffleJson.apply(names()), new JsonObject(), "1.0.0")
            .files().get(ConstantsGenerator.HISTORY)).getAsJsonObject();
        // A history from before the Bedrock versions were recorded: they are derived from the releases and captures again
        for (String section : List.of("blocks", "items")) {
            history.getAsJsonObject(section).asMap().values().forEach(value -> value.getAsJsonObject().remove("bedrock"));
        }
        history.getAsJsonObject("blocks").add("REMOVED_BLOCK", entry("minecraft:removed_block"));
        history.getAsJsonObject("blocks").add("OLD_DOOR", entry("minecraft:old_door"));
        history.getAsJsonObject("items").add("OLD_STICK", entry("minecraft:old_stick"));

        Path second = temp.resolve("second");
        List<List<NbtMap>> previewBlocks = new ArrayList<>(releaseBlocks(true));
        previewBlocks.add(List.of(state("minecraft:test_preview", "minecraft:preview_bit", (byte) 0),
            state("minecraft:test_preview", "minecraft:preview_bit", (byte) 1)));
        List<VanillaItem> previewItems = new ArrayList<>(releaseItems(true));
        previewItems.add(new VanillaItem("minecraft:test_preview_item", 6, 0, false, null));
        write(second, List.of(
            data("1.0", 100, releaseBlocks(false), releaseItems(false)),
            data("1.1", 110, shuffleBlocks.apply(releaseBlocks(true)), shuffleItems.apply(releaseItems(true))),
            data("1.2", 120, shuffleBlocks.apply(previewBlocks), shuffleItems.apply(previewItems))), List.of(false, false, true));
        return ConstantsGenerator.generate(second, shuffleJson.apply(names()), shuffleJson.apply(history), "2.1.0",
            ConstantsGenerator.Sources.read(second, captures(temp.resolve("bds"))));
    }

    /**
     * What BDS captured, as {@code data/bds} has it: in release 1.0 (protocol 100 to 109) the block test_sparse and the
     * item potion are behind a current experiment toggle (a retired toggle gates nothing), and a world with Education
     * Edition features has the apple item and the test_block block (found by the 1.1 capture, which is why the union of
     * all captures counts).
     */
    static Path captures(Path bds) throws IOException {
        Path old = Files.createDirectories(bds.resolve("1.0.5"));
        Files.writeString(old.resolve("experiments.json"), """
            {
              "bdsVersion": "1.0.5", "protocol": 105,
              "toggles": {
                "drop": {"status": "current", "content": {"itemsAdded": ["minecraft:potion"], "scriptItemsAdded": [],
                  "blocksAdded": ["minecraft:test_sparse"], "dataDrivenBlocks": []}},
                "old": {"status": "retired"},
                "other": {"status": "retired", "content": {"itemsAdded": ["minecraft:apple"], "blocksAdded": ["minecraft:test_door"]}}
              }
            }
            """);
        Path newer = Files.createDirectories(bds.resolve("1.1.2"));
        Files.writeString(newer.resolve("education.json"), """
            {"bdsVersion": "1.1.2", "protocol": 115, "items": ["minecraft:apple"], "blocks": ["minecraft:test_block"]}
            """);
        return bds;
    }

    private static ConstantsGenerator.Result generate(Path temp) throws IOException {
        return generate(temp, UnaryOperator.identity(), UnaryOperator.identity(), UnaryOperator.identity());
    }

    private static String golden(String path) {
        return path.equals(ConstantsGenerator.HISTORY) ? "constants-history.json" : path.substring(PREFIX.length());
    }

    // ---- Tests ---------------------------------------------------------------------------------------------------

    @Test
    void outputMatchesTheGoldenFiles(@TempDir Path temp) throws IOException {
        ConstantsGenerator.Result result = generate(temp);
        Map<String, String> expected = new TreeMap<>();
        result.files().forEach((path, content) -> expected.put(golden(path), content));

        if (Boolean.getBoolean("paletter.updateGolden")) {
            if (Files.isDirectory(GOLDEN)) {
                try (Stream<Path> files = Files.walk(GOLDEN)) {
                    for (Path file : files.sorted(Collections.reverseOrder()).toList()) {
                        Files.delete(file);
                    }
                }
            }
            for (Map.Entry<String, String> file : expected.entrySet()) {
                Path path = GOLDEN.resolve(file.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
            }
        }

        TreeSet<String> committed = new TreeSet<>();
        try (Stream<Path> files = Files.walk(GOLDEN)) {
            files.filter(Files::isRegularFile).forEach(file -> committed.add(GOLDEN.relativize(file).toString().replace('\\', '/')));
        }
        assertEquals(expected.keySet(), committed, "generated files vs " + GOLDEN);
        for (Map.Entry<String, String> file : expected.entrySet()) {
            String content = Files.readString(GOLDEN.resolve(file.getKey()), StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertEquals(content, file.getValue(), file.getKey() + " differs from the golden file; if intended, run with -PupdateGolden=true");
        }

        assertEquals(List.of("minecraft:test_sparse: 3 states, but its values have 6 combinations: not a full product"), result.tableBlocks());
        assertEquals(List.of("BedrockBlocks.OLD_DOOR (minecraft:old_door, now TEST_DOOR)",
            "BedrockBlocks.REMOVED_BLOCK (minecraft:removed_block, removed; resolves to minecraft:unknown)",
            "BedrockItems.OLD_STICK (minecraft:old_stick, removed; resolves to minecraft:apple)"), result.deprecated());
        assertEquals(List.of("BedrockStates.LEVEL = level", "BedrockBlocks.TEST_LAMP = minecraft:test_lamp", "BedrockItems.TEST_LAMP = minecraft:test_lamp"),
            result.added());
        assertEquals("1.1", result.release());
        assertEquals("1.2", result.preview());
    }

    @Test
    void generationIsDeterministic(@TempDir Path temp) throws IOException {
        ConstantsGenerator.Result first = generate(temp.resolve("a"));
        ConstantsGenerator.Result second = generate(temp.resolve("b"));
        assertEquals(first.files(), second.files());

        for (long seed = 1; seed <= 5; seed++) {
            Random random = new Random(seed);
            ConstantsGenerator.Result shuffled = generate(temp.resolve("shuffled" + seed),
                blocks -> {
                    // Whole blocks move; the order of states within a block is data and stays
                    List<List<NbtMap>> copy = new ArrayList<>(blocks);
                    Collections.shuffle(copy, random);
                    return copy;
                },
                json -> shuffleKeys(json, random),
                items -> {
                    List<VanillaItem> copy = new ArrayList<>(items);
                    Collections.shuffle(copy, random);
                    return copy;
                });
            assertEquals(first.files(), shuffled.files(), "seed " + seed);
        }
    }

    /** The same JSON with the keys of every object in another order, except the values of an enum (their order is the enum's). */
    private static JsonObject shuffleKeys(JsonObject object, Random random) {
        return shuffleKeys(object, random, false);
    }

    private static JsonObject shuffleKeys(JsonObject object, Random random, boolean keepOrder) {
        List<String> keys = new ArrayList<>(object.keySet());
        if (!keepOrder) {
            Collections.shuffle(keys, random);
        }
        JsonObject copy = new JsonObject();
        for (String key : keys) {
            JsonElement value = object.get(key);
            copy.add(key, value.isJsonObject() ? shuffleKeys(value.getAsJsonObject(), random, key.equals("values")) : value.deepCopy());
        }
        return copy;
    }

    // ---- Failures ------------------------------------------------------------------------------------------------

    private static String failure(Path temp, List<List<NbtMap>> blocks, JsonObject names, JsonObject history) throws IOException {
        write(temp, List.of(data("9.9", 999, blocks, releaseItems(false))), List.of(false));
        return assertThrows(IllegalStateException.class, () -> ConstantsGenerator.generate(temp, names, history, "1.0.0")).getMessage();
    }

    private static String historyFailure(Path temp, UnaryOperator<JsonObject> edit) throws IOException {
        Path base = temp.resolve("base");
        write(base, List.of(data("9.9", 999, releaseBlocks(false), releaseItems(false))), List.of(false));
        JsonObject history = JsonParser.parseString(ConstantsGenerator.generate(base, names(), new JsonObject(), "1.0.0")
            .files().get(ConstantsGenerator.HISTORY)).getAsJsonObject();
        return assertThrows(IllegalStateException.class, () -> ConstantsGenerator.generate(base, names(), edit.apply(history), "2.1.0")).getMessage();
    }

    @Test
    void brokenDataFailsClearly(@TempDir Path temp) throws IOException {
        List<List<NbtMap>> split = new ArrayList<>(releaseBlocks(false));
        split.add(List.of(state("minecraft:test_door", "minecraft:cardinal_direction", "up", "open_bit", (byte) 0)));
        assertEquals("Bundled data 9.9: The states of minecraft:test_door are not contiguous in the palette",
            failure(temp.resolve("split"), split, names(), new JsonObject()));

        List<List<NbtMap>> duplicate = new ArrayList<>(releaseBlocks(false));
        duplicate.add(List.of(state("minecraft:test_twice", "lit", (byte) 0), state("minecraft:test_twice", "lit", (byte) 0)));
        assertTrue(failure(temp.resolve("duplicate"), duplicate, names(), new JsonObject())
            .startsWith("Bundled data 9.9: minecraft:test_twice: duplicate state"));

        List<List<NbtMap>> missing = new ArrayList<>(releaseBlocks(false));
        missing.add(List.of(state("minecraft:test_missing", "lit", (byte) 0, "age", 1), state("minecraft:test_missing", "lit", (byte) 1)));
        String message = failure(temp.resolve("missing"), missing, names(), new JsonObject());
        assertTrue(message.startsWith("Bundled data 9.9: minecraft:test_missing: state ")
            && message.endsWith("lacks age, which other states of the block have"), message);

        List<List<NbtMap>> mixed = new ArrayList<>(releaseBlocks(false));
        mixed.add(List.of(state("minecraft:test_mixed", "age", "young"), state("minecraft:test_mixed", "age", "old")));
        assertEquals("State age has values of no supported kind: [young, old, 0, 2, 5]", failure(temp.resolve("mixed"), mixed, names(), new JsonObject()));
    }

    @Test
    void invalidNamesFailClearly(@TempDir Path temp) throws IOException {
        JsonObject noOverride = names();
        noOverride.getAsJsonObject("blocks").remove("minecraft:test.block");
        assertEquals("Duplicate constant BedrockBlocks.TEST_BLOCK for minecraft:test.block and minecraft:test_block; give one a name in "
            + "data/model/names.json blocks", failure(temp.resolve("a"), releaseBlocks(false), noOverride, new JsonObject()));

        JsonObject noState = names();
        noState.getAsJsonObject("states").remove("minecraft:facing");
        assertEquals("Duplicate constant BedrockStates.FACING for facing and minecraft:facing; give one a name in data/model/names.json states",
            failure(temp.resolve("b"), releaseBlocks(false), noState, new JsonObject()));

        JsonObject digit = names();
        digit.getAsJsonObject("items").addProperty("minecraft:apple", "1APPLE");
        assertEquals("minecraft:apple gives the constant name 1APPLE, which is not a Java constant; add it to data/model/names.json items",
            failure(temp.resolve("c"), releaseBlocks(false), digit, new JsonObject()));

        JsonObject enumName = names();
        enumName.getAsJsonObject("enums").addProperty("wall_east", "wall side");
        assertEquals("Enum name wall side of [wall_east, wall_west] is no Java class name; set one in data/model/names.json enums",
            failure(temp.resolve("d"), releaseBlocks(false), enumName, new JsonObject()));

        JsonObject notAnObject = names();
        notAnObject.addProperty("blocks", "oops");
        assertEquals("\"blocks\" in data/model/names.json or data/model/constants-history.json is not an object",
            failure(temp.resolve("e"), releaseBlocks(false), notAnObject, new JsonObject()));
    }

    @Test
    void inconsistentHistoryFailsClearly(@TempDir Path temp) throws IOException {
        assertEquals("data/model/constants-history.json: blocks.GONE lacks \"identifier\"", historyFailure(temp.resolve("a"), history -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("since", "1.0.0");
            history.getAsJsonObject("blocks").add("GONE", entry);
            return history;
        }));
        assertEquals("data/model/constants-history.json: blocks.GONE is an alias of NOWHERE, which is neither generated nor in the history",
            historyFailure(temp.resolve("b"), history -> {
                JsonObject entry = entry("minecraft:gone");
                entry.addProperty("deprecated", "1.0.0");
                entry.addProperty("replacement", "NOWHERE");
                history.getAsJsonObject("blocks").add("GONE", entry);
                return history;
            }));
        assertEquals("BedrockStates.OPEN_BIT (open_bit) changed from int to BOOL, which would change the type of the constant; give the state "
            + "a new name in data/model/names.json states", historyFailure(temp.resolve("c"), history -> {
                history.getAsJsonObject("states").getAsJsonObject("OPEN_BIT").addProperty("kind", "int");
                return history;
            }));
        assertEquals("data/model/constants-history.json: states.GONE has the unknown kind \"float\" (bool, int or enum)",
            historyFailure(temp.resolve("d"), history -> {
                history.getAsJsonObject("states").add("GONE", JsonParser.parseString("{\"name\":\"gone\",\"kind\":\"float\",\"since\":\"1.0.0\"}"));
                return history;
            }));
        assertEquals("data/model/constants-history.json: states.GONE is an enum state without \"enum\"",
            historyFailure(temp.resolve("e"), history -> {
                history.getAsJsonObject("states").add("GONE", JsonParser.parseString("{\"name\":\"gone\",\"kind\":\"enum\",\"since\":\"1.0.0\"}"));
                return history;
            }));
        assertEquals("BedrockStates.GONE needs enum Nowhere, which is not in the history",
            historyFailure(temp.resolve("f"), history -> {
                history.getAsJsonObject("states").add("GONE",
                    JsonParser.parseString("{\"name\":\"gone\",\"kind\":\"enum\",\"enum\":\"Nowhere\",\"since\":\"1.0.0\"}"));
                return history;
            }));
        assertEquals("data/model/constants-history.json: enums.WallSide.NONE lacks \"since\"", historyFailure(temp.resolve("g"), history -> {
            history.getAsJsonObject("enums").getAsJsonObject("WallSide").getAsJsonObject("values").getAsJsonObject("NONE").remove("since");
            return history;
        }));
        assertEquals("data/model/constants-history.json: items.APPLE is not an object", historyFailure(temp.resolve("h"), history -> {
            history.getAsJsonObject("items").addProperty("APPLE", "minecraft:apple");
            return history;
        }));
    }
}
