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
import org.geysermc.paletter.internal.data.Renames;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.data.VanillaItem;
import org.geysermc.paletter.internal.data.VersionFallbacks;
import org.geysermc.paletter.tools.Golden;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
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
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The constants generator on a small synthetic bundle, compared byte for byte with the committed expected sources in
 * {@code src/test/resources/constants-golden}. The fixture has bool, int (range and list) and string states, a value set
 * shared by two states, a name collision settled by a name override, a block that needs a lookup table, a removed block, a
 * renamed block, a removed item, and a preview-only block and item. Item variants: two named
 * potion variants of the release (one already in the history, one new), a removed one, and one only the preview has.
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
        // Two states with the same values: one shared enum, named by an override
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

    private static ConstantsGenerator.Names names() {
        return names(Map.of("minecraft:test.block", "TEST_DOT_BLOCK"), Map.of(), Map.of("minecraft:facing", "MINECRAFT_FACING"),
            Map.of("wall_east", "WallSide"));
    }

    private static ConstantsGenerator.Names names(Map<String, String> blocks, Map<String, String> items, Map<String, String> states,
                                                  Map<String, String> enums) {
        return new ConstantsGenerator.Names(blocks, items, states, enums,
            new Renames(Map.of("minecraft:old_door", "minecraft:test_door"), Map.of()));
    }

    /** {@code variant_names.txt} of the second generation: the release names two potion variants, the preview one more. */
    private static final String VARIANT_NAMES = """
        # test
        1.1 minecraft:potion 5 POTION_NIGHTVISION
        1.1 minecraft:potion 7 POTION_LONG_NIGHTVISION
        1.2 minecraft:potion 0 POTION_WATER
        1.2 minecraft:potion 5 POTION_NIGHTVISION
        1.2 minecraft:potion 7 POTION_LONG_NIGHTVISION
        """;

    private static JsonObject variantEntry(String identifier, int aux) {
        JsonObject entry = entry(identifier);
        entry.addProperty("aux", aux);
        return entry;
    }

    private static JsonObject entry(String identifier) {
        JsonObject entry = new JsonObject();
        entry.addProperty("identifier", identifier);
        entry.addProperty("since", "0.1.0");
        return entry;
    }

    /**
     * Two generations: 0.1.0 from a release without the lamp (plus history entries of content that is gone since), then
     * 2.1.0 from the full release and a preview.
     *
     * @param shuffle reorders the blocks of the palette, the items and the keys of the history
     */
    private static ConstantsGenerator.Result generate(Path temp, UnaryOperator<List<List<NbtMap>>> shuffleBlocks,
                                                      UnaryOperator<JsonObject> shuffleJson, UnaryOperator<List<VanillaItem>> shuffleItems)
        throws IOException {
        Path first = temp.resolve("first");
        write(first, List.of(data("1.0", 100, shuffleBlocks.apply(releaseBlocks(false)), shuffleItems.apply(releaseItems(false)))), List.of(false));
        JsonObject history = JsonParser.parseString(ConstantsGenerator.generate(first, names(), new JsonObject(), "0.1.0")
            .files().get(ConstantsGenerator.HISTORY)).getAsJsonObject();
        // A history from before the Bedrock versions were recorded: they are derived from the releases and captures again
        for (String section : List.of("blocks", "items")) {
            history.getAsJsonObject(section).asMap().values().forEach(value -> value.getAsJsonObject().remove("bedrock"));
        }
        history.getAsJsonObject("blocks").add("REMOVED_BLOCK", entry("minecraft:removed_block"));
        history.getAsJsonObject("blocks").add("OLD_DOOR", entry("minecraft:old_door"));
        history.getAsJsonObject("items").add("OLD_STICK", entry("minecraft:old_stick"));
        history.getAsJsonObject("variants").add("POTION_NIGHTVISION", variantEntry("minecraft:potion", 5));
        history.getAsJsonObject("variants").add("OLD_POTION", variantEntry("minecraft:potion", 9));
        // The name BDS gave aux 5 before: an alias of the new one
        history.getAsJsonObject("variants").add("NIGHT_VISION", variantEntry("minecraft:potion", 5));

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
        Files.writeString(second.resolve("variant_names.txt"), VARIANT_NAMES);
        return ConstantsGenerator.generate(second, names(), shuffleJson.apply(history), "2.1.0",
            ConstantsGenerator.Sources.read(second, captures(temp.resolve("bds")), second.resolve("variant_names.txt")));
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

        Golden.assertDirectory(GOLDEN, expected);

        assertEquals(List.of("minecraft:test_sparse: 3 states, but its values have 6 combinations: not a full product"), result.tableBlocks());
        assertEquals(List.of("BedrockBlocks.OLD_DOOR (minecraft:old_door, now TEST_DOOR)",
            "BedrockBlocks.REMOVED_BLOCK (minecraft:removed_block, removed; resolves to minecraft:unknown)",
            "BedrockItems.OLD_STICK (minecraft:old_stick, removed; resolves to minecraft:unknown)",
            "BedrockItemVariants.OLD_POTION (minecraft:potion@9, removed)",
            "BedrockItemVariants.NIGHT_VISION (minecraft:potion@5, now POTION_NIGHTVISION)"), result.deprecated());
        assertEquals(List.of("BedrockStates.LEVEL = level", "BedrockBlocks.TEST_LAMP = minecraft:test_lamp", "BedrockItems.TEST_LAMP = minecraft:test_lamp",
            "BedrockItemVariants.POTION_LONG_NIGHTVISION = minecraft:potion@7"), result.added());
        assertEquals("1.1", result.release());
        assertEquals("1.2", result.preview());
    }

    @Test
    void aPreviewCaptureGatesNothingInTheReleaseBeforeIt(@TempDir Path temp) throws IOException {
        Path data = temp.resolve("data");
        write(data, List.of(data("1.0", 100, releaseBlocks(false), releaseItems(false)), data("1.1", 110, releaseBlocks(true), releaseItems(true)),
            data("1.2", 120, releaseBlocks(true), releaseItems(true))), List.of(false, false, true));
        Path bds = temp.resolve("bds");
        // Two captures of release 1.1, the later one named 1.10.0 (numerically, not as text, after 1.9.0), and one of the preview
        capture(bds, "1.9.0", 115, "old", "minecraft:potion");
        capture(bds, "1.10.0", 116, "new", "minecraft:potion");
        capture(bds, "1.2.0", 125, "preview", "minecraft:apple");

        ConstantsGenerator.Sources sources = ConstantsGenerator.Sources.read(data, bds, Map.of());
        // The preview's toggle is not release 1.1's, and of two captures of a release the older build's toggle is kept
        assertNull(sources.gating("items", "minecraft:apple"));
        assertEquals("{@code old} in 1.1", sources.gating("items", "minecraft:potion"));
    }

    private static void capture(Path bds, String build, int protocol, String toggle, String item) throws IOException {
        Files.writeString(Files.createDirectories(bds.resolve(build)).resolve("experiments.json"), """
            {"bdsVersion": "%s", "protocol": %d, "toggles": {"%s": {"status": "current", "content": {"itemsAdded": ["%s"]}}}}
            """.formatted(build, protocol, toggle, item));
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

    private static String failure(Path temp, List<List<NbtMap>> blocks, ConstantsGenerator.Names names, JsonObject history) throws IOException {
        write(temp, List.of(data("9.9", 999, blocks, releaseItems(false))), List.of(false));
        return assertThrows(IllegalStateException.class, () -> ConstantsGenerator.generate(temp, names, history, "0.1.0")).getMessage();
    }

    private static String historyFailure(Path temp, UnaryOperator<JsonObject> edit) throws IOException {
        Path base = temp.resolve("base");
        write(base, List.of(data("9.9", 999, releaseBlocks(false), releaseItems(false))), List.of(false));
        JsonObject history = JsonParser.parseString(ConstantsGenerator.generate(base, names(), new JsonObject(), "0.1.0")
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

    /** The data of a release with one potion variant per entry of {@code names} (aux and name), for {@code variant_names.txt}. */
    private static Path variantData(Path dir, String names) throws IOException {
        write(dir, List.of(data("1.0", 100, releaseBlocks(false), releaseItems(false)), data("1.1", 110, releaseBlocks(false), releaseItems(false))),
            List.of(false, false));
        Files.writeString(dir.resolve("variant_names.txt"), names);
        return dir;
    }

    private static ConstantsGenerator.Result variants(Path dir, String names, JsonObject history, String version) throws IOException {
        variantData(dir, names);
        return ConstantsGenerator.generate(dir, names(), history, version, ConstantsGenerator.Sources.read(dir, null, dir.resolve("variant_names.txt")));
    }

    private static JsonObject historyOf(ConstantsGenerator.Result result) {
        return JsonParser.parseString(result.files().get(ConstantsGenerator.HISTORY)).getAsJsonObject().getAsJsonObject("variants");
    }

    @Test
    void variantsAreKeptAcrossReleases(@TempDir Path temp) throws IOException {
        JsonObject first = JsonParser.parseString(variants(temp.resolve("a"), "1.1 minecraft:potion 1 POTION_A\n1.1 minecraft:potion 2 POTION_B\n",
            new JsonObject(), "0.1.0").files().get(ConstantsGenerator.HISTORY)).getAsJsonObject();

        // Release 1.1 drops B, renames aux 3 and adds C: A keeps its since, B is deprecated, C is new
        ConstantsGenerator.Result second = variants(temp.resolve("b"), "1.1 minecraft:potion 1 POTION_A\n1.1 minecraft:potion 3 POTION_C\n", first, "2.1.0");
        JsonObject history = historyOf(second);
        assertEquals("{\"identifier\":\"minecraft:potion\",\"aux\":1,\"since\":\"0.1.0\"}", history.get("POTION_A").toString());
        assertEquals("{\"identifier\":\"minecraft:potion\",\"aux\":2,\"since\":\"0.1.0\",\"deprecated\":\"2.1.0\"}", history.get("POTION_B").toString());
        assertEquals("{\"identifier\":\"minecraft:potion\",\"aux\":3,\"since\":\"2.1.0\"}", history.get("POTION_C").toString());
        assertEquals(List.of("BedrockItemVariants.POTION_C = minecraft:potion@3"), second.added().stream().filter(a -> a.startsWith("BedrockItemVariants")).toList());
        assertEquals(List.of("BedrockItemVariants.POTION_B (minecraft:potion@2, removed)"), second.deprecated());

        // A later generation of the same data changes nothing, and the deprecation stays as it was
        JsonObject secondHistory = JsonParser.parseString(second.files().get(ConstantsGenerator.HISTORY)).getAsJsonObject();
        ConstantsGenerator.Result third = variants(temp.resolve("c"), "1.1 minecraft:potion 1 POTION_A\n1.1 minecraft:potion 3 POTION_C\n", secondHistory, "3.0.0");
        assertEquals(second.files(), third.files());
        assertEquals(List.of(), third.deprecated());

        // BDS names aux 3 differently: the old name becomes an alias of the new one, and stays one
        ConstantsGenerator.Result renamed = variants(temp.resolve("d"), "1.1 minecraft:potion 1 POTION_A\n1.1 minecraft:potion 3 POTION_D\n", secondHistory, "3.0.0");
        assertEquals(List.of("BedrockItemVariants.POTION_C (minecraft:potion@3, now POTION_D)"), renamed.deprecated());
        assertEquals("{\"identifier\":\"minecraft:potion\",\"aux\":3,\"since\":\"2.1.0\",\"deprecated\":\"3.0.0\",\"replacement\":\"POTION_D\"}",
            historyOf(renamed).get("POTION_C").toString());
        String source = renamed.files().get(ConstantsGenerator.PACKAGE_DIR + "/BedrockItemVariants.java");
        assertTrue(source.contains("public static final BedrockItemVariant POTION_C = POTION_D;"), source);
        assertTrue(source.indexOf("POTION_D = VanillaTypes") < source.indexOf("POTION_C = POTION_D"), "an alias comes after its target");
        ConstantsGenerator.Result again = variants(temp.resolve("e"), "1.1 minecraft:potion 1 POTION_A\n1.1 minecraft:potion 3 POTION_D\n",
            JsonParser.parseString(renamed.files().get(ConstantsGenerator.HISTORY)).getAsJsonObject(), "4.0.0");
        assertEquals(renamed.files(), again.files());
    }

    @Test
    void aReleaseWithoutVariantNamesDoesNotDeprecateThem(@TempDir Path temp) throws IOException {
        JsonObject history = JsonParser.parseString(variants(temp.resolve("a"), "1.1 minecraft:potion 1 POTION_A\n", new JsonObject(), "0.1.0")
            .files().get(ConstantsGenerator.HISTORY)).getAsJsonObject();
        assertEquals("The newest release 1.1 has no variant names in data/model/variant_names.txt, but data/model/constants-history.json has 1: "
            + "that would deprecate them all. Run script-dump on a build of it; names are not carried forward",
            assertThrows(IllegalStateException.class, () -> variants(temp.resolve("b"), "# nothing\n", history, "2.1.0")).getMessage());
        // Without a history there is nothing to lose
        assertEquals("{}", historyOf(variants(temp.resolve("c"), "# nothing\n", new JsonObject(), "0.1.0")).toString());
    }

    @Test
    void aVariantNameKeepsItsMeaning(@TempDir Path temp) throws IOException {
        Path data = temp.resolve("data");
        write(data, List.of(data("1.1", 110, releaseBlocks(false), releaseItems(false))), List.of(false));
        Files.writeString(data.resolve("variant_names.txt"), VARIANT_NAMES);
        ConstantsGenerator.Sources sources = ConstantsGenerator.Sources.read(data, null, data.resolve("variant_names.txt"));
        JsonObject history = JsonParser.parseString(ConstantsGenerator.generate(data, names(), new JsonObject(), "0.1.0", sources)
            .files().get(ConstantsGenerator.HISTORY)).getAsJsonObject();
        assertEquals(variantEntry("minecraft:potion", 7).get("aux"), history.getAsJsonObject("variants").getAsJsonObject("POTION_LONG_NIGHTVISION").get("aux"));

        history.getAsJsonObject("variants").getAsJsonObject("POTION_LONG_NIGHTVISION").addProperty("aux", 6);
        assertEquals("BedrockItemVariants.POTION_LONG_NIGHTVISION is minecraft:potion@6 in data/model/constants-history.json but BDS now has "
            + "minecraft:potion@7 under that name; a name keeps its meaning, so this needs a split in renames.txt and a new name",
            assertThrows(IllegalStateException.class, () -> ConstantsGenerator.generate(data, names(), history, "0.1.0", sources)).getMessage());
    }

    @Test
    void invalidNamesFailClearly(@TempDir Path temp) throws IOException {
        ConstantsGenerator.Names noOverride = names(Map.of(), Map.of(), Map.of("minecraft:facing", "MINECRAFT_FACING"), Map.of("wall_east", "WallSide"));
        assertEquals("Duplicate constant BedrockBlocks.TEST_BLOCK for minecraft:test.block and minecraft:test_block; give one a name: "
            + "add the line `blocks minecraft:test_block <NAME>` to data/model/constant-names.txt", failure(temp.resolve("a"), releaseBlocks(false), noOverride, new JsonObject()));

        ConstantsGenerator.Names noState = names(Map.of("minecraft:test.block", "TEST_DOT_BLOCK"), Map.of(), Map.of(), Map.of("wall_east", "WallSide"));
        assertEquals("Duplicate constant BedrockStates.FACING for facing and minecraft:facing; give one a name: "
            + "add the line `states minecraft:facing <NAME>` to data/model/constant-names.txt", failure(temp.resolve("b"), releaseBlocks(false), noState, new JsonObject()));

        ConstantsGenerator.Names digit = names(Map.of("minecraft:test.block", "TEST_DOT_BLOCK"), Map.of("minecraft:apple", "1APPLE"),
            Map.of("minecraft:facing", "MINECRAFT_FACING"), Map.of("wall_east", "WallSide"));
        assertEquals("minecraft:apple gives the constant name 1APPLE, which is not a Java constant; "
            + "add the line `items minecraft:apple <NAME>` to data/model/constant-names.txt", failure(temp.resolve("c"), releaseBlocks(false), digit, new JsonObject()));

        ConstantsGenerator.Names enumName = names(Map.of("minecraft:test.block", "TEST_DOT_BLOCK"), Map.of(),
            Map.of("minecraft:facing", "MINECRAFT_FACING"), Map.of("wall_east", "wall side"));
        assertEquals("Enum name wall side of [wall_east, wall_west] is no Java class name; "
            + "add the line `enums wall_east <ClassName>` to data/model/constant-names.txt", failure(temp.resolve("d"), releaseBlocks(false), enumName, new JsonObject()));
    }

    @Test
    void inconsistentHistoryFailsClearly(@TempDir Path temp) throws IOException {
        assertEquals("data/model/constants-history.json: blocks.GONE lacks \"identifier\"", historyFailure(temp.resolve("a"), history -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("since", "0.1.0");
            history.getAsJsonObject("blocks").add("GONE", entry);
            return history;
        }));
        assertEquals("data/model/constants-history.json: blocks.GONE is an alias of NOWHERE, which is neither generated nor in the history",
            historyFailure(temp.resolve("b"), history -> {
                JsonObject entry = entry("minecraft:gone");
                entry.addProperty("deprecated", "0.1.0");
                entry.addProperty("replacement", "NOWHERE");
                history.getAsJsonObject("blocks").add("GONE", entry);
                return history;
            }));
        assertEquals("BedrockStates.OPEN_BIT (open_bit) changed from int to BOOL, which would change the type of the constant; give the state "
            + "a new name: add the line `states open_bit <NAME>` to data/model/constant-names.txt", historyFailure(temp.resolve("c"), history -> {
                history.getAsJsonObject("states").getAsJsonObject("OPEN_BIT").addProperty("kind", "int");
                return history;
            }));
        assertEquals("data/model/constants-history.json: states.GONE has the unknown kind \"float\" (bool, int or enum)",
            historyFailure(temp.resolve("d"), history -> {
                history.getAsJsonObject("states").add("GONE", JsonParser.parseString("{\"name\":\"gone\",\"kind\":\"float\",\"since\":\"0.1.0\"}"));
                return history;
            }));
        assertEquals("data/model/constants-history.json: states.GONE is an enum state without \"enum\"",
            historyFailure(temp.resolve("e"), history -> {
                history.getAsJsonObject("states").add("GONE", JsonParser.parseString("{\"name\":\"gone\",\"kind\":\"enum\",\"since\":\"0.1.0\"}"));
                return history;
            }));
        assertEquals("BedrockStates.GONE needs enum Nowhere, which is not in the history",
            historyFailure(temp.resolve("f"), history -> {
                history.getAsJsonObject("states").add("GONE",
                    JsonParser.parseString("{\"name\":\"gone\",\"kind\":\"enum\",\"enum\":\"Nowhere\",\"since\":\"0.1.0\"}"));
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
