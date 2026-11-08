package org.geysermc.paletter.codegen.constants;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataCodec;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.data.VanillaItem;
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
    private static JsonObject names;

    @BeforeAll
    static void load() throws IOException {
        List<ConstantsGenerator.Bundle> bundles = ConstantsGenerator.readIndex(ROOT.resolve(ConstantsGenerator.DATA));
        String name = bundles.stream().filter(b -> !b.preview()).reduce((a, b) -> b).orElseThrow().name();
        try (InputStream stream = Files.newInputStream(ROOT.resolve(ConstantsGenerator.DATA).resolve(name + ".nbt"))) {
            release = PaletteDataCodec.read(stream);
        }
        names = ConstantsGenerator.readJson(ROOT.resolve(ConstantsGenerator.NAMES));
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
        first.getAsJsonObject("items").add("REMOVED_TO_STICK", entry("minecraft:removed_to_stick"));
        JsonObject state = new JsonObject();
        state.addProperty("name", "removed_state");
        state.addProperty("kind", "bool");
        state.addProperty("since", "1.0.0");
        first.getAsJsonObject("states").add("REMOVED_STATE", state);
        JsonObject value = new JsonObject();
        value.addProperty("value", "upwards");
        value.addProperty("since", "1.0.0");
        first.getAsJsonObject("enums").getAsJsonObject("CardinalDirection").getAsJsonObject("values").add("UPWARDS", value);

        JsonObject names = this.names.deepCopy();
        names.getAsJsonObject("renamed").getAsJsonObject("items").addProperty("minecraft:old_item", "minecraft:apple");
        names.getAsJsonObject("fallbacks").getAsJsonObject("items").addProperty("minecraft:removed_to_stick", "minecraft:stick");

        ConstantsGenerator.Result second = ConstantsGenerator.generate(release, null, names, first, "2.1.0");
        String blocks = second.files().get(BLOCKS);
        assertTrue(blocks.contains("""
                @Deprecated(since = "2.1.0", forRemoval = true)
                public static final BedrockBlockType REMOVED_BLOCK = VanillaTypes.block("minecraft:removed_block");
            """), blocks.substring(0, 200));
        assertTrue(blocks.contains("@deprecated Bedrock removed the block; resolves to {@code minecraft:unknown}"));
        assertTrue(blocks.contains("""
                @Deprecated(since = "2.1.0", forRemoval = true)
                public static final BedrockBlockType OLD_STONE_NAME = STONE;
            """));
        assertTrue(blocks.contains("""
                 * @since 2.1.0
                 */
                public static final BedrockBlockType STONE = VanillaTypes.block("minecraft:stone");
            """));
        String items = second.files().get(ITEMS);
        assertTrue(items.contains("public static final BedrockItemType OLD_ITEM = APPLE;"));
        assertTrue(items.contains("@deprecated Bedrock renamed it to {@link #APPLE}"));
        assertTrue(items.contains("REMOVED_TO_STICK = VanillaTypes.item(\"minecraft:removed_to_stick\", \"minecraft:stick\");"));
        String states = second.files().get(STATES);
        assertTrue(states.contains("""
                @Deprecated(since = "2.1.0", forRemoval = true)
                public static final BoolState REMOVED_STATE = VanillaTypes.state(BoolState.of("removed_state"));
            """));
        String cardinal = second.files().get(ConstantsGenerator.STATE_DIR + "/CardinalDirection.java");
        assertTrue(cardinal.contains("    @Deprecated(since = \"2.1.0\", forRemoval = true)\n    UPWARDS(\"upwards\")"), cardinal);

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
        assertEquals("CardinalDirection", ConstantsGenerator.className("minecraft:cardinal_direction"));
        assertEquals("WallConnectionType", ConstantsGenerator.className("wall_connection_type_east"));

        JsonObject clash = names.deepCopy();
        clash.getAsJsonObject("blocks").addProperty("minecraft:dirt", "STONE");
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> ConstantsGenerator.generate(release, null, clash, new JsonObject(), "1.0.0"));
        assertTrue(e.getMessage().contains("Duplicate constant BedrockBlocks.STONE"), e.getMessage());

        JsonObject noOverride = names.deepCopy();
        noOverride.getAsJsonObject("states").remove("minecraft:facing_direction");
        e = assertThrows(IllegalStateException.class, () -> ConstantsGenerator.generate(release, null, noOverride, new JsonObject(), "1.0.0"));
        assertTrue(e.getMessage().contains("Duplicate constant BedrockStates.FACING_DIRECTION"), e.getMessage());

        JsonObject invalid = names.deepCopy();
        invalid.getAsJsonObject("items").addProperty("minecraft:apple", "1APPLE");
        assertThrows(IllegalStateException.class, () -> ConstantsGenerator.generate(release, null, invalid, new JsonObject(), "1.0.0"));
    }

    @Test
    void previewContentGetsItsOwnClasses(@TempDir Path temp) throws IOException {
        List<NbtMap> states = new ArrayList<>(release.blockStates());
        states.add(BlockStates.of("minecraft:preview_block", Map.of("lit", (byte) 0)));
        states.add(BlockStates.of("minecraft:preview_block", Map.of("lit", (byte) 1)));
        List<VanillaItem> items = new ArrayList<>(release.items());
        items.add(new VanillaItem("minecraft:preview_item", 30000, 0, false, null));
        VanillaData preview = new VanillaData(new DataVersion("26.70", 9999), states, items, release.dataDrivenBlocks(), release.creative(),
            release.itemTags(), release.fallbacks(), release.blockDefaults());

        ConstantsGenerator.Result result = ConstantsGenerator.generate(release, preview, names, new JsonObject(), "1.0.0");
        String blocks = result.files().get(ConstantsGenerator.PACKAGE_DIR + "/BedrockPreviewBlocks.java");
        assertTrue(blocks.contains("@Experimental\npublic final class BedrockPreviewBlocks"));
        assertTrue(blocks.contains("public static final BedrockBlockType PREVIEW_BLOCK = VanillaTypes.block(\"minecraft:preview_block\");"));
        assertFalse(blocks.contains(" STONE "));
        String previewItems = result.files().get(ConstantsGenerator.PACKAGE_DIR + "/BedrockPreviewItems.java");
        assertTrue(previewItems.contains("PREVIEW_ITEM = VanillaTypes.item(\"minecraft:preview_item\");"));
        assertFalse(result.files().get(BLOCKS).contains("PREVIEW_BLOCK"));
        // Previews are not part of the history: they have no compatibility promise
        assertNull(history(result).getAsJsonObject("blocks").get("PREVIEW_BLOCK"));
        compile(result, temp);
    }

    /** Compiles the generated sources against Paletter's classes (the package private factory included). */
    private static void compile(ConstantsGenerator.Result result, Path temp) throws IOException {
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
