package dev.opencollab.paletter.tools.codegen.constants;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.data.PaletteDataCodec;
import dev.opencollab.paletter.tools.codegen.component.ExperimentToggles;
import dev.opencollab.paletter.internal.data.Renames;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.data.VanillaItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The constants generator against the newest bundled release, with synthetic histories and names: deprecation of
 * removed and renamed content, stable {@code @since}, naming rules, previews. Generated sources are compiled.
 */
class ConstantsGeneratorTest {
    private static final Path ROOT = Path.of("..");
    private static final String BLOCKS = ConstantsGenerator.PACKAGE_DIR + "/BedrockBlocks.java";
    private static final String ITEMS = ConstantsGenerator.PACKAGE_DIR + "/BedrockItems.java";
    private static final String STATES = ConstantsGenerator.PACKAGE_DIR + "/BedrockStates.java";
    private static VanillaData release;
    private static ConstantsGenerator.Names names;

    @BeforeAll
    static void load() throws IOException {
        List<ConstantsGenerator.Bundle> bundles = ConstantsGenerator.readIndex(ROOT.resolve(ConstantsGenerator.DATA));
        String name = bundles.stream().filter(b -> !b.preview()).reduce((a, b) -> b).orElseThrow().name();
        try (InputStream stream = Files.newInputStream(ROOT.resolve(ConstantsGenerator.DATA).resolve(name + ".nbt"))) {
            release = PaletteDataCodec.read(stream);
        }
        names = ConstantsGenerator.names(ROOT);
    }

    private static JsonObject history(ConstantsGenerator.Result result) {
        return JsonParser.parseString(result.files().get(ConstantsGenerator.HISTORY)).getAsJsonObject();
    }

    private static JsonObject entry(String identifier) {
        JsonObject entry = new JsonObject();
        entry.addProperty("identifier", identifier);
        entry.addProperty("since", "1.0.0");
        return entry;
    }

    private static VanillaData version(String name, int protocol, String... items) {
        return new VanillaData(DataVersion.of(name, protocol), List.of(BlockStates.of("minecraft:air", Map.of()), BlockStates.of("minecraft:unknown", Map.of())),
            java.util.stream.IntStream.range(0, items.length).mapToObj(i -> new VanillaItem(items[i], i + 1, 0, false, null)).toList(),
            List.of(), dev.opencollab.paletter.internal.creative.RawLayout.empty(), Map.of(),
            dev.opencollab.paletter.internal.data.VersionFallbacks.NONE, Map.of());
    }

    private static ConstantsGenerator.Sources sources(Map<String, Map<String, String>> gated, java.util.Set<String> education,
                                                      VanillaData... releases) {
        return new ConstantsGenerator.Sources(List.of(releases), gated, Map.of(), education, java.util.Set.of(), Map.of());
    }

    @Test
    void sinceIsTheFirstReleaseWithoutAnExperiment() {
        ConstantsGenerator.Sources sources = sources(
            Map.of("1.0", Map.of("minecraft:gated", "drop"), "1.1", Map.of("minecraft:always", "other")), java.util.Set.of(),
            version("1.0", 100, "minecraft:plain", "minecraft:gated", "minecraft:always"),
            version("1.1", 110, "minecraft:plain", "minecraft:gated", "minecraft:always", "minecraft:fresh"),
            version("1.2", 120, "minecraft:plain", "minecraft:gated", "minecraft:fresh"));
        assertEquals("1.0", sources.firstVersion("items", "minecraft:plain"));
        // Gated in the release that has it first, regular in the next
        assertEquals("1.1", sources.firstVersion("items", "minecraft:gated"));
        assertEquals("{@code drop} in 1.0", sources.gating("items", "minecraft:gated"));
        // Never regular in a release that has it: the first release that has it
        assertEquals("1.0", sources.firstVersion("items", "minecraft:always"));
        assertEquals("{@code other} in 1.1", sources.gating("items", "minecraft:always"));
        assertEquals("1.1", sources.firstVersion("items", "minecraft:fresh"));
        assertNull(sources.gating("items", "minecraft:fresh"));
        // Not in any release (a removed constant without a record): the oldest
        assertEquals("1.0", sources.firstVersion("items", "minecraft:gone"));
    }

    @Test
    void theHistoryKeepsWhatItRecorded() {
        ConstantsGenerator.Sources sources = sources(Map.of(), java.util.Set.of("minecraft:apple"),
            version("1.0", 100, "minecraft:apple", "minecraft:potion"), version("1.1", 110, "minecraft:apple", "minecraft:potion"));
        JsonObject old = new JsonObject();
        JsonObject potion = entry("minecraft:potion");
        potion.addProperty("bedrock", "0.9");
        JsonObject apple = entry("minecraft:apple");
        JsonObject items = new JsonObject();
        items.add("POTION", potion);
        items.add("APPLE", apple);
        old.add("items", items);
        JsonObject itemHistory = history(ConstantsGenerator.generate(sources.releases().getLast(), null, names(), old, "2.0.0", sources))
            .getAsJsonObject("items");
        // A recorded version stays (a release leaving the bundle must not change it), the others are derived
        assertEquals("0.9", itemHistory.getAsJsonObject("POTION").get("bedrock").getAsString());
        assertEquals("1.0", itemHistory.getAsJsonObject("APPLE").get("bedrock").getAsString());
        assertTrue(itemHistory.getAsJsonObject("APPLE").get("education").getAsBoolean());
        assertFalse(itemHistory.getAsJsonObject("POTION").has("education"));

        // Education is sticky as well: a capture that no longer lists it does not clear it
        apple.addProperty("education", true);
        JsonObject again = history(ConstantsGenerator.generate(sources.releases().getLast(), null, names(), old, "2.0.0",
            sources(Map.of(), java.util.Set.of(), version("1.0", 100, "minecraft:apple", "minecraft:potion"), version("1.1", 110, "minecraft:apple", "minecraft:potion"))))
            .getAsJsonObject("items");
        assertTrue(again.getAsJsonObject("APPLE").get("education").getAsBoolean());
    }

    @Test
    void theExperimentConstantsAreWrittenFromTheToggles() {
        ConstantsGenerator.Sources sources = new ConstantsGenerator.Sources(List.of(version("1.0", 100, "minecraft:plain")), Map.of(), Map.of(),
            java.util.Set.of(), java.util.Set.of(), Map.of(), List.of(new ExperimentToggles.Toggle("y_2026_drop_3", "Drop 3", "1.0", false, "1.1"),
            new ExperimentToggles.Toggle("gametest", null, "1.0", false, null), new ExperimentToggles.Toggle("next", "Next", "1.2", true, null)));
        String source = ConstantsGenerator.generate(sources.releases().getFirst(), null, names(), new JsonObject(), "2.0.0", sources)
            .files().get(ConstantsGenerator.PACKAGE_DIR + "/BedrockExperiments.java");
        assertTrue(source.contains("@BedrockSince(\"1.0\")\n    @BedrockUntil(\"1.1\")\n    public static final String Y_2026_DROP_3 = \"y_2026_drop_3\";"), source);
        assertTrue(source.contains("@BedrockSince(\"1.0\")\n    public static final String GAMETEST = \"gametest\";"), source);
        assertTrue(source.contains("import dev.opencollab.paletter.annotation.BedrockUntil;"));
        assertTrue(source.contains("@SincePreview(\"1.2\")\n    public static final String NEXT = \"next\";"), "a preview toggle is marked as preview, not @BedrockSince");
        assertFalse(source.contains("@BedrockSince(\"1.2\")"), source);
        // Without any toggle there is no class
        assertFalse(ConstantsGenerator.generate(sources(Map.of(), java.util.Set.of(), version("1.0", 100, "minecraft:plain")).releases().getFirst(), null,
            names(), new JsonObject(), "2.0.0").files().containsKey(ConstantsGenerator.PACKAGE_DIR + "/BedrockExperiments.java"));
    }

    private static ConstantsGenerator.Names names() {
        return ConstantsGenerator.Names.of(Renames.NONE, List.of(), new JsonObject());
    }

    @Test
    void committedOutputIsReproducible() throws IOException {
        ConstantsGenerator.Result result = ConstantsGenerator.generate(ROOT);
        assertTrue(result.tableBlocks().isEmpty(), "blocks needing a lookup table: " + result.tableBlocks());
        assertTrue(result.added().isEmpty(), "constants missing from the committed history: " + result.added());
        assertTrue(result.deprecated().isEmpty());
        for (Map.Entry<String, String> file : result.files().entrySet()) {
            assertEquals(Files.readString(ROOT.resolve(file.getKey())).replace("\r\n", "\n"), file.getValue(), file.getKey());
        }
    }

    @Test
    void removedAndRenamedContentIsDeprecated(@TempDir Path temp) throws IOException {
        JsonObject first = history(ConstantsGenerator.generate(release, null, names, new JsonObject(), "1.0.0"));

        // A previous generation had a block Bedrock removed, a constant since renamed, an item Bedrock renamed,
        // a state Bedrock removed and an enum value no state has anymore; STONE did not exist yet
        first.getAsJsonObject("blocks").add("REMOVED_BLOCK", entry("minecraft:removed_block"));
        first.getAsJsonObject("blocks").add("OLD_STONE_NAME", entry("minecraft:stone"));
        first.getAsJsonObject("blocks").remove("STONE");
        first.getAsJsonObject("items").add("OLD_ITEM", entry("minecraft:old_item"));
        first.getAsJsonObject("items").add("REMOVED_ITEM", entry("minecraft:removed_item"));
        JsonObject state = new JsonObject();
        state.addProperty("name", "removed_state");
        state.addProperty("kind", "bool");
        state.addProperty("since", "1.0.0");
        first.getAsJsonObject("states").add("REMOVED_STATE", state);
        JsonObject value = new JsonObject();
        value.addProperty("value", "upwards");
        value.addProperty("since", "1.0.0");
        first.getAsJsonObject("enums").getAsJsonObject("CardinalDirection").getAsJsonObject("values").add("UPWARDS", value);

        ConstantsGenerator.Names names = new ConstantsGenerator.Names(this.names.blocks(), this.names.items(), this.names.states(),
            this.names.enums(), new Renames(Map.of(), Map.of("minecraft:old_item", "minecraft:apple")), java.util.Set.of());

        ConstantsGenerator.Result second = ConstantsGenerator.generate(release, null, names, first, "2.1.0");
        String blocks = second.files().get(BLOCKS);
        assertTrue(blocks.contains("""
                @Deprecated(
                        since = "2.1.0"
                )
                public static final BedrockBlockType REMOVED_BLOCK = VanillaTypes.block("REMOVED_BLOCK", "minecraft:removed_block");
            """), blocks.substring(0, 200));
        assertTrue(blocks.contains("@deprecated Bedrock removed the block; palettes without it resolve it through their {@link dev.opencollab.paletter.Fallbacks}"));
        assertTrue(blocks.contains("""
                @Deprecated(
                        since = "2.1.0"
                )
                public static final BedrockBlockType OLD_STONE_NAME = STONE;
            """));
        // New in Paletter 2.1.0 (the @since), in the newest release (the line of the Bedrock release)
        assertTrue(blocks.contains("""
                 * First in Bedrock %s.
                 *
                 * @since 2.1.0
                 */
                public static final BedrockBlockType STONE = VanillaTypes.block("STONE", "minecraft:stone");
            """.formatted(release.version().name())));
        String items = second.files().get(ITEMS);
        assertTrue(items.contains("public static final BedrockItemType OLD_ITEM = APPLE;"));
        assertTrue(items.contains("@deprecated Bedrock renamed it to {@link #APPLE}"));
        assertTrue(items.contains("REMOVED_ITEM = VanillaTypes.item(\"REMOVED_ITEM\", \"minecraft:removed_item\");"));
        assertFalse(blocks.contains("forRemoval") || items.contains("forRemoval"), "names are never removed");
        String states = second.files().get(STATES);
        assertTrue(states.contains("""
                @Deprecated(
                        since = "2.1.0"
                )
                public static final BoolState REMOVED_STATE = VanillaTypes.state(BoolState.of("removed_state"));
            """));
        String cardinal = second.files().get(ConstantsGenerator.STATE_DIR + "/CardinalDirection.java");
        assertTrue(cardinal.contains("    @Deprecated(\n            since = \"2.1.0\"\n    )\n    UPWARDS(\"upwards\")"), cardinal);

        assertTrue(second.added().contains("BedrockBlocks.STONE = minecraft:stone"));
        assertEquals(6, second.deprecated().size(), second.deprecated().toString());
        compile(second, temp);

        // The next generation keeps every deprecation as it was and reports nothing new
        JsonObject secondHistory = history(second);
        assertEquals("2.1.0", secondHistory.getAsJsonObject("blocks").getAsJsonObject("REMOVED_BLOCK").get("deprecated").getAsString());
        assertEquals("STONE", secondHistory.getAsJsonObject("blocks").getAsJsonObject("OLD_STONE_NAME").get("replacement").getAsString());
        ConstantsGenerator.Result third = ConstantsGenerator.generate(release, null, names, secondHistory, "2.2.0");
        assertTrue(third.deprecated().isEmpty(), third.deprecated().toString());
        assertTrue(third.added().isEmpty(), third.added().toString());
        assertEquals(second.files(), third.files());
    }

    @Test
    void namesAreUnique() {
        assertEquals("ITEM_BED", ConstantsGenerator.constantName("minecraft:item.bed"));
        assertEquals("EXAMPLE_FOO_BAR", ConstantsGenerator.constantName("example:foo-bar"));
        assertEquals("CardinalDirection", StateConstants.className("minecraft:cardinal_direction"));
        assertEquals("WallConnectionType", StateConstants.className("wall_connection_type_east"));

        ConstantsGenerator.Names clash = new ConstantsGenerator.Names(Map.of("minecraft:dirt", "STONE"), names.items(), names.states(),
            names.enums(), names.renames(), java.util.Set.of());
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> ConstantsGenerator.generate(release, null, clash, new JsonObject(), "1.0.0"));
        assertTrue(e.getMessage().contains("Duplicate constant BedrockBlocks.STONE"), e.getMessage());

        ConstantsGenerator.Names noOverride = new ConstantsGenerator.Names(names.blocks(), names.items(), Map.of(), names.enums(),
            names.renames(), java.util.Set.of());
        e = assertThrows(IllegalStateException.class, () -> ConstantsGenerator.generate(release, null, noOverride, new JsonObject(), "1.0.0"));
        assertTrue(e.getMessage().contains("Duplicate constant BedrockStates.FACING_DIRECTION"), e.getMessage());

        ConstantsGenerator.Names invalid = new ConstantsGenerator.Names(names.blocks(), Map.of("minecraft:apple", "1APPLE"), names.states(),
            names.enums(), names.renames(), java.util.Set.of());
        assertThrows(IllegalStateException.class, () -> ConstantsGenerator.generate(release, null, invalid, new JsonObject(), "1.0.0"));
    }

    @Test
    void previewContentIsMarkedSincePreview(@TempDir Path temp) throws IOException {
        List<NbtMap> states = new ArrayList<>(release.blockStates());
        states.add(BlockStates.of("minecraft:preview_block", Map.of("preview_bit", (byte) 0)));
        states.add(BlockStates.of("minecraft:preview_block", Map.of("preview_bit", (byte) 1)));
        List<VanillaItem> items = new ArrayList<>(release.items());
        items.add(new VanillaItem("minecraft:preview_item", 30000, 0, false, null));
        VanillaData preview = new VanillaData(DataVersion.of("26.70", 9999), states, items, release.dataDrivenBlocks(), release.creative(),
            release.itemTags(), release.fallbacks(), release.blockDefaults());

        ConstantsGenerator.Result result = ConstantsGenerator.generate(release, preview, names, new JsonObject(), "1.0.0");
        // In the same classes as the release, marked with the preview
        assertFalse(result.files().containsKey(ConstantsGenerator.PACKAGE_DIR + "/BedrockPreviewBlocks.java"));
        // The constant carries the preview's states: a release palette has no data for the block
        assertTrue(result.files().get(BLOCKS).contains("    @SincePreview(\"26.70\")\n    public static final BedrockBlockType PREVIEW_BLOCK = "
            + "VanillaTypes.blockWithStates(\"PREVIEW_BLOCK\", \"minecraft:preview_block\", 0,\n        VanillaTypes.property(\"preview_bit\", (byte) 0, (byte) 1));"));
        assertTrue(result.files().get(ConstantsGenerator.PACKAGE_DIR + "/BedrockItems.java")
            .contains("    @SincePreview(\"26.70\")\n    public static final BedrockItemType PREVIEW_ITEM = VanillaTypes.item(\"PREVIEW_ITEM\", \"minecraft:preview_item\");"));
        assertTrue(result.files().get(ConstantsGenerator.PACKAGE_DIR + "/BedrockStates.java")
            .contains("    @SincePreview(\"26.70\")\n    public static final BoolState PREVIEW_BIT = "));
        // Preview blocks and items are recorded with their preview (and a block with its states), states are not
        JsonObject history = history(result);
        assertEquals("26.70", history.getAsJsonObject("blocks").getAsJsonObject("PREVIEW_BLOCK").get("preview").getAsString());
        assertNotNull(history.getAsJsonObject("blocks").getAsJsonObject("PREVIEW_BLOCK").get("states"));
        assertEquals("26.70", history.getAsJsonObject("items").getAsJsonObject("PREVIEW_ITEM").get("preview").getAsString());
        assertNull(history.getAsJsonObject("states").get("PREVIEW_BIT"));
        compile(result, temp);

        // The same preview again changes nothing
        assertEquals(result.files(), ConstantsGenerator.generate(release, preview, names, history, "1.0.0").files());

        // A newer preview that drops them: the constants stay, deprecated and still marked, the block with its states
        ConstantsGenerator.Result dropped = ConstantsGenerator.generate(release, null, names, history, "2.1.0");
        String blocks = dropped.files().get(BLOCKS);
        assertTrue(blocks.contains("""
                @SincePreview("26.70")
                @Deprecated(
                        since = "2.1.0"
                )
                public static final BedrockBlockType PREVIEW_BLOCK = VanillaTypes.blockWithStates("PREVIEW_BLOCK", "minecraft:preview_block", 0,
                    VanillaTypes.property("preview_bit", (byte) 0, (byte) 1));
            """), blocks);
        assertTrue(blocks.contains("@deprecated a newer Bedrock preview dropped the block"));
        String droppedItems = dropped.files().get(ITEMS);
        assertTrue(droppedItems.contains("PREVIEW_ITEM = VanillaTypes.item(\"PREVIEW_ITEM\", \"minecraft:preview_item\");"), droppedItems);
        assertTrue(dropped.deprecated().stream().anyMatch(d -> d.contains("PREVIEW_ITEM")), dropped.deprecated().toString());
        compile(dropped, temp.resolve("dropped"));
        // And so on: the next generation keeps them as they are
        assertEquals(dropped.files(), ConstantsGenerator.generate(release, null, names, history(dropped), "2.2.0").files());

        // A release that ships them: plain constants, out of the preview
        ConstantsGenerator.Result shipped = ConstantsGenerator.generate(preview, null, names, history, "2.1.0");
        assertTrue(shipped.files().get(BLOCKS).contains("    public static final BedrockBlockType PREVIEW_BLOCK = VanillaTypes.block("),
            "a plain constant, without a preview mark");
        assertFalse(shipped.files().get(BLOCKS).contains("@SincePreview(\"26.70\")\n    public static final BedrockBlockType PREVIEW_BLOCK"));
        assertNull(history(shipped).getAsJsonObject("blocks").getAsJsonObject("PREVIEW_BLOCK").get("preview"));
        assertNull(history(shipped).getAsJsonObject("blocks").getAsJsonObject("PREVIEW_BLOCK").get("states"));
    }

    /** A block Bedrock removes keeps the states it last had: from an older bundled release, then from the history. */
    @Test
    void removedBlocksKeepTheirStates(@TempDir Path temp) throws IOException {
        List<NbtMap> states = new ArrayList<>(release.blockStates());
        states.add(BlockStates.of("minecraft:going_block", Map.of("going_bit", (byte) 0)));
        states.add(BlockStates.of("minecraft:going_block", Map.of("going_bit", (byte) 1)));
        VanillaData older = new VanillaData(DataVersion.of("26.10", 1), states, release.items(), release.dataDrivenBlocks(),
            release.creative(), release.itemTags(), release.fallbacks(), release.blockDefaults());
        JsonObject first = history(ConstantsGenerator.generate(older, null, names, new JsonObject(), "1.0.0"));
        assertNull(first.getAsJsonObject("blocks").getAsJsonObject("GOING_BLOCK").get("states"), "a release block records no states");

        String expected = "GOING_BLOCK = VanillaTypes.blockWithStates(\"GOING_BLOCK\", \"minecraft:going_block\", 0,\n"
            + "        VanillaTypes.property(\"going_bit\", (byte) 0, (byte) 1));";
        // Removed in the newest release, still in the older bundled one
        ConstantsGenerator.Result removed = ConstantsGenerator.generate(release, null, names, first, "2.1.0",
            new ConstantsGenerator.Sources(List.of(older, release), Map.of(), Map.of(), java.util.Set.of(), java.util.Set.of(), Map.of()));
        assertTrue(removed.files().get(BLOCKS).contains(expected), removed.files().get(BLOCKS));
        JsonObject recorded = history(removed);
        assertNotNull(recorded.getAsJsonObject("blocks").getAsJsonObject("GOING_BLOCK").get("states"));
        compile(removed, temp);

        // The older release retired: the history still has the states
        ConstantsGenerator.Result retired = ConstantsGenerator.generate(release, null, names, recorded, "2.2.0");
        assertTrue(retired.files().get(BLOCKS).contains(expected));
        assertEquals(recorded, history(retired));
    }

    /**
     * A preview block's constant lists its properties in the preview's state order (the fastest varying first, not by
     * name), each with its values in order of first appearance, and the position of its default state: enumerated, they
     * are the preview's states in the preview's order.
     */
    @Test
    void previewBlockStatesFollowThePreviewsOrder(@TempDir Path temp) throws IOException {
        List<NbtMap> states = new ArrayList<>(release.blockStates());
        List<NbtMap> block = new ArrayList<>();
        // "b_size" varies fastest although "a_facing" sorts first; values in the preview's order, not sorted
        for (String facing : List.of("south", "north")) {
            for (int size : List.of(2, 0, 1)) {
                block.add(BlockStates.of("minecraft:preview_block", Map.of("a_facing", facing, "b_size", size)));
            }
        }
        states.addAll(block);
        Map<String, NbtMap> defaults = new java.util.HashMap<>(release.blockDefaults());
        defaults.put("minecraft:preview_block", block.get(4));
        VanillaData preview = new VanillaData(DataVersion.of("26.70", 9999), states, release.items(), release.dataDrivenBlocks(),
            release.creative(), release.itemTags(), release.fallbacks(), defaults);

        ConstantsGenerator.Result result = ConstantsGenerator.generate(release, preview, names, new JsonObject(), "1.0.0");
        assertTrue(result.files().get(BLOCKS).contains("VanillaTypes.blockWithStates(\"PREVIEW_BLOCK\", \"minecraft:preview_block\", 4,\n"
            + "        VanillaTypes.property(\"b_size\", 2, 0, 1),\n"
            + "        VanillaTypes.property(\"a_facing\", \"south\", \"north\"));"), result.files().get(BLOCKS));
        ConstantsGenerator.PreviewStates read = ConstantsGenerator.previewStates(preview,
            dev.opencollab.paletter.internal.vanilla.BlockLayout.of(states).getLast());
        assertEquals(4, read.defaultOffset());
        assertEquals(block.stream().map(state -> state.getCompound("states")).toList(), read.enumerate());
        compile(result, temp);
    }

    /** A preview block whose states are no full product cannot be described by its constant: generation fails. */
    @Test
    void aPreviewBlockThatNeedsATableFails() {
        List<NbtMap> states = new ArrayList<>(release.blockStates());
        states.add(BlockStates.of("minecraft:preview_block", Map.of("a", 0, "b", 0)));
        states.add(BlockStates.of("minecraft:preview_block", Map.of("a", 1, "b", 0)));
        states.add(BlockStates.of("minecraft:preview_block", Map.of("a", 0, "b", 1)));
        VanillaData preview = new VanillaData(DataVersion.of("26.70", 9999), states, release.items(), release.dataDrivenBlocks(),
            release.creative(), release.itemTags(), release.fallbacks(), release.blockDefaults());
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> ConstantsGenerator.generate(release, preview, names, new JsonObject(), "1.0.0"));
        assertTrue(e.getMessage().contains("minecraft:preview_block"), e.getMessage());
    }

    @Test
    void stateValuesAreJavaLiteralsOfTheirNbtType() {
        assertEquals("(byte) 1", ConstantsSource.literal((byte) 1));
        assertEquals("-3", ConstantsSource.literal(-3));
        assertEquals("\"say \\\"hi\\\" \\\\\"", ConstantsSource.literal("say \"hi\" \\"));
    }

    private static JsonObject itemsOf(ConstantsGenerator.Result result) {
        return history(result).getAsJsonObject("items");
    }

    private static ConstantsGenerator.Result generate(ConstantsGenerator.Sources sources, JsonObject history, String version) {
        return ConstantsGenerator.generate(sources.releases().getLast(), null, names(), history, version, sources);
    }

    @Test
    void theBedrockReleaseFollowsTheDataUntilItsReleaseLeavesTheBundle() {
        Map<String, Map<String, String>> gated = Map.of("1.1", Map.of("minecraft:egg", "drop"));
        VanillaData v10 = version("1.0", 100, "minecraft:apple");
        VanillaData v11 = version("1.1", 110, "minecraft:apple", "minecraft:egg");
        VanillaData v12 = version("1.2", 120, "minecraft:apple", "minecraft:egg");

        // Only 1.1 is bundled, and it has the egg behind an experiment: the first release that has it
        ConstantsGenerator.Result first = generate(sources(gated, java.util.Set.of(), v11), new JsonObject(), "1.0.0");
        assertEquals("1.1", itemsOf(first).getAsJsonObject("EGG").get("bedrock").getAsString());
        assertEquals("1.1", itemsOf(first).getAsJsonObject("APPLE").get("bedrock").getAsString());

        // 1.2 ungates the egg, and 1.0 is back-filled: what the history recorded is derived again, not kept
        ConstantsGenerator.Result second = generate(sources(gated, java.util.Set.of(), v10, v11, v12), history(first), "2.0.0");
        assertEquals("1.2", itemsOf(second).getAsJsonObject("EGG").get("bedrock").getAsString());
        assertEquals("1.0", itemsOf(second).getAsJsonObject("APPLE").get("bedrock").getAsString());
        assertTrue(second.files().get(ITEMS).contains("existed before the oldest bundled release ({@code 1.0})"));
        assertTrue(second.files().get(ITEMS).contains("First in Bedrock 1.2."));
        assertTrue(second.files().get(ITEMS).contains("Behind the experiment {@code drop} in 1.1"));

        // Releases leave the bundle: a recorded release that is not bundled anymore stays, the others are derived
        ConstantsGenerator.Result third = generate(sources(Map.of(), java.util.Set.of(), v12), history(second), "3.0.0");
        assertEquals("1.0", itemsOf(third).getAsJsonObject("APPLE").get("bedrock").getAsString());
        assertEquals("1.2", itemsOf(third).getAsJsonObject("EGG").get("bedrock").getAsString());
    }

    @Test
    void contentOfTheBaselineHasNoBedrockReleaseWhenTheOldestBundledOneHasIt(@TempDir Path temp) throws IOException {
        Path file = temp.resolve("baseline.txt");
        Files.writeString(file, "# a comment\nitems minecraft:apple\nblocks minecraft:egg\n");
        VanillaData v10 = version("1.0", 100, "minecraft:apple", "minecraft:egg", "minecraft:pear");
        VanillaData v11 = version("1.1", 110, "minecraft:apple", "minecraft:egg", "minecraft:pear", "minecraft:plum");

        ConstantsGenerator.Result result = generate(sources(Map.of(), java.util.Set.of(), v10, v11).withBaseline(file), new JsonObject(), "1.0.0");
        String source = result.files().get(ITEMS);
        // The apple was in the baseline: older than the bundle, so no line and no recorded release
        assertTrue(source.contains("     * {@code minecraft:apple}.\n     */\n    public static final BedrockItemType APPLE"), source);
        assertFalse(itemsOf(result).getAsJsonObject("APPLE").has("bedrock"));
        // The baseline lists blocks and items apart: a block of that name says nothing of the item
        assertEquals("1.0", itemsOf(result).getAsJsonObject("EGG").get("bedrock").getAsString());
        assertTrue(source.contains("First in Bedrock 1.0."));
        // Content the baseline lacks keeps its release, new or not
        assertEquals("1.1", itemsOf(result).getAsJsonObject("PLUM").get("bedrock").getAsString());

        // Regenerating from that history gives the same
        ConstantsGenerator.Result again = generate(sources(Map.of(), java.util.Set.of(), v10, v11).withBaseline(file), history(result), "1.0.0");
        assertEquals(source, again.files().get(ITEMS));
        compile(result, temp);
    }

    @Test
    void theCommittedBaselineNamesItsSource() throws IOException {
        List<String> lines = Files.readAllLines(ROOT.resolve(Baseline.FILE));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("#") && line.contains("CloudburstMC/Data commit eb77f9f")), lines.getFirst());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("#") && line.contains("Apache License 2.0")));
        assertTrue(lines.contains("blocks minecraft:stone"));
        assertTrue(lines.contains("items minecraft:apple"));
    }

    @Test
    void sinceIsThePaletterVersionInEveryClass(@TempDir Path temp) throws IOException {
        JsonObject old = new JsonObject();
        JsonObject items = new JsonObject();
        items.add("APPLE", entry("minecraft:apple"));
        old.add("items", items);
        ConstantsGenerator.Result result = generate(sources(Map.of(), java.util.Set.of(),
            version("1.0", 100, "minecraft:apple", "minecraft:egg")), old, "2.0.0");
        String source = result.files().get(ITEMS);
        // Added in the version of the run: @since is that, and the Bedrock release is its own line; the old one has no @since
        assertTrue(source.contains("     * First in Bedrock 1.0.\n     *\n     * @since 2.0.0\n     */\n    public static final BedrockItemType EGG"), source);
        assertTrue(source.contains("     * First in Bedrock 1.0.\n     */\n    public static final BedrockItemType APPLE"), source);
        assertFalse(source.contains("@since 1.0\n"), "a Bedrock release is never a @since");
        compile(result, temp);
    }

    @Test
    void handNamesAreReadFromTheirFile() throws IOException {
        List<String> committed = Files.readAllLines(ROOT.resolve(ConstantsGenerator.NAMES));
        // Without any history, the hand names alone give the names the committed history has
        ConstantsGenerator.Result fresh = ConstantsGenerator.generate(release, null,
            ConstantsGenerator.Names.of(Renames.NONE, committed, new JsonObject()), new JsonObject(), "1.0.0");
        JsonObject committedHistory = dev.opencollab.paletter.tools.Json.objectOrEmpty(ROOT.resolve(ConstantsGenerator.HISTORY));
        assertEquals(committedHistory.getAsJsonObject("states").keySet(), history(fresh).getAsJsonObject("states").keySet());
        assertEquals(committedHistory.getAsJsonObject("enums").keySet(), history(fresh).getAsJsonObject("enums").keySet());

        // A name of the file is used, wherever it comes from
        List<String> renamed = new ArrayList<>(committed);
        renamed.add("items minecraft:apple FRUIT");
        String items = ConstantsGenerator.generate(release, null, ConstantsGenerator.Names.of(Renames.NONE, renamed, new JsonObject()),
            new JsonObject(), "1.0.0").files().get(ITEMS);
        assertTrue(items.contains("BedrockItemType FRUIT = VanillaTypes.item(\"FRUIT\", \"minecraft:apple\")"));
        assertFalse(items.contains(" APPLE = "));

        // A line that matches nothing fails, and so do malformed ones
        List<String> unused = new ArrayList<>(committed);
        unused.add("blocks minecraft:nothing NOTHING");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ConstantsGenerator.generate(release, null,
            ConstantsGenerator.Names.of(Renames.NONE, unused, new JsonObject()), new JsonObject(), "1.0.0"));
        assertTrue(e.getMessage().startsWith(ConstantsGenerator.NAMES) && e.getMessage().contains("blocks minecraft:nothing"), e.getMessage());
        e = assertThrows(IllegalStateException.class, () -> ConstantsGenerator.Names.of(Renames.NONE, List.of("# c", "blocks minecraft:dirt"), new JsonObject()));
        assertEquals(ConstantsGenerator.NAMES + " line 2: expected `blocks|items|states|enums <key> <name>`, got `blocks minecraft:dirt`", e.getMessage());
        e = assertThrows(IllegalStateException.class,
            () -> ConstantsGenerator.Names.of(Renames.NONE, List.of("items minecraft:dirt A", "items minecraft:dirt B"), new JsonObject()));
        assertEquals(ConstantsGenerator.NAMES + " line 2: `items minecraft:dirt` is named twice", e.getMessage());
    }

    @Test
    void renameChainsEndAtTheFinalConstant(@TempDir Path temp) throws IOException {
        VanillaData data = version("1.0", 100, "minecraft:c");
        JsonObject old = new JsonObject();
        JsonObject items = new JsonObject();
        items.add("A", entry("minecraft:a"));
        items.add("B", entry("minecraft:b"));
        old.add("items", items);
        // a became b, which became c, in one update: both are aliases of C, not removed
        ConstantsGenerator.Names chain = new ConstantsGenerator.Names(Map.of(), Map.of(), Map.of(), Map.of(),
            new Renames(Map.of(), Map.of("minecraft:a", "minecraft:b", "minecraft:b", "minecraft:c")), java.util.Set.of());
        ConstantsGenerator.Result result = ConstantsGenerator.generate(data, null, chain, old, "2.0.0");
        assertEquals("C", itemsOf(result).getAsJsonObject("A").get("replacement").getAsString());
        assertEquals("C", itemsOf(result).getAsJsonObject("B").get("replacement").getAsString());
        assertEquals(List.of("BedrockItems.A (minecraft:a, now C)", "BedrockItems.B (minecraft:b, now C)"), result.deprecated());
        compile(result, temp.resolve("one"));

        // A was already an alias of B, and B is renamed to c now: A follows (an alias sorts before its target here)
        JsonObject aliased = new JsonObject();
        JsonObject alias = entry("minecraft:a");
        alias.addProperty("deprecated", "1.5.0");
        alias.addProperty("replacement", "B");
        JsonObject aliasItems = new JsonObject();
        aliasItems.add("A", alias);
        aliasItems.add("B", entry("minecraft:b"));
        aliased.add("items", aliasItems);
        ConstantsGenerator.Names oneStep = new ConstantsGenerator.Names(Map.of(), Map.of(), Map.of(), Map.of(),
            new Renames(Map.of(), Map.of("minecraft:b", "minecraft:c")), java.util.Set.of());
        result = ConstantsGenerator.generate(data, null, oneStep, aliased, "2.0.0");
        assertEquals("C", itemsOf(result).getAsJsonObject("A").get("replacement").getAsString());
        assertEquals("1.5.0", itemsOf(result).getAsJsonObject("A").get("deprecated").getAsString());
        compile(result, temp.resolve("two"));

        // A cycle fails clearly
        JsonObject cycle = new JsonObject();
        JsonObject a = entry("minecraft:a");
        a.addProperty("deprecated", "1.5.0");
        a.addProperty("replacement", "B");
        JsonObject b = entry("minecraft:b");
        b.addProperty("deprecated", "1.5.0");
        b.addProperty("replacement", "A");
        JsonObject cycleItems = new JsonObject();
        cycleItems.add("A", a);
        cycleItems.add("B", b);
        cycle.add("items", cycleItems);
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> ConstantsGenerator.generate(data, null, ConstantsGenerator.Names.of(Renames.NONE, List.of(), new JsonObject()), cycle, "2.0.0"));
        assertTrue(e.getMessage().contains("cycle of aliases"), e.getMessage());
    }

    /** Compiles the generated sources against Paletter's classes (the package private factory included). */
    static void compile(ConstantsGenerator.Result result, Path temp) throws IOException {
        List<String> sources = new ArrayList<>();
        for (Map.Entry<String, String> file : result.files().entrySet()) {
            if (file.getKey().endsWith(".java")) {
                Path path = temp.resolve("src").resolve(file.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, file.getValue());
                sources.add(path.toString());
            }
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<String> args = new ArrayList<>(List.of("-d", temp.resolve("classes").toString(), "-classpath", System.getProperty("java.class.path"),
            "-proc:none", "-Xlint:-options"));
        args.addAll(sources);
        java.io.ByteArrayOutputStream errors = new java.io.ByteArrayOutputStream();
        assertEquals(0, compiler.run(null, errors, errors, args.toArray(String[]::new)), "generated sources do not compile: " + errors);
    }
}
