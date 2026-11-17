package org.geysermc.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.geysermc.paletter.vanilla.ItemKey;

import java.util.function.Predicate;

/**
 * Selects creative stacks, e.g. as an anchor to insert after, or to remove. Matchers print readably, so a placement
 * that found nothing can be reported as {@code after(minecraft:hopper_minecart) matched nothing}.
 * <pre>{@code
 * ItemMatcher.id("minecraft:empty_map").and(ItemMatcher.damage(2))
 * }</pre>
 *
 * @since 0.1.0
 */
public sealed interface ItemMatcher permits ItemMatchers.Id, ItemMatchers.Damage, ItemMatchers.BlockState,
        ItemMatchers.Exact, ItemMatchers.Namespace, ItemMatchers.And, ItemMatchers.Not, ItemMatchers.Custom {

    boolean matches(@NonNull BedrockItemStack stack);

    default @NonNull ItemMatcher and(@NonNull ItemMatcher other) {
        return new ItemMatchers.And(this, other);
    }

    default @NonNull ItemMatcher negate() {
        return new ItemMatchers.Not(this);
    }

    /**
     * Matches every stack of an item, regardless of damage, block state or NBT.
     */
    static @NonNull ItemMatcher id(@NonNull String identifier) {
        return new ItemMatchers.Id(identifier);
    }

    /**
     * Matches every stack of an item type, like {@link #id(String)}.
     *
     * @since 0.1.0
     */
    static @NonNull ItemMatcher anyVariantOf(@NonNull BedrockItemType type) {
        return new ItemMatchers.Id(type.identifier());
    }

    /**
     * Matches the stacks of exactly that variant (a type is its base variant; see {@link #anyVariantOf} for any
     * variant).
     *
     * @since 0.1.0
     */
    static @NonNull ItemMatcher of(@NonNull ItemKey key) {
        BedrockItemVariant variant = key.variant();
        return new ItemMatchers.Id(variant.type().identifier()).and(new ItemMatchers.Damage(variant.aux()));
    }

    static @NonNull ItemMatcher damage(int damage) {
        return new ItemMatchers.Damage(damage);
    }

    static @NonNull ItemMatcher blockState(@NonNull NbtMap blockState) {
        return new ItemMatchers.BlockState(BlockStates.normalize(blockState));
    }

    /**
     * Matches stacks that carry this block state.
     *
     * @since 0.1.0
     */
    static @NonNull ItemMatcher blockState(@NonNull BedrockBlockState blockState) {
        return new ItemMatchers.BlockState(BlockStates.normalize(blockState.toNbt()));
    }

    /**
     * Matches stacks equal to the given stack in every respect, including count and NBT.
     */
    static @NonNull ItemMatcher exact(@NonNull BedrockItemStack stack) {
        return new ItemMatchers.Exact(stack);
    }

    static @NonNull ItemMatcher namespace(@NonNull String namespace) {
        return new ItemMatchers.Namespace(namespace.endsWith(":") ? namespace : namespace + ":");
    }

    static @NonNull ItemMatcher of(@NonNull String description, @NonNull Predicate<BedrockItemStack> predicate) {
        return new ItemMatchers.Custom(description, predicate);
    }
}
