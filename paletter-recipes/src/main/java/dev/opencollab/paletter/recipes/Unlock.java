package dev.opencollab.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.RecipeUnlockingRequirement.UnlockingContext;

import java.util.List;
import java.util.Objects;

/**
 * What unlocks a recipe in the recipe book, as BDS sends it: a context, or (with the context {@code NONE}) the items the
 * player must have. {@code NONE} without items is what BDS sends for a recipe that is not unlocked by anything in particular.
 * Made with {@link #of} or {@link #byIngredients}.
 *
 * @since 1.0.0
 */
public final class Unlock {
    private final UnlockingContext context;
    private final List<Ingredient> ingredients;

    private Unlock(UnlockingContext context, List<Ingredient> ingredients) {
        this.context = Objects.requireNonNull(context, "Unlock.context");
        this.ingredients = List.copyOf(ingredients);
        if (!this.ingredients.isEmpty() && context != UnlockingContext.NONE) {
            throw new IllegalArgumentException("Only the context NONE has unlocking ingredients, not " + context);
        }
    }

    /** The unlock of the recipe bundle's data: a context with the items it has (which only {@code NONE} may have). */
    static Unlock read(UnlockingContext context, List<Ingredient> ingredients) {
        return new Unlock(context, ingredients);
    }

    /**
     * Returns when the recipe unlocks without items.
     *
     * @return the context
     */
    public @NonNull UnlockingContext context() {
        return context;
    }

    /**
     * Returns the items that unlock the recipe when the player has them.
     *
     * @return the items; empty unless the context is {@code NONE}
     */
    public @NonNull List<Ingredient> ingredients() {
        return ingredients;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof Unlock other && context == other.context && ingredients.equals(other.ingredients);
    }

    @Override
    public int hashCode() {
        return 31 * context.hashCode() + ingredients.hashCode();
    }

    @Override
    public String toString() {
        return "Unlock[context=" + context + ", ingredients=" + ingredients + "]";
    }

    /**
     * Makes the unlock of a context, without ingredients.
     *
     * @param context a context
     * @return the unlock
     */
    public static @NonNull Unlock of(@NonNull UnlockingContext context) {
        return new Unlock(context, List.of());
    }

    /**
     * Makes the unlock by items: the context {@code NONE} with them.
     *
     * @param ingredients the items that unlock the recipe
     * @return the unlock
     * @throws NullPointerException if the list is null or has a null element
     */
    public static @NonNull Unlock byIngredients(@NonNull List<Ingredient> ingredients) {
        return new Unlock(UnlockingContext.NONE, ingredients);
    }
}
