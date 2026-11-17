package org.geysermc.paletter.tools.data;

import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.recipes.Recipe;
import org.geysermc.paletter.recipes.VanillaRecipes;
import org.geysermc.paletter.tools.client.RecipeCapture;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The loader reads what the generator wrote: the bundle of a release against its capture. */
class RecipeBundleCountsTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));

    @Test
    void theBundleOfTheNewestReleaseHasAsManyRecipesAsItsCapture() throws IOException {
        RecipeCapture.Recipes capture = RecipeCapture.read(ROOT.resolve("data/bds/1.26.63.1/vanilla"));
        VanillaRecipes recipes = VanillaRecipes.of(new DataVersion("26.60", 2225));
        assertEquals("26.60", recipes.version().name());
        int expected = 0;
        for (String list : List.of("shaped", "shapeless", "shulker_box", "multi", "smithing_transform", "smithing_trim")) {
            expected += capture.count(list);
        }
        assertEquals(expected, recipes.recipes().size());
        assertEquals(capture.count("potion_mix"), recipes.potionMixes().size());
        assertEquals(capture.count("container_mix"), recipes.containerMixes().size());
        assertEquals(capture.trim().getAsJsonArray("patterns").size(), recipes.trimPatterns().size());
        assertEquals(capture.trim().getAsJsonArray("materials").size(), recipes.trimMaterials().size());
        assertEquals(capture.count("shulker_box"), recipes.recipes().stream().filter(r -> r instanceof Recipe.Shapeless s && s.shulkerBox()).count());
    }
}
