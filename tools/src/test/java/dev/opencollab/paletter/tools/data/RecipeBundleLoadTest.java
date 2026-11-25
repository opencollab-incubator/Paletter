package dev.opencollab.paletter.tools.data;

import com.google.gson.JsonObject;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.RecipeUnlockingRequirement.UnlockingContext;
import dev.opencollab.paletter.Lookup;
import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.data.PaletteDataSource;
import dev.opencollab.paletter.recipes.Ingredient;
import dev.opencollab.paletter.recipes.Recipe;
import dev.opencollab.paletter.recipes.RecipeStations;
import dev.opencollab.paletter.recipes.Result;
import dev.opencollab.paletter.recipes.VanillaRecipes;
import dev.opencollab.paletter.tools.client.RecipeCapture;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The loader reads what the generator wrote: every bundled version against its capture. */
class RecipeBundleLoadTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));
    private static final List<DataVersion> VERSIONS = PaletteDataSource.bundled().versions();

    private static RecipeCapture.Recipes capture(DataVersion version) throws IOException {
        return RecipeCapture.read(RecipeImport.captures(ROOT).get(version.name()));
    }

    private static List<Recipe> recipes(DataVersion version) {
        return VanillaRecipes.of(version).recipes();
    }

    @Test
    void everyVersionHasAsManyRecipesMixesAndTrimsAsItsCapture() throws IOException {
        Map<String, NbtMap> bundles = RecipeImport.bundles(ROOT);
        for (DataVersion version : VERSIONS) {
            RecipeCapture.Recipes capture = capture(version);
            VanillaRecipes loaded = VanillaRecipes.of(version);
            int expected = 0;
            for (String kind : List.of("shaped", "shapeless", "shulker_box", "multi", "smithing_transform", "smithing_trim")) {
                int[] count = {0};
                RecipeData.each(capture, (entryKind, entry) -> count[0] += entryKind.equals(kind) ? 1 : 0);
                expected += count[0];
            }
            String name = version.toString();
            assertEquals(expected, loaded.recipes().size(), name);
            assertEquals(capture.count("potion_mix"), loaded.potionMixes().size(), name);
            assertEquals(capture.count("container_mix"), loaded.containerMixes().size(), name);
            NbtMap bundled = bundles.get(version.name());
            assertEquals(capture.trim().getAsJsonArray("patterns").size(), bundled.getList("trim_patterns", NbtType.COMPOUND).size(), name);
            assertEquals(capture.trim().getAsJsonArray("materials").size(), bundled.getList("trim_materials", NbtType.COMPOUND).size(), name);
            long shulker = 0;
            for (Recipe recipe : loaded.recipes()) {
                shulker += recipe instanceof Recipe.Shapeless shapeless && shapeless.shulkerBox() ? 1 : 0;
            }
            int[] captured = {0};
            RecipeData.each(capture, (kind, entry) -> captured[0] += kind.equals("shulker_box") ? 1 : 0);
            assertEquals(captured[0], shulker, name);
        }
    }

    @Test
    void everyUnlockContextIsParsed() throws IOException {
        for (DataVersion version : VERSIONS) {
            Map<UnlockingContext, Integer> expected = new EnumMap<>(UnlockingContext.class);
            RecipeData.each(capture(version), (kind, entry) -> {
                JsonObject unlock = entry.has("unlock") ? entry.getAsJsonObject("unlock") : null;
                if (unlock != null) {
                    expected.merge(UnlockingContext.valueOf(unlock.get("context").getAsString().toUpperCase(Locale.ROOT)), 1, Integer::sum);
                }
            });
            Map<UnlockingContext, Integer> loaded = new EnumMap<>(UnlockingContext.class);
            for (Recipe recipe : recipes(version)) {
                switch (recipe) {
                    case Recipe.Shaped shaped -> loaded.merge(shaped.unlock().context(), 1, Integer::sum);
                    case Recipe.Shapeless shapeless -> loaded.merge(shapeless.unlock().context(), 1, Integer::sum);
                    default -> { }
                }
            }
            assertEquals(expected, loaded, version.toString());
            assertTrue(loaded.size() > 1, version.toString());
        }
    }

    @Test
    void everyStationIsAConstantOfRecipeStations() throws ReflectiveOperationException {
        Set<String> constants = new HashSet<>();
        for (var field : RecipeStations.class.getFields()) {
            constants.add((String) field.get(null));
        }
        for (DataVersion version : VERSIONS) {
            for (Recipe recipe : recipes(version)) {
                String station = switch (recipe) {
                    case Recipe.Shaped shaped -> shaped.station();
                    case Recipe.Shapeless shapeless -> shapeless.station();
                    case Recipe.SmithingTransform transform -> transform.station();
                    case Recipe.SmithingTrim trim -> trim.station();
                    case Recipe.Multi multi -> null;
                    default -> throw new AssertionError(recipe);
                };
                assertTrue(station == null || constants.contains(station), station + " in " + version);
            }
        }
    }

    @Test
    void aliasesLoadWhereTheCaptureHasThem() {
        for (DataVersion version : VERSIONS) {
            boolean alias = ingredients(version).anyMatch(ingredient -> ingredient instanceof Ingredient.Alias);
            assertEquals(version.name().equals("26.30"), alias, version.toString());
        }
        Ingredient.Alias first = ingredients(VERSIONS.getFirst()).filter(ingredient -> ingredient instanceof Ingredient.Alias)
            .map(ingredient -> (Ingredient.Alias) ingredient).findFirst().orElseThrow();
        assertTrue(first.name().startsWith("minecraft:") && first.count() > 0);
    }

    private static Stream<Ingredient> ingredients(DataVersion version) {
        List<Ingredient> all = new ArrayList<>();
        for (Recipe recipe : recipes(version)) {
            switch (recipe) {
                case Recipe.Shaped shaped -> {
                    all.addAll(shaped.ingredients());
                    all.addAll(shaped.unlock().ingredients());
                }
                case Recipe.Shapeless shapeless -> {
                    all.addAll(shapeless.ingredients());
                    all.addAll(shapeless.unlock().ingredients());
                }
                case Recipe.SmithingTransform transform -> all.addAll(List.of(transform.template(), transform.base(), transform.addition()));
                case Recipe.SmithingTrim trim -> all.addAll(List.of(trim.template(), trim.base(), trim.addition()));
                case Recipe.Multi multi -> { }
                default -> throw new AssertionError(recipe);
            }
        }
        return all.stream();
    }

    @Test
    void everyItemOfEveryVersionResolvesInThePaletteOfThatVersion() {
        for (DataVersion version : VERSIONS) {
            Palette palette = Paletter.builder().allExperiments().previews(version.isPreview()).build().palette(version.protocolVersion());
            VanillaRecipes loaded = VanillaRecipes.of(version);
            List<BedrockItemVariant> keys = new ArrayList<>();
            ingredients(version).forEach(ingredient -> {
                if (ingredient instanceof Ingredient.Item item) {
                    keys.add(item.item());
                } else if (ingredient instanceof Ingredient.AnyVariant any) {
                    keys.add(any.type().variant());
                }
            });
            for (Recipe recipe : loaded.recipes()) {
                List<Result> results = switch (recipe) {
                    case Recipe.Shaped shaped -> shaped.results();
                    case Recipe.Shapeless shapeless -> shapeless.results();
                    case Recipe.SmithingTransform transform -> List.of(transform.result());
                    default -> List.of();
                };
                results.forEach(result -> keys.add(result.item()));
            }
            loaded.potionMixes().forEach(mix -> keys.addAll(List.of(mix.input(), mix.reagent(), mix.output())));
            loaded.containerMixes().forEach(mix -> keys.addAll(List.of(mix.input().variant(), mix.reagent().variant(), mix.output().variant())));
            assertFalse(keys.isEmpty());
            for (BedrockItemVariant key : keys) {
                String identifier = key.type().identifier();
                assertFalse(key.type().isCustom() || palette.itemType(identifier, Lookup.EXACT) == null, identifier + " in " + version);
            }
        }
    }
}
