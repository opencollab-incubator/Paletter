package org.geysermc.paletter.tools.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.tools.client.RecipeCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The recipe bundle generator: a synthetic capture, the committed bundles against the committed captures. */
class RecipeImportTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));
    private static final String CAPTURE = """
        {"shaped":[{"id":"minecraft:stick","width":1,"height":2,"ingredients":[{"kind":"item_tag","tag":"minecraft:planks","count":1},{"kind":"invalid","count":0}],
          "results":[{"id":"minecraft:stick","aux":0,"count":4}],"uuid":"random","tag":"crafting_table","priority":3,"netId":9,
          "assumeSymmetry":true,"unlock":{"context":"none","ingredients":[{"kind":"complex_alias","name":"minecraft:log","count":1}]}}],
         "shapeless":[{"id":"minecraft:torch","ingredients":[{"kind":"default","id":"minecraft:stick","aux":32767,"count":2}],
          "results":[{"id":"minecraft:torch","aux":0,"count":4}],"uuid":"random","tag":"furnace","priority":0,"netId":10,
          "unlock":{"context":"none","ingredients":[]}}],
         "multi":[{"uuid":"00000000-0000-0000-0000-000000000001","netId":11}],
         "potion_mix":[{"input":{"id":"minecraft:potion","aux":4},"reagent":{"id":"minecraft:stick","aux":0},"output":{"id":"minecraft:potion","aux":47}}],
         "container_mix":[{"input":{"id":"minecraft:potion","aux":0},"reagent":{"id":"minecraft:gunpowder","aux":0},"output":{"id":"minecraft:splash_potion","aux":0}}]}""";
    private static final Set<String> ITEMS = Set.of("minecraft:stick", "minecraft:torch", "minecraft:potion", "minecraft:gunpowder",
        "minecraft:splash_potion", "minecraft:ward_armor_trim_smithing_template", "minecraft:quartz", "minecraft:diamond_axe",
        "minecraft:netherite_upgrade_smithing_template", "minecraft:netherite_ingot", "minecraft:netherite_axe", "minecraft:ink_sac",
        "minecraft:bundle", "minecraft:black_bundle", "minecraft:barrel");
    private static final String TRIM = """
        {"patterns":[{"itemName":"minecraft:ward_armor_trim_smithing_template","patternId":"ward"}],
         "materials":[{"materialId":"quartz","color":"§h","itemName":"minecraft:quartz"}]}""";

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void theBundleHoldsTheRecipesWithoutNetIdsAndRandomUuids() {
        NbtMap bundle = bundleOf(CAPTURE);
        List<NbtMap> recipes = bundle.getList("recipes", NbtType.COMPOUND);
        assertEquals(List.of("shaped", "shapeless", "multi"), recipes.stream().map(recipe -> recipe.getString("type")).toList());
        NbtMap shaped = recipes.get(0);
        assertEquals(List.of(NbtMap.builder().putString("tag", "minecraft:planks").putInt("count", 1).build(), NbtMap.EMPTY),
            shaped.getList("ingredients", NbtType.COMPOUND));
        assertEquals("crafting_table", shaped.getString("station"));
        assertTrue(shaped.getBoolean("symmetry"));
        assertEquals("minecraft:log", shaped.getCompound("unlock").getList("ingredients", NbtType.COMPOUND).getFirst().getString("alias"));
        assertEquals(32767, recipes.get(1).getList("ingredients", NbtType.COMPOUND).getFirst().getInt("aux"));
        assertEquals("00000000-0000-0000-0000-000000000001", recipes.get(2).getString("id"));
        for (NbtMap recipe : recipes) {
            assertFalse(recipe.containsKey("netId") || recipe.containsKey("uuid") && !recipe.getString("type").equals("multi"), recipe.toString());
        }
        assertEquals("minecraft:splash_potion", bundle.getList("container_mixes", NbtType.COMPOUND).getFirst().getString("output"));
        assertEquals("quartz", bundle.getList("trim_materials", NbtType.COMPOUND).getFirst().getString("id"));
    }

    @Test
    void anUnknownDescriptorFailsTheImport() {
        String molang = CAPTURE.replace("\"kind\":\"invalid\",\"count\":0", "\"kind\":\"molang\",\"expression\":\"q\",\"version\":1,\"count\":1");
        var capture = new RecipeCapture.Recipes(json(molang), json(TRIM));
        assertThrows(IllegalStateException.class, () -> RecipeImport.bundle(capture, ITEMS));
    }

    @Test
    void theBundledFilesAreWhatTheCapturesGive() throws IOException {
        assertEquals(List.of(), RecipeImport.check(ROOT));
    }

    private static final String STICK = """
        {"id":"minecraft:stick","width":1,"height":2,"ingredients":[{"kind":"item_tag","tag":"minecraft:planks","count":1}],
         "results":[{"id":"minecraft:stick","aux":0,"count":4}],"uuid":"u1","tag":"crafting_table","priority":0,"netId":1,
         "assumeSymmetry":true,"unlock":{"context":"none","ingredients":[]}}""";
    private static final String TORCH = """
        {"id":"minecraft:torch","ingredients":[{"kind":"default","id":"minecraft:stick","aux":32767,"count":1}],
         "results":[{"id":"minecraft:torch","aux":0,"count":4}],"uuid":"u2","tag":"crafting_table","priority":0,"netId":2,
         "unlock":{"context":"none","ingredients":[]}}""";
    private static final String SMITHING = """
        {"smithing_transform":[{"id":"minecraft:smithing_netherite_axe",
          "template":{"kind":"default","id":"minecraft:netherite_upgrade_smithing_template","aux":32767,"count":1},
          "base":{"kind":"default","id":"minecraft:diamond_axe","aux":32767,"count":1},
          "addition":{"kind":"default","id":"minecraft:netherite_ingot","aux":32767,"count":1},
          "result":{"id":"minecraft:netherite_axe","aux":0,"count":1},"tag":"smithing_table","netId":1}],
         "smithing_trim":[{"id":"minecraft:smithing_armor_trim","template":{"kind":"item_tag","tag":"minecraft:trim_templates","count":1},
          "base":{"kind":"item_tag","tag":"minecraft:trimmable_armors","count":1},"addition":{"kind":"item_tag","tag":"minecraft:trim_materials","count":1},
          "tag":"smithing_table","netId":2}],
         "shulker_box":[{"id":"bundle_dye_0","ingredients":[{"kind":"default","id":"minecraft:bundle","aux":32767,"count":1},
          {"kind":"default","id":"minecraft:ink_sac","aux":32767,"count":1}],"results":[{"id":"minecraft:black_bundle","aux":0,"count":1}],
          "uuid":"u","tag":"crafting_table","priority":0,"netId":3,"unlock":{"context":"none","ingredients":[]}}]}""";

    private static NbtMap bundleOf(String crafting) {
        return RecipeImport.bundle(new RecipeCapture.Recipes(json(crafting), json(TRIM)), ITEMS);
    }

    @Test
    void smithingAndShulkerBoxRecipesKeepTheirKindsInTheBundleOrder() {
        List<NbtMap> recipes = bundleOf(SMITHING).getList("recipes", NbtType.COMPOUND);
        assertEquals(List.of("shulker_box", "smithing_transform", "smithing_trim"), recipes.stream().map(recipe -> recipe.getString("type")).toList());
        NbtMap transform = recipes.get(1);
        assertEquals("minecraft:diamond_axe", transform.getCompound("base").getString("item"));
        assertEquals("minecraft:netherite_axe", transform.getCompound("result").getString("id"));
        assertEquals("smithing_table", transform.getString("station"));
        NbtMap trim = recipes.get(2);
        assertEquals("minecraft:trimmable_armors", trim.getCompound("base").getString("tag"));
        assertFalse(trim.containsKey("result"));
    }

    @Test
    void theOldCraftingListGivesTheSameBundleAsTheSplitLists() {
        String legacy = "{\"crafting\":[{\"type\":\"shapeless\"," + TORCH.strip().substring(1) + ",{\"type\":\"shaped\"," + STICK.strip().substring(1) + "]}";
        String split = "{\"shaped\":[" + STICK + "],\"shapeless\":[" + TORCH + "]}";
        assertEquals(bundleOf(split), bundleOf(legacy));
    }

    @Test
    void resultsKeepTheirBlockStateAndTag() throws IOException {
        String block = Nbt.writeLeBase64(NbtMap.builder().putString("name", "minecraft:barrel").build());
        String tag = Nbt.writeLeBase64(NbtMap.builder().putInt("Damage", 3).build());
        String crafting = "{\"shapeless\":[" + TORCH.replace("\"aux\":0,\"count\":4}", "\"aux\":0,\"count\":4,\"block_runtime_id\":\"7\","
            + "\"block_state_b64\":\"" + block + "\",\"nbt_b64\":\"" + tag + "\"}") + "]}";
        NbtMap result = bundleOf(crafting).getList("recipes", NbtType.COMPOUND).getFirst().getList("results", NbtType.COMPOUND).getFirst();
        assertEquals("minecraft:barrel", result.getCompound("block").getString("name"));
        assertEquals(3, result.getCompound("nbt").getInt("Damage"));
        assertFalse(result.containsKey("block_runtime_id"));
    }

    private static void assertImportFails(String capture, String trim) {
        assertThrows(RuntimeException.class, () -> RecipeImport.bundle(new RecipeCapture.Recipes(json(capture), json(trim)), ITEMS));
    }

    @Test
    void whatTheLoaderCouldOnlyGuessFailsTheImport() throws IOException {
        assertImportFails(CAPTURE.replace("\"reagent\":{\"id\":\"minecraft:gunpowder\",\"aux\":0}", "\"reagent\":{\"id\":\"minecraft:gunpowder\",\"aux\":1}"), TRIM);
        assertImportFails(CAPTURE.replace("\"context\":\"none\"", "\"context\":\"sometimes\""), TRIM);
        assertImportFails(CAPTURE.replace("00000000-0000-0000-0000-000000000001", "not-a-uuid"), TRIM);
        assertImportFails(CAPTURE.replace("minecraft:splash_potion", "minecraft:not_an_item"), TRIM);
        assertImportFails(CAPTURE, TRIM.replace("minecraft:quartz", "minecraft:not_an_item"));
        String emptyTag = Nbt.writeLeBase64(NbtMap.EMPTY);
        assertImportFails(CAPTURE.replace("\"aux\":0,\"count\":4}", "\"aux\":0,\"count\":4,\"nbt_b64\":\"" + emptyTag + "\"}"), TRIM);
        assertImportFails(CAPTURE.replace("\"aux\":0,\"count\":4}", "\"aux\":0,\"count\":4,\"block_state_b64\":\"" + emptyTag + "\"}"), TRIM);
    }

    /** A repository root with one data version (the real 26.30 data) and, if asked, one recipe capture. */
    private static Path root(Path directory, boolean withCapture) throws IOException {
        Path data = directory.resolve("paletter/src/main/resources/paletter");
        Files.createDirectories(data);
        Files.copy(ROOT.resolve("paletter/src/main/resources/paletter/26.30.nbt"), data.resolve("26.30.nbt"));
        Files.writeString(data.resolve("versions.txt"), "26.30 1001\n");
        Path build = directory.resolve("data/bds/1.26.36.1");
        Files.createDirectories(build);
        if (withCapture) {
            Files.createDirectories(build.resolve("vanilla"));
            Files.writeString(build.resolve("experiments.json"), "{\"protocol\":1001,\"toggles\":{}}");
            Files.writeString(build.resolve("vanilla/trim_data.json"), TRIM);
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(build.resolve("vanilla/crafting_data.json.gz")))) {
                out.write(CAPTURE.getBytes(StandardCharsets.UTF_8));
            }
        }
        return directory;
    }

    @Test
    void aVersionWithoutACaptureFailsTheImport(@TempDir Path directory) throws IOException {
        Path root = root(directory, false);
        assertThrows(IOException.class, () -> RecipeImport.run(root));
        assertThrows(IOException.class, () -> RecipeImport.check(root));
    }

    @Test
    void checkReportsMissingStaleAndChangedFilesButNotOtherCompressionOfTheSameData(@TempDir Path directory) throws IOException {
        Path root = root(directory, true);
        Path file = root.resolve(RecipeImport.DIRECTORY).resolve("26.30.nbt");
        assertEquals(List.of("26.30.nbt is missing or not what the captures give", "RecipeStations.java is missing or not what the captures give"),
            RecipeImport.check(root));
        RecipeImport.run(root);
        assertEquals(List.of(), RecipeImport.check(root));

        // The same data, compressed differently
        NbtMap data = Nbt.readGzip(file);
        try (var out = new GZIPOutputStream(Files.newOutputStream(file)) {
            { def.setLevel(java.util.zip.Deflater.BEST_SPEED); }
        }; var nbt = org.cloudburstmc.nbt.NbtUtils.createWriter(out)) {
            nbt.writeTag(data);
        }
        assertEquals(List.of(), RecipeImport.check(root));

        Files.write(root.resolve(RecipeImport.DIRECTORY).resolve("99.99.nbt"), new byte[] {1});
        assertEquals(List.of("99.99.nbt is not a bundled data version"), RecipeImport.check(root));
        RecipeImport.run(root);
        assertFalse(Files.exists(root.resolve(RecipeImport.DIRECTORY).resolve("99.99.nbt")));

        NbtMap changed = Nbt.readGzip(file).toBuilder().putString("_generated", "edited by hand").build();
        try (var nbt = org.cloudburstmc.nbt.NbtUtils.createGZIPWriter(Files.newOutputStream(file))) {
            nbt.writeTag(changed);
        }
        assertEquals(List.of("26.30.nbt is missing or not what the captures give"), RecipeImport.check(root));
    }

    @Test
    void stationsBecomeSortedConstants() {
        String source = RecipeImport.stationsSource(new java.util.TreeSet<>(List.of("crafting_table", "blast_furnace")));
        assertTrue(source.startsWith("// Generated by "));
        assertTrue(source.contains("public static final String BLAST_FURNACE = \"blast_furnace\";"));
        assertTrue(source.indexOf("BLAST_FURNACE") < source.indexOf("CRAFTING_TABLE"));
    }

    @Test
    void stationsAreThoseOfTheRecipesOfTheCaptures(@TempDir Path directory) throws IOException {
        assertEquals(Set.of("crafting_table", "furnace"), RecipeImport.stations(root(directory, true)));
    }
}
