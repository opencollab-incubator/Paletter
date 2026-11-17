package org.geysermc.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.RecipeUnlockingRequirement.UnlockingContext;

import java.util.List;

/**
 * What unlocks a recipe in the recipe book, as BDS sends it.
 *
 * @param context when the recipe unlocks without the ingredients
 * @param ingredients the items that unlock it when the player has them
 * @since 1.0.0
 */
public record Unlock(@NonNull UnlockingContext context, @NonNull List<Ingredient> ingredients) {
    public Unlock {
        ingredients = List.copyOf(ingredients);
    }
}
