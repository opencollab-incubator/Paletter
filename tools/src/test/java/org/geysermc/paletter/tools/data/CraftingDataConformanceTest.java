package org.geysermc.paletter.tools.data;

import com.google.gson.JsonObject;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataSource;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.recipes.CraftingData;
import org.geysermc.paletter.recipes.VanillaRecipes;
import org.geysermc.paletter.tools.client.RecipeCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The encoder against BDS: the bundled recipes of the data version of BDS 1.26.63.1, encoded for that version's palette, are
 * the committed capture of that build, apart from net ids and random UUIDs (the way {@link RecipeData#normalize} ignores them).
 * Every difference fails: recipes, mixes, their order and what each carries.
 */
class CraftingDataConformanceTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));
    private static final String BUILD = "1.26.63.1";
    private static final int PROTOCOL = 2225;

    @TempDir
    Path directory;

    @Test
    void theEncodedRecipesAreTheBdsCapture() throws IOException {
        RecipeCapture.Recipes capture = RecipeCapture.read(ROOT.resolve("data/bds").resolve(BUILD).resolve("vanilla"));
        RecipeCapture.Recipes expected = new RecipeCapture.Recipes(capture.crafting(), null);
        DataVersion version = PaletteDataSource.bundled().versions().stream().filter(candidate -> candidate.protocolVersion() == PROTOCOL).findFirst().orElseThrow();
        Palette palette = Paletter.builder().build().palette(version.protocolVersion());
        VanillaRecipes vanilla = VanillaRecipes.of(version);

        CraftingData data = CraftingData.builder(palette).recipes(vanilla.recipes()).potionMixes(vanilla.potionMixes())
            .containerMixes(vanilla.containerMixes()).build();
        assertEquals(List.of(), data.dropped());
        CraftingDataPacket packet = new CraftingDataPacket();
        data.applyTo(packet);

        Map<Integer, String> itemNames = new HashMap<>();
        for (RegisteredItem item : palette.itemDefinitions()) {
            itemNames.put(item.getRuntimeId(), item.getIdentifier());
        }
        new RecipeCapture(itemNames, runtimeId -> palette.blockDefinition(runtimeId) == null ? null : palette.blockDefinition(runtimeId).getState())
            .write(directory, packet, null, BUILD);
        RecipeCapture.Recipes actual = RecipeCapture.read(directory);

        assertEquals(capture.crafting().get("cleanRecipes"), actual.crafting().get("cleanRecipes"));
        Set<String> tagged = RecipeData.duplicateKeys(expected);
        List<String> differences = differences(RecipeData.normalize(expected, tagged), RecipeData.normalize(actual, tagged));
        assertTrue(differences.isEmpty(), differences.size() + " differences, the first ones: " + differences.subList(0, Math.min(10, differences.size())));
        assertEquals(sequence(expected), sequence(actual), "the order of the recipes");

        // The net ids are 1..n in the order of the recipes
        int netId = 1;
        for (var recipe : vanilla.recipes()) {
            assertEquals(netId++, data.netId(recipe), recipe.toString());
        }
    }

    private static List<String> differences(Map<String, Map<String, JsonObject>> expected, Map<String, Map<String, JsonObject>> actual) {
        List<String> differences = new ArrayList<>();
        for (String kind : expected.keySet().stream().sorted().toList()) {
            Map<String, JsonObject> have = actual.getOrDefault(kind, Map.of());
            expected.get(kind).forEach((key, entry) -> {
                if (!have.containsKey(key)) {
                    differences.add("missing " + kind + " " + key);
                } else if (!have.get(key).equals(entry)) {
                    differences.add("changed " + kind + " " + key + ": expected " + entry + ", was " + have.get(key));
                }
            });
            have.keySet().stream().filter(key -> !expected.get(kind).containsKey(key)).forEach(key -> differences.add("extra " + kind + " " + key));
        }
        actual.keySet().stream().filter(kind -> !expected.containsKey(kind)).forEach(kind -> differences.add("extra kind " + kind));
        return differences;
    }

    /** Every entry in wire order, without net id and random UUID. */
    private static List<String> sequence(RecipeCapture.Recipes recipes) {
        List<String> sequence = new ArrayList<>();
        RecipeData.each(recipes, (kind, entry) -> {
            JsonObject copy = entry.deepCopy();
            copy.remove("netId");
            if (!kind.equals("multi")) {
                copy.remove("uuid");
            }
            sequence.add(kind + " " + copy);
        });
        return sequence;
    }
}
