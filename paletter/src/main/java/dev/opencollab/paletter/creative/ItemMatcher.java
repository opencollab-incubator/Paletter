package dev.opencollab.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.item.BedrockItemStack;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import dev.opencollab.paletter.vanilla.ItemKey;

import java.util.function.Predicate;

/**
 * Selects creative stacks, e.g. as an anchor to insert after, or to remove. Matchers print readably, so a placement
 * that found nothing can be reported as {@code after(minecraft:hopper_minecart) matched nothing}.
 * <pre>{@code
 * ItemMatcher.id("minecraft:empty_map").and(ItemMatcher.aux(2))
 * }</pre>
 *
 * @since 1.0.0
 */
public sealed interface ItemMatcher permits ItemMatchers.Id, ItemMatchers.Aux, ItemMatchers.BlockState,
        ItemMatchers.Exact, ItemMatchers.Namespace, ItemMatchers.And, ItemMatchers.Not, ItemMatchers.Custom {

    /**
     * Tests a stack.
     *
     * @param stack the stack
     * @return true if the stack is selected
     */
    boolean matches(@NonNull BedrockItemStack stack);

    /**
     * Combines this matcher with another one.
     *
     * @param other the other matcher
     * @return a matcher that selects the stacks both select
     */
    default @NonNull ItemMatcher and(@NonNull ItemMatcher other) {
        return new ItemMatchers.And(this, other);
    }

    /**
     * Inverts this matcher.
     *
     * @return a matcher that selects the stacks this one does not
     */
    default @NonNull ItemMatcher negate() {
        return new ItemMatchers.Not(this);
    }

    /**
     * Matches every stack of an item, regardless of aux, block state or NBT.
     *
     * @param identifier the item identifier, e.g. {@code minecraft:empty_map}
     * @return the matcher
     */
    static @NonNull ItemMatcher id(@NonNull String identifier) {
        return new ItemMatchers.Id(identifier);
    }

    /**
     * Matches every stack of an item type, like {@link #id(String)}.
     *
     * @param type the item type
     * @return the matcher
     * @since 1.0.0
     */
    static @NonNull ItemMatcher anyVariantOf(@NonNull BedrockItemType type) {
        return new ItemMatchers.Id(type.identifier());
    }

    /**
     * Matches the stacks of exactly that variant (a type is its base variant; see {@link #anyVariantOf} for any
     * variant).
     *
     * @param key a variant, or a type for its base variant
     * @return the matcher
     * @since 1.0.0
     */
    static @NonNull ItemMatcher of(@NonNull ItemKey key) {
        BedrockItemVariant variant = key.variant();
        return new ItemMatchers.Id(variant.type().identifier()).and(new ItemMatchers.Aux(variant.aux()));
    }

    /**
     * Matches the stacks whose variant has an aux value.
     *
     * @param aux the aux value
     * @return the matcher
     */
    static @NonNull ItemMatcher aux(int aux) {
        return new ItemMatchers.Aux(aux);
    }

    /**
     * Matches stacks that carry this block state, given as NBT; the NBT is normalized first.
     *
     * @param blockState the block state NBT
     * @return the matcher
     */
    static @NonNull ItemMatcher blockState(@NonNull NbtMap blockState) {
        return new ItemMatchers.BlockState(BlockStates.normalize(blockState));
    }

    /**
     * Matches stacks that carry this block state.
     *
     * @param blockState the block state
     * @return the matcher
     * @since 1.0.0
     */
    static @NonNull ItemMatcher blockState(@NonNull BedrockBlockState blockState) {
        return new ItemMatchers.BlockState(BlockStates.normalize(blockState.toNbt()));
    }

    /**
     * Matches stacks equal to the given stack in every respect, including count and NBT.
     *
     * @param stack the stack
     * @return the matcher
     */
    static @NonNull ItemMatcher exact(@NonNull BedrockItemStack stack) {
        return new ItemMatchers.Exact(stack);
    }

    /**
     * Matches every stack of an item in a namespace.
     *
     * @param namespace the namespace, with or without the trailing colon
     * @return the matcher
     */
    static @NonNull ItemMatcher namespace(@NonNull String namespace) {
        return new ItemMatchers.Namespace(namespace.endsWith(":") ? namespace : namespace + ":");
    }

    /**
     * Matches the stacks a predicate accepts.
     *
     * @param description what the matcher selects, used when it is printed
     * @param predicate the test
     * @return the matcher
     * @throws NullPointerException if the predicate is null
     */
    static @NonNull ItemMatcher of(@NonNull String description, @NonNull Predicate<BedrockItemStack> predicate) {
        return new ItemMatchers.Custom(description, predicate);
    }
}
