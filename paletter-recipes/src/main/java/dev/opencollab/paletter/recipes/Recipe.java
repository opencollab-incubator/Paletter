package dev.opencollab.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.RecipeUnlockingRequirement.UnlockingContext;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A vanilla recipe, as BDS sends it. Smelting recipes are {@link Shapeless} recipes of a furnace station ({@link RecipeStations}). The
 * recipes are final classes made with builders (a field Bedrock adds is a new builder setter, docs/api.md "Evolution"), and
 * check their own shape: the ingredients of a {@link Shaped} fill its grid, and a recipe has results. Net ids are not
 * part of it (they number the recipes a server sends, whatever it adds), and neither are the random UUIDs BDS gives
 * crafting recipes: only the {@link Multi} recipes have fixed ones.
 * <p>
 * Bedrock adds kinds of recipes, so a {@code switch} over a recipe needs a {@code default}; the compiler enforces it.
 *
 * @since 1.0.0
 */
public sealed interface Recipe permits Recipe.Shaped, Recipe.Shapeless, Recipe.Multi, Recipe.SmithingTransform, Recipe.SmithingTrim, FutureRecipe {
    /**
     * A recipe on a grid, ingredients row by row ({@code width * height} of them). Made with {@link #builder()}.
     *
     * @since 1.0.0
     */
    final class Shaped implements Recipe {
        private final String id;
        private final int width;
        private final int height;
        private final List<Ingredient> ingredients;
        private final List<Result> results;
        private final String station;
        private final int priority;
        private final boolean assumeSymmetry;
        private final Unlock unlock;
        /** {@link #hashCode()}, made on first use: a recipe is a map key of {@code CraftingData} (0: not made yet). */
        private int hash;

        private Shaped(Builder b) {
            this.id = Required.of(b.id, "Recipe.Shaped.id");
            this.width = b.width;
            this.height = b.height;
            this.ingredients = List.copyOf(b.ingredients);
            this.results = List.copyOf(b.results);
            this.station = Required.of(b.station, "Recipe.Shaped.station");
            this.priority = b.priority;
            this.assumeSymmetry = b.assumeSymmetry;
            this.unlock = b.unlock;
            if (width < 1 || height < 1 || width * height != ingredients.size()) {
                throw new IllegalArgumentException("A " + width + "x" + height + " recipe needs " + width * height + " ingredients, not " + ingredients.size());
            }
            requireResults(results);
        }

        /**
         * Creates a builder: the id, the grid, the ingredients, the results and the station must be set.
         *
         * @return a builder with priority 0, no symmetry and the unlock {@code NONE} without items
         */
        public static @NonNull Builder builder() {
            return new Builder();
        }

        /**
         * Returns the recipe's id.
         *
         * @return the recipe's string id
         */
        public @NonNull String id() {
            return id;
        }

        /**
         * Returns the grid's width.
         *
         * @return the grid's width
         */
        public int width() {
            return width;
        }

        /**
         * Returns the grid's height.
         *
         * @return the grid's height
         */
        public int height() {
            return height;
        }

        /**
         * Returns the slots, row by row.
         *
         * @return the slots, exactly {@code width * height}
         */
        public @NonNull List<Ingredient> ingredients() {
            return ingredients;
        }

        /**
         * Returns the items produced.
         *
         * @return the items produced, at least one
         */
        public @NonNull List<Result> results() {
            return results;
        }

        /**
         * Returns the crafting station tag.
         *
         * @return the crafting station tag, one of {@link RecipeStations}
         */
        public @NonNull String station() {
            return station;
        }

        /**
         * Returns the recipe's priority.
         *
         * @return the recipe's priority
         */
        public int priority() {
            return priority;
        }

        /**
         * Returns whether the mirrored recipe matches too.
         *
         * @return whether the mirrored recipe matches too
         */
        public boolean assumeSymmetry() {
            return assumeSymmetry;
        }

        /**
         * Returns what unlocks it in the recipe book.
         *
         * @return what unlocks it in the recipe book
         */
        public @NonNull Unlock unlock() {
            return unlock;
        }

        /**
         * Creates a builder holding this recipe's fields, to change some of them.
         *
         * @return a builder with this recipe's fields
         */
        public @NonNull Builder toBuilder() {
            return builder().id(id).grid(width, height).ingredients(ingredients).results(results).station(station).priority(priority)
                .assumeSymmetry(assumeSymmetry).unlock(unlock);
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof Shaped other && id.equals(other.id) && width == other.width && height == other.height
                && ingredients.equals(other.ingredients) && results.equals(other.results) && station.equals(other.station)
                && priority == other.priority && assumeSymmetry == other.assumeSymmetry && unlock.equals(other.unlock);
        }

        @Override
        public int hashCode() {
            int h = hash;
            if (h == 0) {
                hash = h = Objects.hash(id, width, height, ingredients, results, station, priority, assumeSymmetry, unlock);
            }
            return h;
        }

        @Override
        public String toString() {
            return "Shaped[id=" + id + ", width=" + width + ", height=" + height + ", ingredients=" + ingredients + ", results=" + results
                + ", station=" + station + ", priority=" + priority + ", assumeSymmetry=" + assumeSymmetry + ", unlock=" + unlock + "]";
        }

        /**
         * Builds a {@link Shaped} recipe; {@link #build()} checks the shape.
         *
         * @since 1.0.0
         */
        public static final class Builder {
            private String id;
            private int width;
            private int height;
            private List<Ingredient> ingredients = List.of();
            private List<Result> results = List.of();
            private String station;
            private int priority;
            private boolean assumeSymmetry;
            private Unlock unlock = Unlock.of(UnlockingContext.NONE);

            private Builder() {
            }

            /**
             * Sets the recipe's id.
             *
             * @param id the recipe's string id
             * @return this builder
             */
            public @NonNull Builder id(@NonNull String id) {
                this.id = Objects.requireNonNull(id, "id");
                return this;
            }

            /**
             * Sets the grid's size.
             *
             * @param width the grid's width
             * @param height the grid's height
             * @return this builder
             */
            public @NonNull Builder grid(int width, int height) {
                this.width = width;
                this.height = height;
                return this;
            }

            /**
             * Sets the slots, row by row.
             *
             * @param ingredients the slots, exactly {@code width * height}
             * @return this builder
             */
            public @NonNull Builder ingredients(@NonNull List<Ingredient> ingredients) {
                this.ingredients = Objects.requireNonNull(ingredients, "ingredients");
                return this;
            }

            /**
             * Sets the items produced.
             *
             * @param results the items produced, at least one
             * @return this builder
             */
            public @NonNull Builder results(@NonNull List<Result> results) {
                this.results = Objects.requireNonNull(results, "results");
                return this;
            }

            /**
             * Sets the crafting station tag.
             *
             * @param station the crafting station tag, one of {@link RecipeStations}
             * @return this builder
             */
            public @NonNull Builder station(@NonNull String station) {
                this.station = Objects.requireNonNull(station, "station");
                return this;
            }

            /**
             * Sets the recipe's priority.
             *
             * @param priority the recipe's priority
             * @return this builder
             */
            public @NonNull Builder priority(int priority) {
                this.priority = priority;
                return this;
            }

            /**
             * Sets whether the mirrored recipe matches too.
             *
             * @param assumeSymmetry whether the mirrored recipe matches too
             * @return this builder
             */
            public @NonNull Builder assumeSymmetry(boolean assumeSymmetry) {
                this.assumeSymmetry = assumeSymmetry;
                return this;
            }

            /**
             * Sets what unlocks it in the recipe book.
             *
             * @param unlock what unlocks it in the recipe book
             * @return this builder
             */
            public @NonNull Builder unlock(@NonNull Unlock unlock) {
                this.unlock = Objects.requireNonNull(unlock, "unlock");
                return this;
            }

            /**
             * Builds the recipe, copying the lists.
             *
             * @return the recipe
             * @throws IllegalArgumentException if the ingredients do not fill the width and height, or there is no result
             * @throws IllegalStateException if the id or the station is not set
             * @throws NullPointerException if a list has a null element
             */
            public @NonNull Shaped build() {
                return new Shaped(this);
            }
        }
    }

    /**
     * A recipe without a pattern, which includes smelting. Made with {@link #builder()}.
     *
     * @since 1.0.0
     */
    final class Shapeless implements Recipe {
        private final String id;
        private final List<Ingredient> ingredients;
        private final List<Result> results;
        private final String station;
        private final int priority;
        private final boolean shulkerBox;
        private final Unlock unlock;
        /** {@link #hashCode()}, made on first use (0: not made yet). */
        private int hash;

        private Shapeless(Builder b) {
            this.id = Required.of(b.id, "Recipe.Shapeless.id");
            this.ingredients = List.copyOf(b.ingredients);
            this.results = List.copyOf(b.results);
            this.station = Required.of(b.station, "Recipe.Shapeless.station");
            this.priority = b.priority;
            this.shulkerBox = b.shulkerBox;
            this.unlock = b.unlock;
            requireResults(results);
        }

        /**
         * Creates a builder: the id, the ingredients, the results and the station must be set.
         *
         * @return a builder with priority 0, not in the shulker box list and the unlock {@code NONE} without items
         */
        public static @NonNull Builder builder() {
            return new Builder();
        }

        /**
         * Returns the recipe's id.
         *
         * @return the recipe's string id
         */
        public @NonNull String id() {
            return id;
        }

        /**
         * Returns the slots.
         *
         * @return the slots
         */
        public @NonNull List<Ingredient> ingredients() {
            return ingredients;
        }

        /**
         * Returns the items produced.
         *
         * @return the items produced, at least one
         */
        public @NonNull List<Result> results() {
            return results;
        }

        /**
         * Returns the station tag.
         *
         * @return the station tag, one of {@link RecipeStations}
         */
        public @NonNull String station() {
            return station;
        }

        /**
         * Returns the recipe's priority.
         *
         * @return the recipe's priority
         */
        public int priority() {
            return priority;
        }

        /**
         * Returns whether BDS sends it in the shulker box list of the recipes (the dyeing of shulker boxes and bundles).
         *
         * @return whether it is in the shulker box list
         */
        public boolean shulkerBox() {
            return shulkerBox;
        }

        /**
         * Returns what unlocks it in the recipe book.
         *
         * @return what unlocks it in the recipe book
         */
        public @NonNull Unlock unlock() {
            return unlock;
        }

        /**
         * Creates a builder holding this recipe's fields, to change some of them.
         *
         * @return a builder with this recipe's fields
         */
        public @NonNull Builder toBuilder() {
            return builder().id(id).ingredients(ingredients).results(results).station(station).priority(priority).shulkerBox(shulkerBox)
                .unlock(unlock);
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof Shapeless other && id.equals(other.id) && ingredients.equals(other.ingredients)
                && results.equals(other.results) && station.equals(other.station) && priority == other.priority
                && shulkerBox == other.shulkerBox && unlock.equals(other.unlock);
        }

        @Override
        public int hashCode() {
            int h = hash;
            if (h == 0) {
                hash = h = Objects.hash(id, ingredients, results, station, priority, shulkerBox, unlock);
            }
            return h;
        }

        @Override
        public String toString() {
            return "Shapeless[id=" + id + ", ingredients=" + ingredients + ", results=" + results + ", station=" + station
                + ", priority=" + priority + ", shulkerBox=" + shulkerBox + ", unlock=" + unlock + "]";
        }

        /**
         * Builds a {@link Shapeless} recipe; {@link #build()} checks it.
         *
         * @since 1.0.0
         */
        public static final class Builder {
            private String id;
            private List<Ingredient> ingredients = List.of();
            private List<Result> results = List.of();
            private String station;
            private int priority;
            private boolean shulkerBox;
            private Unlock unlock = Unlock.of(UnlockingContext.NONE);

            private Builder() {
            }

            /**
             * Sets the recipe's id.
             *
             * @param id the recipe's string id
             * @return this builder
             */
            public @NonNull Builder id(@NonNull String id) {
                this.id = Objects.requireNonNull(id, "id");
                return this;
            }

            /**
             * Sets the slots.
             *
             * @param ingredients the slots
             * @return this builder
             */
            public @NonNull Builder ingredients(@NonNull List<Ingredient> ingredients) {
                this.ingredients = Objects.requireNonNull(ingredients, "ingredients");
                return this;
            }

            /**
             * Sets the items produced.
             *
             * @param results the items produced, at least one
             * @return this builder
             */
            public @NonNull Builder results(@NonNull List<Result> results) {
                this.results = Objects.requireNonNull(results, "results");
                return this;
            }

            /**
             * Sets the station tag.
             *
             * @param station the station tag, one of {@link RecipeStations}
             * @return this builder
             */
            public @NonNull Builder station(@NonNull String station) {
                this.station = Objects.requireNonNull(station, "station");
                return this;
            }

            /**
             * Sets the recipe's priority.
             *
             * @param priority the recipe's priority
             * @return this builder
             */
            public @NonNull Builder priority(int priority) {
                this.priority = priority;
                return this;
            }

            /**
             * Sets whether BDS sends it in the shulker box list of the recipes.
             *
             * @param shulkerBox whether it is in the shulker box list
             * @return this builder
             */
            public @NonNull Builder shulkerBox(boolean shulkerBox) {
                this.shulkerBox = shulkerBox;
                return this;
            }

            /**
             * Sets what unlocks it in the recipe book.
             *
             * @param unlock what unlocks it in the recipe book
             * @return this builder
             */
            public @NonNull Builder unlock(@NonNull Unlock unlock) {
                this.unlock = Objects.requireNonNull(unlock, "unlock");
                return this;
            }

            /**
             * Builds the recipe, copying the lists.
             *
             * @return the recipe
             * @throws IllegalArgumentException if there is no result
             * @throws IllegalStateException if the id or the station is not set
             * @throws NullPointerException if a list has a null element
             */
            public @NonNull Shapeless build() {
                return new Shapeless(this);
            }
        }
    }

    /**
     * A recipe the client works out itself (map cloning, banner patterns, ...).
     *
     * @since 1.0.0
     */
    final class Multi implements Recipe {
        private final UUID id;

        private Multi(UUID id) {
            this.id = Objects.requireNonNull(id, "Recipe.Multi.id");
        }

        /**
         * Creates the recipe of a fixed UUID.
         *
         * @param id its fixed UUID
         * @return the recipe
         */
        public static @NonNull Multi of(@NonNull UUID id) {
            return new Multi(id);
        }

        /**
         * Returns the recipe's fixed UUID.
         *
         * @return its fixed UUID
         */
        public @NonNull UUID id() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof Multi other && id.equals(other.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return "Multi[id=" + id + "]";
        }
    }

    /**
     * A smithing table recipe with a result. Made with {@link #builder()}; every field must be set.
     *
     * @since 1.0.0
     */
    final class SmithingTransform implements Recipe {
        private final String id;
        private final Ingredient template;
        private final Ingredient base;
        private final Ingredient addition;
        private final Result result;
        private final String station;
        /** {@link #hashCode()}, made on first use (0: not made yet). */
        private int hash;

        private SmithingTransform(Builder b) {
            this.id = Required.of(b.id, "Recipe.SmithingTransform.id");
            this.template = Required.of(b.template, "Recipe.SmithingTransform.template");
            this.base = Required.of(b.base, "Recipe.SmithingTransform.base");
            this.addition = Required.of(b.addition, "Recipe.SmithingTransform.addition");
            this.result = Required.of(b.result, "Recipe.SmithingTransform.result");
            this.station = Required.of(b.station, "Recipe.SmithingTransform.station");
        }

        /**
         * Creates a builder.
         *
         * @return a builder with nothing set
         */
        public static @NonNull Builder builder() {
            return new Builder();
        }

        /**
         * Returns the recipe's id.
         *
         * @return the recipe's string id
         */
        public @NonNull String id() {
            return id;
        }

        /**
         * Returns the template slot.
         *
         * @return the template slot
         */
        public @NonNull Ingredient template() {
            return template;
        }

        /**
         * Returns the base slot.
         *
         * @return the base slot
         */
        public @NonNull Ingredient base() {
            return base;
        }

        /**
         * Returns the addition slot.
         *
         * @return the addition slot
         */
        public @NonNull Ingredient addition() {
            return addition;
        }

        /**
         * Returns the item produced.
         *
         * @return the item produced
         */
        public @NonNull Result result() {
            return result;
        }

        /**
         * Returns the station tag.
         *
         * @return the station tag, one of {@link RecipeStations}
         */
        public @NonNull String station() {
            return station;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof SmithingTransform other && id.equals(other.id) && template.equals(other.template)
                && base.equals(other.base) && addition.equals(other.addition) && result.equals(other.result) && station.equals(other.station);
        }

        @Override
        public int hashCode() {
            int h = hash;
            if (h == 0) {
                hash = h = Objects.hash(id, template, base, addition, result, station);
            }
            return h;
        }

        @Override
        public String toString() {
            return "SmithingTransform[id=" + id + ", template=" + template + ", base=" + base + ", addition=" + addition + ", result=" + result
                + ", station=" + station + "]";
        }

        /**
         * Builds a {@link SmithingTransform} recipe.
         *
         * @since 1.0.0
         */
        public static final class Builder {
            private String id;
            private Ingredient template;
            private Ingredient base;
            private Ingredient addition;
            private Result result;
            private String station;

            private Builder() {
            }

            /**
             * Sets the recipe's id.
             *
             * @param id the recipe's string id
             * @return this builder
             */
            public @NonNull Builder id(@NonNull String id) {
                this.id = Objects.requireNonNull(id, "id");
                return this;
            }

            /**
             * Sets the template slot.
             *
             * @param template the template slot
             * @return this builder
             */
            public @NonNull Builder template(@NonNull Ingredient template) {
                this.template = Objects.requireNonNull(template, "template");
                return this;
            }

            /**
             * Sets the base slot.
             *
             * @param base the base slot
             * @return this builder
             */
            public @NonNull Builder base(@NonNull Ingredient base) {
                this.base = Objects.requireNonNull(base, "base");
                return this;
            }

            /**
             * Sets the addition slot.
             *
             * @param addition the addition slot
             * @return this builder
             */
            public @NonNull Builder addition(@NonNull Ingredient addition) {
                this.addition = Objects.requireNonNull(addition, "addition");
                return this;
            }

            /**
             * Sets the item produced.
             *
             * @param result the item produced
             * @return this builder
             */
            public @NonNull Builder result(@NonNull Result result) {
                this.result = Objects.requireNonNull(result, "result");
                return this;
            }

            /**
             * Sets the station tag.
             *
             * @param station the station tag, one of {@link RecipeStations}
             * @return this builder
             */
            public @NonNull Builder station(@NonNull String station) {
                this.station = Objects.requireNonNull(station, "station");
                return this;
            }

            /**
             * Builds the recipe.
             *
             * @return the recipe
             * @throws IllegalStateException if a field is not set
             */
            public @NonNull SmithingTransform build() {
                return new SmithingTransform(this);
            }
        }
    }

    /**
     * A smithing table recipe that applies an armor trim (the result is the base with the trim). Made with {@link #builder()};
     * every field must be set.
     *
     * @since 1.0.0
     */
    final class SmithingTrim implements Recipe {
        private final String id;
        private final Ingredient template;
        private final Ingredient base;
        private final Ingredient addition;
        private final String station;
        /** {@link #hashCode()}, made on first use (0: not made yet). */
        private int hash;

        private SmithingTrim(Builder b) {
            this.id = Required.of(b.id, "Recipe.SmithingTrim.id");
            this.template = Required.of(b.template, "Recipe.SmithingTrim.template");
            this.base = Required.of(b.base, "Recipe.SmithingTrim.base");
            this.addition = Required.of(b.addition, "Recipe.SmithingTrim.addition");
            this.station = Required.of(b.station, "Recipe.SmithingTrim.station");
        }

        /**
         * Creates a builder.
         *
         * @return a builder with nothing set
         */
        public static @NonNull Builder builder() {
            return new Builder();
        }

        /**
         * Returns the recipe's id.
         *
         * @return the recipe's string id
         */
        public @NonNull String id() {
            return id;
        }

        /**
         * Returns the template slot.
         *
         * @return the template slot
         */
        public @NonNull Ingredient template() {
            return template;
        }

        /**
         * Returns the base slot.
         *
         * @return the base slot
         */
        public @NonNull Ingredient base() {
            return base;
        }

        /**
         * Returns the addition slot.
         *
         * @return the addition slot
         */
        public @NonNull Ingredient addition() {
            return addition;
        }

        /**
         * Returns the station tag.
         *
         * @return the station tag, one of {@link RecipeStations}
         */
        public @NonNull String station() {
            return station;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof SmithingTrim other && id.equals(other.id) && template.equals(other.template)
                && base.equals(other.base) && addition.equals(other.addition) && station.equals(other.station);
        }

        @Override
        public int hashCode() {
            int h = hash;
            if (h == 0) {
                hash = h = Objects.hash(id, template, base, addition, station);
            }
            return h;
        }

        @Override
        public String toString() {
            return "SmithingTrim[id=" + id + ", template=" + template + ", base=" + base + ", addition=" + addition + ", station=" + station + "]";
        }

        /**
         * Builds a {@link SmithingTrim} recipe.
         *
         * @since 1.0.0
         */
        public static final class Builder {
            private String id;
            private Ingredient template;
            private Ingredient base;
            private Ingredient addition;
            private String station;

            private Builder() {
            }

            /**
             * Sets the recipe's id.
             *
             * @param id the recipe's string id
             * @return this builder
             */
            public @NonNull Builder id(@NonNull String id) {
                this.id = Objects.requireNonNull(id, "id");
                return this;
            }

            /**
             * Sets the template slot.
             *
             * @param template the template slot
             * @return this builder
             */
            public @NonNull Builder template(@NonNull Ingredient template) {
                this.template = Objects.requireNonNull(template, "template");
                return this;
            }

            /**
             * Sets the base slot.
             *
             * @param base the base slot
             * @return this builder
             */
            public @NonNull Builder base(@NonNull Ingredient base) {
                this.base = Objects.requireNonNull(base, "base");
                return this;
            }

            /**
             * Sets the addition slot.
             *
             * @param addition the addition slot
             * @return this builder
             */
            public @NonNull Builder addition(@NonNull Ingredient addition) {
                this.addition = Objects.requireNonNull(addition, "addition");
                return this;
            }

            /**
             * Sets the station tag.
             *
             * @param station the station tag, one of {@link RecipeStations}
             * @return this builder
             */
            public @NonNull Builder station(@NonNull String station) {
                this.station = Objects.requireNonNull(station, "station");
                return this;
            }

            /**
             * Builds the recipe.
             *
             * @return the recipe
             * @throws IllegalStateException if a field is not set
             */
            public @NonNull SmithingTrim build() {
                return new SmithingTrim(this);
            }
        }
    }

    private static void requireResults(List<Result> results) {
        if (results.isEmpty()) {
            throw new IllegalArgumentException("A recipe needs at least one result");
        }
    }
}
