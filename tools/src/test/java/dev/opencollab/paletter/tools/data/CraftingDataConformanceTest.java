package dev.opencollab.paletter.tools.data;

import com.google.gson.JsonObject;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.data.PaletteDataSource;
import dev.opencollab.paletter.item.RegisteredItem;
import dev.opencollab.paletter.recipes.CraftingData;
import dev.opencollab.paletter.recipes.VanillaRecipes;
import dev.opencollab.paletter.tools.client.RecipeCapture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The encoder against BDS: the bundled recipes of each data version, encoded for that version's palette, are the committed
 * capture of its newest build (1.26.36.1, 1.26.45.1, 1.26.52.3 or 1.26.63.1, 1.26.70.24), apart from net ids and random UUIDs (the way {@link RecipeData#normalize} ignores them).
 * Every difference fails: recipes, mixes, and what each carries. The order too, except for protocols below 2168: BDS
 * interleaves the recipe kinds in their single list, and the encoder sends the order of the bundle.
 */
class CraftingDataConformanceTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));
    /** The first protocol with a list per recipe kind. */
    private static final int SPLIT_LISTS_FROM = 2168;
    /**
     * Versions whose committed capture is known to be stale: the 1.26.70.24 capture could not resolve the block states of
     * the results that place the blocks new in 26.70 (it used 26.60's palette: they are only a {@code block_runtime_id}
     * without a state, where the encoder, with the palette of 26.70, writes the state). The capture code is fixed, the
     * committed file updates with the recapture. Until then these differences, and only these, are expected; the
     * version fails the moment there are none, so this entry has to go with the recapture.
     */
    private static final Set<String> PENDING_RECAPTURE = Set.of("26.70");

    @TempDir
    Path directory;

    static List<DataVersion> versions() {
        return PaletteDataSource.bundled().versions();
    }

    @ParameterizedTest
    @MethodSource("versions")
    void theEncodedRecipesAreTheBdsCapture(DataVersion version) throws IOException {
        RecipeCapture.Recipes capture = RecipeCapture.read(RecipeImport.captures(ROOT).get(version.name()));
        String build = capture.crafting().get("bdsVersion").getAsString();
        RecipeCapture.Recipes expected = new RecipeCapture.Recipes(capture.crafting(), null);
        Palette palette = Paletter.builder().previews(version.isPreview()).build().palette(version.protocolVersion());
        VanillaRecipes vanilla = VanillaRecipes.of(version);

        CraftingData data = CraftingData.builder(palette).recipes(vanilla.recipes()).potionMixes(vanilla.potionMixes())
            .containerMixes(vanilla.containerMixes()).build();
        assertEquals(List.of(), data.dropped());
        CraftingDataPacket packet = new CraftingDataPacket();
        data.applyTo(packet);
        boolean legacy = version.protocolVersion() < SPLIT_LISTS_FROM;
        if (legacy) {
            // Written like the capture of such a build: the one list, with a type per recipe
            packet.getShapedData().clear();
            packet.getShapelessData().clear();
            packet.getShapelessUserData().clear();
            packet.getMultiData().clear();
            packet.getSmithingTransformData().clear();
            packet.getSmithingTrimData().clear();
        }

        Map<Integer, String> itemNames = new HashMap<>();
        for (RegisteredItem item : palette.itemDefinitions()) {
            itemNames.put(item.getRuntimeId(), item.getIdentifier());
        }
        new RecipeCapture(itemNames, runtimeId -> palette.blockDefinition(runtimeId) == null ? null : palette.blockDefinition(runtimeId).getState())
            .write(directory, packet, null, build);
        RecipeCapture.Recipes actual = RecipeCapture.read(directory);

        assertEquals(capture.crafting().get("cleanRecipes"), actual.crafting().get("cleanRecipes"));
        Set<String> tagged = RecipeData.duplicateKeys(expected);
        List<String> differences = differences(RecipeData.normalize(expected, tagged), RecipeData.normalize(actual, tagged));
        if (PENDING_RECAPTURE.contains(version.name())) {
            assertFalse(differences.isEmpty(), "the capture of " + version + " is current: remove it from PENDING_RECAPTURE");
            expected = withoutUnresolvedStates(expected);
            List<String> others = differences(RecipeData.normalize(expected, tagged), RecipeData.normalize(actual, tagged));
            assertTrue(others.isEmpty(), others.size() + " differences besides the stale capture, the first ones: " + others.subList(0, Math.min(10, others.size())));
        } else {
            assertTrue(differences.isEmpty(), differences.size() + " differences, the first ones: " + differences.subList(0, Math.min(10, differences.size())));
        }
        if (!legacy) {
            assertEquals(sequence(expected), sequence(actual), "the order of the recipes");
        }

        // The net ids are 1..n in the order of the recipes
        int netId = 1;
        for (var recipe : vanilla.recipes()) {
            assertEquals(netId++, data.netId(recipe), recipe.toString());
        }
    }

    /** The capture as a recapture with the palette of its version would not leave it: a result block without its state has no runtime id either. */
    private static RecipeCapture.Recipes withoutUnresolvedStates(RecipeCapture.Recipes recipes) {
        RecipeCapture.Recipes copy = new RecipeCapture.Recipes(recipes.crafting().deepCopy(), null);
        RecipeData.each(copy, (kind, entry) -> {
            if (entry.has("results")) {
                for (var result : entry.getAsJsonArray("results")) {
                    JsonObject json = result.getAsJsonObject();
                    if (json.has("block_runtime_id") && !json.has("block_state_b64")) {
                        json.remove("block_runtime_id");
                    }
                }
            }
        });
        return copy;
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
