package org.geysermc.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.RecipeUnlockingRequirement.UnlockingContext;

import java.util.List;

/**
 * What unlocks a recipe in the recipe book, as BDS sends it: a context, or (with the context {@code NONE}) the items the
 * player must have. {@code NONE} without items is what BDS sends for a recipe that is not unlocked by anything in particular.
 *
 * @param context when the recipe unlocks without items
 * @param ingredients the items that unlock it when the player has them; empty unless the context is {@code NONE}
 * @since 1.0.0
 */
public record Unlock(@NonNull UnlockingContext context, @NonNull List<Ingredient> ingredients) {
    public Unlock {
        ingredients = List.copyOf(ingredients);
        if (!ingredients.isEmpty() && context != UnlockingContext.NONE) {
            throw new IllegalArgumentException("Only the context NONE has unlocking ingredients, not " + context);
        }
    }

    /**
     * @param context a context
     * @return the unlock of a context, without ingredients
     */
    public static @NonNull Unlock of(@NonNull UnlockingContext context) {
        return new Unlock(context, List.of());
    }

    /**
     * @param ingredients the items that unlock the recipe
     * @return the unlock by these items: the context {@code NONE} with them
     */
    public static @NonNull Unlock byIngredients(@NonNull List<Ingredient> ingredients) {
        return new Unlock(UnlockingContext.NONE, ingredients);
    }
}
