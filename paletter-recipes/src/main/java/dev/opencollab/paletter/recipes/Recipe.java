package dev.opencollab.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.UUID;

/**
 * A vanilla recipe, as BDS sends it. Smelting recipes are {@link Shapeless} recipes of a furnace station ({@link RecipeStations}). The
 * records check their own shape: the ingredients of a {@link Shaped} fill its grid, and a recipe has results. Net ids are not
 * part of it (they number the recipes a server sends, whatever it adds), and neither are the random UUIDs BDS gives
 * crafting recipes: only the {@link Multi} recipes have fixed ones.
 *
 * @since 1.0.0
 */
public sealed interface Recipe {
    /**
     * A recipe on a grid, ingredients row by row ({@code width * height} of them).
     *
     * @param id the recipe's string id
     * @param width the grid's width
     * @param height the grid's height
     * @param ingredients the slots, exactly {@code width * height}
     * @param results the items produced, at least one
     * @param station the crafting station tag, one of {@link RecipeStations}
     * @param priority the recipe's priority
     * @param assumeSymmetry whether the mirrored recipe matches too
     * @param unlock what unlocks it in the recipe book
     * @since 1.0.0
     */
    record Shaped(@NonNull String id, int width, int height, @NonNull List<Ingredient> ingredients, @NonNull List<Result> results,
                  @NonNull String station, int priority, boolean assumeSymmetry, @NonNull Unlock unlock) implements Recipe {
        /**
         * Creates the recipe, copying the lists.
         *
         * @throws IllegalArgumentException if the ingredients do not fill the width and height, or there is no result
         * @throws NullPointerException if a list is null or has a null element
         */
        public Shaped {
            ingredients = List.copyOf(ingredients);
            results = List.copyOf(results);
            if (width < 1 || height < 1 || width * height != ingredients.size()) {
                throw new IllegalArgumentException("A " + width + "x" + height + " recipe needs " + width * height + " ingredients, not " + ingredients.size());
            }
            requireResults(results);
        }
    }

    /**
     * A recipe without a pattern, which includes smelting.
     *
     * @param id the recipe's string id
     * @param ingredients the slots
     * @param results the items produced
     * @param station the station tag, one of {@link RecipeStations}
     * @param priority the recipe's priority
     * @param shulkerBox whether BDS sends it in the shulker box list of the recipes (the dyeing of shulker boxes and bundles)
     * @param unlock what unlocks it in the recipe book
     * @since 1.0.0
     */
    record Shapeless(@NonNull String id, @NonNull List<Ingredient> ingredients, @NonNull List<Result> results, @NonNull String station,
                     int priority, boolean shulkerBox, @NonNull Unlock unlock) implements Recipe {
        /**
         * Creates the recipe, copying the lists.
         *
         * @throws IllegalArgumentException if there is no result
         * @throws NullPointerException if a list is null or has a null element
         */
        public Shapeless {
            ingredients = List.copyOf(ingredients);
            results = List.copyOf(results);
            requireResults(results);
        }
    }

    /**
     * A recipe the client works out itself (map cloning, banner patterns, ...).
     *
     * @param id its fixed UUID
     * @since 1.0.0
     */
    record Multi(@NonNull UUID id) implements Recipe {
    }

    /**
     * A smithing table recipe with a result.
     *
     * @param id the recipe's string id
     * @param template the template slot
     * @param base the base slot
     * @param addition the addition slot
     * @param result the item produced
     * @param station the station tag, one of {@link RecipeStations}
     * @since 1.0.0
     */
    record SmithingTransform(@NonNull String id, @NonNull Ingredient template, @NonNull Ingredient base, @NonNull Ingredient addition,
                             @NonNull Result result, @NonNull String station) implements Recipe {
    }

    /**
     * A smithing table recipe that applies an armor trim (the result is the base with the trim).
     *
     * @param id the recipe's string id
     * @param template the template slot
     * @param base the base slot
     * @param addition the addition slot
     * @param station the station tag, one of {@link RecipeStations}
     * @since 1.0.0
     */
    record SmithingTrim(@NonNull String id, @NonNull Ingredient template, @NonNull Ingredient base, @NonNull Ingredient addition,
                        @NonNull String station) implements Recipe {
    }

    private static void requireResults(List<Result> results) {
        if (results.isEmpty()) {
            throw new IllegalArgumentException("A recipe needs at least one result");
        }
    }
}
