package dev.opencollab.paletter.tools.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.client.RecipeCapture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Small synthetic recipe captures: normalisation, the diff and the assumption checks. */
class RecipeDataTest {
    private static final String STICK = """
        {"id":"minecraft:stick","width":1,"height":2,"ingredients":[{"kind":"item_tag","tag":"minecraft:planks","count":1}],
         "results":[{"id":"minecraft:stick","aux":0,"count":4}],"uuid":"UUID","tag":"crafting_table","priority":0,"netId":NET,
         "assumeSymmetry":true,"unlock":{"context":"none","ingredients":[]}}""";
    private static final String TORCH = """
        {"id":"minecraft:torch","ingredients":[{"kind":"default","id":"minecraft:stick","aux":32767,"count":1}],
         "results":[{"id":"minecraft:torch","aux":0,"count":4}],"uuid":"UUID","tag":"crafting_table","priority":0,"netId":NET,
         "unlock":{"context":"none","ingredients":[]}}""";
    private static final String MULTI = "{\"uuid\":\"00000000-0000-0000-0000-000000000001\",\"netId\":NET}";
    private static final String MIX = "{\"input\":{\"id\":\"minecraft:potion\",\"aux\":4},\"reagent\":{\"id\":\"minecraft:ice_ball\",\"aux\":0},"
        + "\"output\":{\"id\":\"minecraft:potion\",\"aux\":47}}";

    private static String entry(String template, String uuid, int net) {
        return template.replace("UUID", uuid).replace("NET", String.valueOf(net));
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static String typed(String type, String entry) {
        return "{\"type\":\"" + type + "\"," + entry.strip().substring(1);
    }

    /** Post-2168 layout: a list per kind; the lists are in another order and the ids are other ones. */
    private static RecipeCapture.Recipes split(String uuidPrefix, int netShift, String... extra) {
        return new RecipeCapture.Recipes(json("{\"shapeless\":[" + entry(TORCH, uuidPrefix + "2", 2 + netShift) + "],\"shaped\":["
            + entry(STICK, uuidPrefix + "1", 1 + netShift) + "],\"multi\":[" + entry(MULTI, "", 3) + "],\"potion_mix\":[" + MIX + "]"
            + String.join("", extra) + "}"), null);
    }

    /** Pre-2168 layout: everything in one list, with its type. */
    private static RecipeCapture.Recipes legacy() {
        return new RecipeCapture.Recipes(json("{\"crafting\":[" + typed("shapeless", entry(TORCH, "x2", 20)) + "," + typed("shaped", entry(STICK, "x1", 10))
            + "," + typed("multi", entry(MULTI, "", 30)) + "],\"potion_mix\":[" + MIX + "]}"), null);
    }

    @Test
    void layoutIdsAndUuidsDoNotShowAsDifference() {
        assertTrue(Diff.recipes(legacy(), split("u", 100)).identical());
        assertEquals(List.of("minecraft:stick"), List.copyOf(RecipeData.normalize(split("u", 0)).get("shaped").keySet()));
    }

    @Test
    void detectsAddedRemovedAndChanged() {
        RecipeCapture.Recipes changed = split("u", 0);
        JsonObject shaped = changed.crafting().getAsJsonArray("shaped").get(0).getAsJsonObject();
        shaped.addProperty("priority", 5);
        shaped.getAsJsonObject("unlock").addProperty("context", "always_unlocked");
        changed.crafting().getAsJsonArray("shapeless").add(json(entry(TORCH.replace("minecraft:torch", "minecraft:lantern"), "u9", 9)));
        changed.crafting().getAsJsonArray("potion_mix").add(json(MIX.replace("\"aux\":47", "\"aux\":48")));
        changed.crafting().getAsJsonArray("multi").remove(0);
        Diff diff = Diff.recipes(split("u", 0), changed);
        assertEquals(List.of("shapeless minecraft:lantern"), diff.lines("recipes added"));
        assertEquals(List.of("multi 00000000-0000-0000-0000-000000000001"), diff.lines("recipes removed"));
        assertEquals(List.of("shaped minecraft:stick: priority"), diff.lines("recipes changed"));
        assertEquals(List.of("shaped minecraft:stick"), diff.lines("recipe unlock changes"));
        assertEquals(List.of("potion_mix minecraft:potion:4 + minecraft:ice_ball:0 + minecraft:potion:48"), diff.lines("mixes added"));
    }

    @Test
    void comparesTrims() {
        JsonObject before = json("{\"patterns\":[{\"itemName\":\"a\",\"patternId\":\"p\"}],\"materials\":[{\"materialId\":\"m\",\"color\":\"red\",\"itemName\":\"i\"}]}");
        JsonObject after = json("{\"patterns\":[{\"itemName\":\"a\",\"patternId\":\"p\"},{\"itemName\":\"b\",\"patternId\":\"q\"}],"
            + "\"materials\":[{\"materialId\":\"m\",\"color\":\"blue\",\"itemName\":\"i\"}]}");
        Diff diff = Diff.recipes(new RecipeCapture.Recipes(json("{}"), before), new RecipeCapture.Recipes(json("{}"), after));
        assertEquals(List.of("trim_pattern q"), diff.lines("trim added"));
        assertEquals(List.of("trim_material m: color"), diff.lines("trim changed"));
    }

    @Test
    void checksItemsTagsAndNetIds() {
        RecipeCapture.Recipes recipes = split("u", 0);
        // Net ids 1, 2, 3: exactly 1..n
        assertTrue(RecipeData.netIdProblems(recipes).isEmpty());
        assertFalse(RecipeData.netIdProblems(split("u", 5)).isEmpty());
        assertTrue(RecipeData.missingItems(recipes, Set.of("minecraft:stick", "minecraft:torch", "minecraft:potion", "minecraft:ice_ball")).isEmpty());
        assertEquals(List.of("minecraft:torch (shapeless minecraft:torch)"), RecipeData.missingItems(recipes, Set.of("minecraft:stick", "minecraft:potion", "minecraft:ice_ball")));
        assertTrue(RecipeData.emptyTags(recipes, Map.of("minecraft:planks", List.of("minecraft:oak_planks"))).isEmpty());
        assertEquals(List.of("minecraft:planks (shaped minecraft:stick)"), RecipeData.emptyTags(recipes, Map.of("minecraft:planks", List.of())));
    }

    private static final String BEEF = "{\"id\":\"minecraft:furnace_beef\",\"ingredients\":[{\"kind\":\"default\",\"id\":\"minecraft:beef\",\"aux\":32767,\"count\":1}],"
        + "\"results\":[{\"id\":\"minecraft:cooked_beef\",\"aux\":0,\"count\":1}],\"uuid\":\"UUID\",\"tag\":\"TAG\",\"priority\":0,\"netId\":NET,"
        + "\"unlock\":{\"context\":\"none\",\"ingredients\":[]}}";

    private static RecipeCapture.Recipes beef(String... tags) {
        List<String> entries = new java.util.ArrayList<>();
        for (String tag : tags) {
            entries.add(entry(BEEF.replace("TAG", tag), "u" + tag, entries.size() + 1));
        }
        return new RecipeCapture.Recipes(json("{\"shapeless\":[" + String.join(",", entries) + "]}"), null);
    }

    @Test
    void sameIdIsToldApartByTag() {
        assertTrue(Diff.recipes(beef("furnace", "smoker"), beef("smoker", "furnace")).identical());
        Diff diff = Diff.recipes(beef("furnace", "smoker"), beef("furnace"));
        assertEquals(List.of("shapeless minecraft:furnace_beef (smoker)"), diff.lines("recipes removed"));
        assertEquals(1, diff.count());
    }

    @Test
    void legacyFurnaceRecipes() {
        String furnace = "\"input\":{\"id\":\"minecraft:cobblestone\",\"aux\":0},\"result\":{\"id\":\"minecraft:stone\",\"aux\":0,\"count\":1},\"tag\":\"furnace\"}";
        RecipeCapture.Recipes recipes = new RecipeCapture.Recipes(json("{\"crafting\":[{\"type\":\"furnace\"," + furnace + ",{\"type\":\"furnace_data\","
            + furnace.replace("cobblestone", "coal") + "]}"), null);
        assertEquals(List.of("furnace", "furnace_data"), List.copyOf(RecipeData.normalize(recipes).keySet()));
        assertEquals(List.of("minecraft:cobblestone:0 (furnace)"), List.copyOf(RecipeData.normalize(recipes).get("furnace").keySet()));
        assertTrue(Diff.recipes(recipes, recipes).identical());
        assertEquals(List.of("furnace minecraft:cobblestone:0 (furnace)"), Diff.recipes(recipes, new RecipeCapture.Recipes(
            json("{\"crafting\":[]}"), null)).lines("recipes removed").subList(0, 1));
    }

    @Test
    void checksItemsOfMixesAndReducers() {
        RecipeCapture.Recipes recipes = new RecipeCapture.Recipes(json("{\"potion_mix\":[" + MIX.replace("minecraft:ice_ball", "unknown:77") + "],"
            + "\"material_reducer\":[{\"input\":{\"id\":\"minecraft:potion\",\"aux\":0},\"itemCounts\":[{\"id\":\"minecraft:gone\",\"count\":1}]}]}"), null);
        List<String> missing = RecipeData.missingItems(recipes, Set.of("minecraft:potion"));
        assertEquals(2, missing.size());
        assertTrue(missing.get(0).startsWith("minecraft:gone"));
        assertTrue(missing.get(1).startsWith("unknown:77"));
    }

    @Test
    void comparesCleanRecipesAndChecksBlockStates() {
        assertEquals(List.of("cleanRecipes: true -> false"), Diff.recipes(
            new RecipeCapture.Recipes(json("{\"cleanRecipes\":true}"), null), new RecipeCapture.Recipes(json("{\"cleanRecipes\":false}"), null)).lines("recipes changed"));
        RecipeCapture.Recipes recipes = new RecipeCapture.Recipes(json("{\"shaped\":[{\"id\":\"minecraft:a\",\"results\":[{\"id\":\"minecraft:a\",\"block_runtime_id\":\"5\"},"
            + "{\"id\":\"minecraft:b\",\"block_runtime_id\":\"6\",\"block_state_b64\":\"x\"}]}]}"), null);
        assertEquals(List.of("minecraft:a block 5 (shaped minecraft:a)"), RecipeData.missingBlockStates(recipes));
    }

    /**
     * The assumptions {@code update} fails on hold for every committed recipe capture against the bundled data of its version.
     */
    @Test
    void theCommittedCapturesHoldTheRecipeAssumptions() throws java.io.IOException {
        java.nio.file.Path root = java.nio.file.Path.of("..");
        int checked = 0;
        try (java.util.stream.Stream<java.nio.file.Path> builds = java.nio.file.Files.list(root.resolve("data/bds"))) {
            for (java.nio.file.Path build : builds.filter(b -> java.nio.file.Files.exists(b.resolve("vanilla").resolve(RecipeCapture.CRAFTING))).toList()) {
                String name = dev.opencollab.paletter.tools.BuildNumber.parse(build.getFileName().toString()).dataVersion();
                java.nio.file.Path bundled = root.resolve("paletter/src/main/resources/paletter").resolve(name + ".nbt");
                if (!java.nio.file.Files.exists(bundled)) {
                    continue;
                }
                dev.opencollab.paletter.internal.data.VanillaData data = BundledFiles.read(bundled);
                RecipeCapture.Recipes recipes = RecipeCapture.read(build.resolve("vanilla"));
                Set<String> items = new java.util.HashSet<>();
                data.items().forEach(item -> items.add(item.identifier()));
                List<String> problems = new java.util.ArrayList<>();
                problems.addAll(RecipeData.missingItems(recipes, items));
                problems.addAll(RecipeData.emptyTags(recipes, data.itemTags()));
                problems.addAll(RecipeData.missingBlockStates(recipes));
                problems.addAll(RecipeData.netIdProblems(recipes));
                assertEquals(List.of(), problems, build.getFileName().toString());
                checked++;
            }
        }
        assertTrue(checked > 0);
    }

    @Test
    void trimWithoutPatternsIsFine() {
        assertTrue(Diff.recipes(new RecipeCapture.Recipes(json("{}"), json("{}")), new RecipeCapture.Recipes(json("{}"), json("{\"bdsVersion\":\"x\"}"))).identical());
    }
}
