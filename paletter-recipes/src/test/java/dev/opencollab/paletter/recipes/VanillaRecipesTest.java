package dev.opencollab.paletter.recipes;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.RecipeUnlockingRequirement.UnlockingContext;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.data.PaletteDataSource;
import dev.opencollab.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundled recipes load for every bundled data version and hold what the captures show. */
class VanillaRecipesTest {
    private static final List<DataVersion> VERSIONS = PaletteDataSource.bundled().versions();

    @Test
    void everyCoreVersionHasABundleAndLoads() {
        assertEquals(VERSIONS, VanillaRecipes.versions());
        for (DataVersion version : VERSIONS) {
            VanillaRecipes recipes = VanillaRecipes.of(version);
            assertEquals(version, recipes.version());
            assertTrue(recipes.recipes().size() > 3000, version.toString());
            assertFalse(recipes.potionMixes().isEmpty(), version.toString());
            assertFalse(recipes.containerMixes().isEmpty(), version.toString());
            assertEquals(14, recipes.recipes().stream().filter(recipe -> recipe instanceof Recipe.Multi).count(), version.toString());
        }
    }

    @Test
    void aVersionWithoutABundleThrowsOrFallsBack() {
        DataVersion unknown = new DataVersion("custom", 1);
        assertThrows(IllegalArgumentException.class, () -> VanillaRecipes.of(unknown));
        assertNull(VanillaRecipes.of(unknown, null));
        VanillaRecipes fallback = VanillaRecipes.of(VERSIONS.getFirst());
        assertSame(fallback, VanillaRecipes.of(unknown, fallback));
        assertSame(fallback, VanillaRecipes.of(VERSIONS.getFirst(), null));
        // Exact: the same name with another protocol is not that version
        assertNull(VanillaRecipes.of(new DataVersion(VERSIONS.getFirst().name(), 1), null));
    }

    @Test
    void aShapedRecipeIsAsCaptured() {
        Recipe.Shaped barrel = VanillaRecipes.of(VERSIONS.getLast()).recipes().stream()
            .filter(recipe -> recipe instanceof Recipe.Shaped shaped && shaped.id().equals("minecraft:barrel"))
            .map(recipe -> (Recipe.Shaped) recipe).findFirst().orElseThrow();
        assertEquals(3, barrel.width());
        assertEquals(3, barrel.height());
        assertEquals(9, barrel.ingredients().size());
        Ingredient.AnyVariant stick = assertInstanceOf(Ingredient.AnyVariant.class, barrel.ingredients().getFirst());
        assertEquals(BedrockItems.STICK, stick.type());
        assertEquals(new Ingredient.Tag("minecraft:wooden_slabs", 1), barrel.ingredients().get(1));
        assertEquals(new Ingredient.Empty(), barrel.ingredients().get(4));
        assertEquals(BedrockItems.BARREL.variant(), barrel.results().getFirst().item());
        assertEquals("minecraft:barrel", barrel.results().getFirst().blockState().getString("name"));
    }

    @Test
    void smeltingIsShapelessAtAFurnaceStation() {
        assertTrue(VanillaRecipes.of(VERSIONS.getLast()).recipes().stream()
            .anyMatch(recipe -> recipe instanceof Recipe.Shapeless shapeless && shapeless.station().equals(RecipeStations.FURNACE)));
    }

    @Test
    void recipesCheckTheirOwnShape() {
        Ingredient stick = new Ingredient.AnyVariant(BedrockItems.STICK, 1);
        Result result = new Result(BedrockItems.TORCH.variant(), 4, NbtMap.EMPTY, NbtMap.EMPTY);
        Unlock none = Unlock.of(UnlockingContext.NONE);
        assertThrows(IllegalArgumentException.class, () -> new Recipe.Shaped("x", 1, 2, List.of(stick), List.of(result), RecipeStations.CRAFTING_TABLE, 0, true, none));
        assertThrows(IllegalArgumentException.class, () -> new Recipe.Shaped("x", 1, 1, List.of(stick), List.of(), RecipeStations.CRAFTING_TABLE, 0, true, none));
        assertThrows(IllegalArgumentException.class, () -> new Recipe.Shapeless("x", List.of(stick), List.of(), RecipeStations.CRAFTING_TABLE, 0, false, none));
        assertThrows(IllegalArgumentException.class, () -> new Ingredient.Tag("minecraft:planks", 0));
        assertThrows(IllegalArgumentException.class, () -> new Ingredient.AnyVariant(BedrockItems.STICK, 0));
        assertThrows(IllegalArgumentException.class, () -> new Result(BedrockItems.TORCH.variant(), 0, NbtMap.EMPTY, NbtMap.EMPTY));
        new Recipe.Shaped("x", 1, 1, List.of(stick), List.of(result), RecipeStations.CRAFTING_TABLE, 0, true, none);
    }

    @Test
    void unlocksHaveIngredientsOnlyWithTheContextNone() {
        Ingredient stick = new Ingredient.AnyVariant(BedrockItems.STICK, 1);
        assertEquals(UnlockingContext.NONE, Unlock.byIngredients(List.of(stick)).context());
        assertEquals(List.of(stick), Unlock.byIngredients(List.of(stick)).ingredients());
        assertTrue(Unlock.of(UnlockingContext.PLAYER_IN_WATER).ingredients().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new Unlock(UnlockingContext.ALWAYS_UNLOCKED, List.of(stick)));
    }
}
