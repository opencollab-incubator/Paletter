package org.geysermc.paletter.tools.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.tools.client.RecipeCapture;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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
          "assumeSymmetry":true,"unlock":{"context":"always_unlocked","ingredients":[{"kind":"complex_alias","name":"minecraft:log","count":1}]}}],
         "shapeless":[{"id":"minecraft:torch","ingredients":[{"kind":"default","id":"minecraft:stick","aux":32767,"count":2}],
          "results":[{"id":"minecraft:torch","aux":0,"count":4}],"uuid":"random","tag":"furnace","priority":0,"netId":10,
          "unlock":{"context":"none","ingredients":[]}}],
         "multi":[{"uuid":"00000000-0000-0000-0000-000000000001","netId":11}],
         "potion_mix":[{"input":{"id":"minecraft:potion","aux":4},"reagent":{"id":"minecraft:ice_ball","aux":0},"output":{"id":"minecraft:potion","aux":47}}],
         "container_mix":[{"input":{"id":"minecraft:potion","aux":0},"reagent":{"id":"minecraft:gunpowder","aux":0},"output":{"id":"minecraft:splash_potion","aux":0}}]}""";
    private static final String TRIM = """
        {"patterns":[{"itemName":"minecraft:ward_armor_trim_smithing_template","patternId":"ward"}],
         "materials":[{"materialId":"quartz","color":"§h","itemName":"minecraft:quartz"}]}""";

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static NbtMap bundle(String experiments) {
        return RecipeImport.bundle(new RecipeCapture.Recipes(json(CAPTURE), json(TRIM)), json(experiments));
    }

    @Test
    void theBundleHoldsTheRecipesWithoutNetIdsAndRandomUuids() {
        NbtMap bundle = bundle("{\"toggles\":{}}");
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
        assertFalse(bundle.containsKey("experiments"));
    }

    @Test
    void experimentMarksComeFromTheRecordsRecipes() {
        NbtMap marks = bundle("""
            {"toggles":{"a":{"status":"current","content":{"recipes":{"recipes added":["shaped minecraft:new"],
              "mixes removed":["potion_mix minecraft:potion:4 + x"],"recipe unlock changes":["shaped minecraft:stick"]}}},
              "b":{"status":"current","content":{}},"c":{"status":"retired"}}}""").getCompound("experiments");
        assertEquals(List.of("a"), List.copyOf(marks.keySet()));
        NbtMap a = marks.getCompound("a");
        assertEquals(List.of("shaped minecraft:new"), a.getList("added", NbtType.STRING));
        assertEquals(List.of("potion_mix minecraft:potion:4 + x"), a.getList("removed", NbtType.STRING));
        assertEquals(List.of("shaped minecraft:stick: unlock"), a.getList("changed", NbtType.STRING));
    }

    @Test
    void anUnknownDescriptorFailsTheImport() {
        String molang = CAPTURE.replace("\"kind\":\"invalid\",\"count\":0", "\"kind\":\"molang\",\"expression\":\"q\",\"version\":1,\"count\":1");
        var capture = new RecipeCapture.Recipes(json(molang), json(TRIM));
        assertThrows(IllegalStateException.class, () -> RecipeImport.bundle(capture, json("{\"toggles\":{}}")));
    }

    @Test
    void theBundledFilesAreWhatTheCapturesGive() throws IOException {
        assertEquals(List.of(), RecipeImport.check(ROOT));
    }
}
