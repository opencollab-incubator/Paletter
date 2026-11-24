package dev.opencollab.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import dev.opencollab.paletter.vanilla.ItemKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A stack of items, independent of any protocol version: what a {@link dev.opencollab.paletter.Palette} encodes to and
 * decodes from the client's {@code ItemData}, and what the creative inventory is made of.
 * <p>
 * The {@link #variant()} is the key: what the stack is, bounded by known content. The {@link #aux()} is the aux value
 * the stack is sent with: the variant's own aux, or, on a stack of a base variant, a value that names no variant (a
 * legacy damage value, a number a client sent). {@link dev.opencollab.paletter.Palette#decode} never makes a variant
 * for an aux nobody named: it gives the base variant and keeps the client's value in {@link #aux()}, so the variants of
 * decoded stacks are bounded and safe as map keys.
 * <p>
 * Made with {@link #of(ItemKey)}, {@link #of(ItemKey, int)} or {@link #builder(ItemKey)}; a value with
 * {@code equals}, {@code hashCode} and {@code toString} over every field.
 *
 * @since 1.0.0
 */
public final class BedrockItemStack {
    private final BedrockItemVariant variant;
    private final int aux;
    private final int count;
    private final @Nullable NbtMap nbt;
    private final @Nullable BedrockBlockState blockState;
    /** What few stacks have, in one object, so the stacks of a creative inventory stay small; null for none. */
    private final @Nullable Adventure adventure;

    private BedrockItemStack(Builder builder) {
        this.variant = builder.variant;
        this.aux = builder.aux == null ? variant.aux() : builder.aux;
        this.count = builder.count;
        this.nbt = builder.nbt;
        this.blockState = builder.blockState;
        this.adventure = builder.canPlace.isEmpty() && builder.canBreak.isEmpty() && builder.blockingTicks == 0 ? null
            : new Adventure(builder.canPlace, builder.canBreak, builder.blockingTicks);
        if (variant.aux() != 0 && aux != variant.aux()) {
            throw new IllegalArgumentException("The aux " + aux + " is not the aux of the variant " + variant
                + ": only a stack of a base variant takes another aux");
        }
    }

    /**
     * Creates a stack of one item.
     *
     * @param key a variant, or a type for its base variant
     * @return one item of a variant, or of a type's base variant, without NBT or block state
     * @throws NullPointerException if the key is null
     */
    public static @NonNull BedrockItemStack of(@NonNull ItemKey key) {
        return builder(key).build();
    }

    /**
     * Creates a stack of several items.
     *
     * @param key a variant, or a type for its base variant
     * @param count the stack size, at least 1
     * @return a stack of a variant, or of a type's base variant, without NBT or block state
     * @throws NullPointerException if the key is null
     * @throws IllegalArgumentException if the count is below 1
     */
    public static @NonNull BedrockItemStack of(@NonNull ItemKey key, int count) {
        return builder(key).count(count).build();
    }

    /**
     * Starts a stack of one item of a variant, with nothing else set.
     *
     * @param key a variant, or a type for its base variant
     * @return a builder
     * @throws NullPointerException if the key is null
     */
    public static @NonNull Builder builder(@NonNull ItemKey key) {
        return new Builder(key.variant());
    }

    /**
     * Starts a builder with every field of this stack.
     *
     * @return a builder
     */
    public @NonNull Builder toBuilder() {
        Builder builder = new Builder(variant);
        builder.aux = aux == variant.aux() ? null : aux;
        builder.count = count;
        builder.nbt = nbt;
        builder.blockState = blockState;
        builder.canPlace = canPlace();
        builder.canBreak = canBreak();
        builder.blockingTicks = blockingTicks();
        return builder;
    }

    /**
     * The item variant: the key of the stack.
     *
     * @return the item variant (type and aux value)
     */
    public @NonNull BedrockItemVariant variant() {
        return variant;
    }

    /**
     * The item type of the variant.
     *
     * @return the item type
     */
    public @NonNull BedrockItemType type() {
        return variant.type();
    }

    /**
     * The aux value the stack is sent with.
     *
     * @return the aux value: the variant's {@link BedrockItemVariant#aux()}, or on a stack of a base variant a value that
     *         names no variant
     */
    public int aux() {
        return aux;
    }

    /**
     * The stack size.
     *
     * @return the stack size, at least 1
     */
    public int count() {
        return count;
    }

    /**
     * The item NBT.
     *
     * @return the item NBT, or null for none (an empty compound is normalized to null)
     */
    public @Nullable NbtMap nbt() {
        return nbt;
    }

    /**
     * The block the stack places.
     *
     * @return the block the stack places, or null for the item's default block (or none)
     */
    public @Nullable BedrockBlockState blockState() {
        return blockState;
    }

    /**
     * The blocks the stack can be placed on in adventure mode.
     *
     * @return block identifiers, unmodifiable; empty for no restriction
     */
    public @NonNull List<String> canPlace() {
        return adventure == null ? List.of() : adventure.canPlace;
    }

    /**
     * The blocks the stack can break in adventure mode.
     *
     * @return block identifiers, unmodifiable; empty for no restriction
     */
    public @NonNull List<String> canBreak() {
        return adventure == null ? List.of() : adventure.canBreak;
    }

    /**
     * The game tick a shield stack can block from ({@code ItemData#getBlockingTicks()}).
     *
     * @return the blocking ticks, 0 for none
     */
    public long blockingTicks() {
        return adventure == null ? 0 : adventure.blockingTicks;
    }

    /**
     * Returns a copy with another variant, keeping the other fields; an aux of its own (see {@link #aux()}) is dropped.
     *
     * @param key a variant, or a type for its base variant
     * @return the new stack
     */
    public @NonNull BedrockItemStack withVariant(@NonNull ItemKey key) {
        return toBuilder().variant(key).build();
    }

    /**
     * Returns a copy with another count.
     *
     * @param count the stack size, at least 1
     * @return the new stack
     * @throws IllegalArgumentException if the count is below 1
     */
    public @NonNull BedrockItemStack withCount(int count) {
        return count == this.count ? this : toBuilder().count(count).build();
    }

    /**
     * Returns a copy with other NBT.
     *
     * @param nbt the item NBT, or null for none
     * @return the new stack
     */
    public @NonNull BedrockItemStack withNbt(@Nullable NbtMap nbt) {
        return toBuilder().nbt(nbt).build();
    }

    /**
     * Returns a copy with another block state.
     *
     * @param blockState the block the stack places, or null for the item's default block (or none)
     * @return the new stack
     */
    public @NonNull BedrockItemStack withBlockState(@Nullable BedrockBlockState blockState) {
        return toBuilder().blockState(blockState).build();
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof BedrockItemStack other && aux == other.aux && count == other.count
            && variant.equals(other.variant) && Objects.equals(nbt, other.nbt) && Objects.equals(blockState, other.blockState)
            && Objects.equals(adventure, other.adventure);
    }

    @Override
    public int hashCode() {
        return Objects.hash(variant, aux, count, nbt, blockState, adventure);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        if (count != 1) {
            builder.append(count).append("x ");
        }
        builder.append(variant);
        if (aux != variant.aux()) {
            builder.append(" aux ").append(aux);
        }
        if (blockState != null) {
            builder.append(" placing ").append(blockState);
        }
        if (nbt != null) {
            builder.append(' ').append(nbt);
        }
        if (adventure != null) {
            if (!adventure.canPlace.isEmpty()) {
                builder.append(" canPlace ").append(adventure.canPlace);
            }
            if (!adventure.canBreak.isEmpty()) {
                builder.append(" canBreak ").append(adventure.canBreak);
            }
            if (adventure.blockingTicks != 0) {
                builder.append(" blockingTicks ").append(adventure.blockingTicks);
            }
        }
        return builder.toString();
    }

    private record Adventure(List<String> canPlace, List<String> canBreak, long blockingTicks) {
    }

    /**
     * Builds a {@link BedrockItemStack}. Only the variant is required; the count defaults to 1, the aux to the
     * variant's, the lists to empty and the rest to none.
     *
     * @since 1.0.0
     */
    public static final class Builder {
        private BedrockItemVariant variant;
        private @Nullable Integer aux;
        private int count = 1;
        private @Nullable NbtMap nbt;
        private @Nullable BedrockBlockState blockState;
        private List<String> canPlace = List.of();
        private List<String> canBreak = List.of();
        private long blockingTicks;

        private Builder(BedrockItemVariant variant) {
            this.variant = Objects.requireNonNull(variant, "variant");
        }

        /**
         * Sets the variant. An aux set before is dropped: the stack takes the variant's aux.
         *
         * @param key a variant, or a type for its base variant
         * @return this builder
         * @throws NullPointerException if the key is null
         */
        public @NonNull Builder variant(@NonNull ItemKey key) {
            this.variant = Objects.requireNonNull(key, "key").variant();
            this.aux = null;
            return this;
        }

        /**
         * Sets the aux value to send for a stack of a base variant, where it names no variant: a legacy damage value, or
         * what a client sent. A named variant is the {@link #variant(ItemKey) variant} itself, e.g.
         * {@code BedrockItems.POTION.variant(5)}.
         *
         * @param aux the aux value
         * @return this builder
         */
        public @NonNull Builder aux(int aux) {
            this.aux = aux;
            return this;
        }

        /**
         * Sets the stack size.
         *
         * @param count the stack size, at least 1
         * @return this builder
         * @throws IllegalArgumentException if the count is below 1
         */
        public @NonNull Builder count(int count) {
            if (count < 1) {
                throw new IllegalArgumentException("Count must be at least 1, was " + count);
            }
            this.count = count;
            return this;
        }

        /**
         * Sets the item NBT.
         *
         * @param nbt the item NBT, or null for none; an empty compound is none
         * @return this builder
         */
        public @NonNull Builder nbt(@Nullable NbtMap nbt) {
            this.nbt = nbt == null || nbt.isEmpty() ? null : nbt;
            return this;
        }

        /**
         * Sets the block the stack places.
         *
         * @param blockState the block state, or null for the item's default block (or none)
         * @return this builder
         */
        public @NonNull Builder blockState(@Nullable BedrockBlockState blockState) {
            this.blockState = blockState;
            return this;
        }

        /**
         * Sets the blocks the stack can be placed on in adventure mode.
         *
         * @param identifiers block identifiers, or null or empty for no restriction
         * @return this builder
         * @throws NullPointerException if an identifier is null
         */
        public @NonNull Builder canPlace(@Nullable List<String> identifiers) {
            this.canPlace = identifiers == null ? List.of() : List.copyOf(identifiers);
            return this;
        }

        /**
         * Sets the blocks the stack can be placed on in adventure mode.
         *
         * @param first a block identifier
         * @param rest more block identifiers
         * @return this builder
         * @throws NullPointerException if an identifier is null
         */
        public @NonNull Builder canPlace(@NonNull String first, @NonNull String @NonNull ... rest) {
            return canPlace(list(first, rest));
        }

        /**
         * Sets the blocks the stack can break in adventure mode.
         *
         * @param identifiers block identifiers, or null or empty for no restriction
         * @return this builder
         * @throws NullPointerException if an identifier is null
         */
        public @NonNull Builder canBreak(@Nullable List<String> identifiers) {
            this.canBreak = identifiers == null ? List.of() : List.copyOf(identifiers);
            return this;
        }

        /**
         * Sets the blocks the stack can break in adventure mode.
         *
         * @param first a block identifier
         * @param rest more block identifiers
         * @return this builder
         * @throws NullPointerException if an identifier is null
         */
        public @NonNull Builder canBreak(@NonNull String first, @NonNull String @NonNull ... rest) {
            return canBreak(list(first, rest));
        }

        /**
         * Sets the game tick a shield stack can block from.
         *
         * @param blockingTicks the blocking ticks, 0 for none
         * @return this builder
         * @throws IllegalArgumentException if the value is negative
         */
        public @NonNull Builder blockingTicks(long blockingTicks) {
            if (blockingTicks < 0) {
                throw new IllegalArgumentException("Blocking ticks must not be negative, was " + blockingTicks);
            }
            this.blockingTicks = blockingTicks;
            return this;
        }

        /**
         * Builds the stack.
         *
         * @return the stack
         * @throws IllegalArgumentException if an aux is set on a variant that is not a base variant and differs from its aux
         */
        public @NonNull BedrockItemStack build() {
            return new BedrockItemStack(this);
        }

        private static List<String> list(String first, String[] rest) {
            List<String> list = new ArrayList<>(rest.length + 1);
            list.add(first);
            list.addAll(List.of(rest));
            return list;
        }
    }
}
