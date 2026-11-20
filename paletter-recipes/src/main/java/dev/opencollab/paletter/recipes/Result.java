package dev.opencollab.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;

import java.util.Objects;

/**
 * An item a recipe produces. Made with {@link #builder()}.
 *
 * @since 1.0.0
 */
public final class Result {
    private final BedrockItemVariant item;
    private final int count;
    private final NbtMap blockState;
    private final NbtMap tag;

    private Result(Builder b) {
        this.item = Objects.requireNonNull(b.item, "Result.item");
        this.count = b.count;
        this.blockState = Objects.requireNonNull(b.blockState, "Result.blockState");
        this.tag = Objects.requireNonNull(b.tag, "Result.tag");
        if (count < 1) {
            throw new IllegalArgumentException("A result needs a count of at least 1: " + count);
        }
    }

    /**
     * Creates a builder: the item must be set.
     *
     * @return a builder with the count 1, no block state and no NBT
     */
    public static @NonNull Builder builder() {
        return new Builder();
    }

    /**
     * Returns the item variant.
     *
     * @return the item variant
     */
    public @NonNull BedrockItemVariant item() {
        return item;
    }

    /**
     * Returns how many items.
     *
     * @return how many items, at least 1
     */
    public int count() {
        return count;
    }

    /**
     * Returns the block state of the item.
     *
     * @return the block state (compound with {@code name} and {@code states}), or an empty map if the item places no particular state
     */
    public @NonNull NbtMap blockState() {
        return blockState;
    }

    /**
     * Returns the item's NBT.
     *
     * @return the item's NBT, or an empty map if it has none
     */
    public @NonNull NbtMap tag() {
        return tag;
    }

    /**
     * Creates a builder holding this result's fields, to change some of them.
     *
     * @return a builder with this result's fields
     */
    public @NonNull Builder toBuilder() {
        return builder().item(item).count(count).blockState(blockState).tag(tag);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof Result other && item.equals(other.item) && count == other.count && blockState.equals(other.blockState)
            && tag.equals(other.tag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(item, count, blockState, tag);
    }

    @Override
    public String toString() {
        return "Result[item=" + item + ", count=" + count + ", blockState=" + blockState + ", tag=" + tag + "]";
    }

    /**
     * Builds a {@link Result}.
     *
     * @since 1.0.0
     */
    public static final class Builder {
        private BedrockItemVariant item;
        private int count = 1;
        private NbtMap blockState = NbtMap.EMPTY;
        private NbtMap tag = NbtMap.EMPTY;

        private Builder() {
        }

        /**
         * Sets the item variant.
         *
         * @param item the item variant
         * @return this builder
         */
        public @NonNull Builder item(@NonNull BedrockItemVariant item) {
            this.item = item;
            return this;
        }

        /**
         * Sets how many items.
         *
         * @param count how many items, at least 1
         * @return this builder
         */
        public @NonNull Builder count(int count) {
            this.count = count;
            return this;
        }

        /**
         * Sets the block state of the item.
         *
         * @param blockState the block state (compound with {@code name} and {@code states}), or an empty map for none in particular
         * @return this builder
         */
        public @NonNull Builder blockState(@NonNull NbtMap blockState) {
            this.blockState = blockState;
            return this;
        }

        /**
         * Sets the item's NBT.
         *
         * @param tag the item's NBT, or an empty map for none
         * @return this builder
         */
        public @NonNull Builder tag(@NonNull NbtMap tag) {
            this.tag = tag;
            return this;
        }

        /**
         * Builds the result.
         *
         * @return the result
         * @throws IllegalArgumentException if the count is below 1
         * @throws NullPointerException if the item is not set
         */
        public @NonNull Result build() {
            return new Result(this);
        }
    }
}
