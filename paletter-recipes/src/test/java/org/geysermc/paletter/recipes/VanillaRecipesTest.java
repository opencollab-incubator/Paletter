package org.geysermc.paletter.recipes;

import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataSource;
import org.geysermc.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundled recipes load for every bundled data version and hold what the captures show. */
class VanillaRecipesTest {
    private static final List<DataVersion> VERSIONS = PaletteDataSource.bundled().versions();

    @Test
    void everyBundledVersionLoads() {
        for (DataVersion version : VERSIONS) {
            VanillaRecipes recipes = VanillaRecipes.of(version);
            assertEquals(version, recipes.version());
            assertTrue(recipes.recipes().size() > 3000, version.toString());
            assertFalse(recipes.potionMixes().isEmpty(), version.toString());
            assertFalse(recipes.containerMixes().isEmpty(), version.toString());
            assertFalse(recipes.trimPatterns().isEmpty(), version.toString());
            assertFalse(recipes.trimMaterials().isEmpty(), version.toString());
            assertEquals(14, recipes.recipes().stream().filter(recipe -> recipe instanceof Recipe.Multi).count(), version.toString());
        }
    }

    @Test
    void aVersionServesTheBundleOfItsFirstProtocolUpToTheNextVersion() {
        DataVersion first = VERSIONS.getFirst();
        DataVersion second = VERSIONS.get(1);
        assertSame(VanillaRecipes.of(first), VanillaRecipes.of(new DataVersion("custom", second.protocolVersion() - 1)));
        assertSame(VanillaRecipes.of(second), VanillaRecipes.of(new DataVersion("custom", second.protocolVersion())));
        // Older than every bundled version: the oldest bundle
        assertSame(VanillaRecipes.of(first), VanillaRecipes.of(new DataVersion("ancient", 1)));
    }

    @Test
    void aShapedRecipeIsAsCaptured() {
        Recipe.Shaped barrel = VanillaRecipes.of(VERSIONS.getLast()).recipes().stream()
            .filter(recipe -> recipe instanceof Recipe.Shaped shaped && shaped.id().equals("minecraft:barrel"))
            .map(recipe -> (Recipe.Shaped) recipe).findFirst().orElseThrow();
        assertEquals(3, barrel.width());
        assertEquals(3, barrel.height());
        assertEquals(9, barrel.ingredients().size());
        Ingredient.Item stick = assertInstanceOf(Ingredient.Item.class, barrel.ingredients().getFirst());
        assertEquals(BedrockItems.STICK, stick.item());
        assertTrue(stick.anyVariant());
        assertEquals(new Ingredient.Tag("minecraft:wooden_slabs", 1), barrel.ingredients().get(1));
        assertEquals(new Ingredient.Empty(), barrel.ingredients().get(4));
        assertEquals(BedrockItems.BARREL.variant(), barrel.results().getFirst().item());
        assertEquals("minecraft:barrel", barrel.results().getFirst().blockState().getString("name"));
    }

    @Test
    void smeltingIsShapelessAtAFurnaceStation() {
        assertTrue(VanillaRecipes.of(VERSIONS.getLast()).recipes().stream()
            .anyMatch(recipe -> recipe instanceof Recipe.Shapeless shapeless && shapeless.station().equals("furnace")));
    }
}
