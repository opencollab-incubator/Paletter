package org.geysermc.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Selects creative stacks, e.g. as an anchor to insert after, or to remove. Matchers print readably, so a placement
 * that found nothing can be reported as {@code after(minecraft:hopper_minecart) matched nothing}.
 * <pre>{@code
 * ItemMatcher.id("minecraft:empty_map").and(ItemMatcher.damage(2))
 * }</pre>
 *
 * @since 1.0.0
 */
@FunctionalInterface
public interface ItemMatcher {

    boolean matches(@NonNull CreativeStack stack);

    default @NonNull ItemMatcher and(@NonNull ItemMatcher other) {
        return new And(this, other);
    }

    default @NonNull ItemMatcher negate() {
        return new Not(this);
    }

    /**
     * Matches every stack of an item, regardless of damage, block state or NBT.
     */
    static @NonNull ItemMatcher id(@NonNull String identifier) {
        return new Id(identifier);
    }

    /**
     * Matches every stack of an item type, like {@link #id(String)}.
     *
     * @since 1.0.0
     */
    static @NonNull ItemMatcher of(@NonNull BedrockItemType type) {
        return new Id(type.identifier());
    }

    /**
     * Matches the stacks of a variant: its item and aux value.
     *
     * @since 1.0.0
     */
    static @NonNull ItemMatcher of(@NonNull BedrockItemVariant variant) {
        return new Id(variant.type().identifier()).and(new Damage(variant.aux()));
    }

    static @NonNull ItemMatcher damage(int damage) {
        return new Damage(damage);
    }

    static @NonNull ItemMatcher blockState(@NonNull NbtMap blockState) {
        return new BlockState(BlockStates.normalize(blockState));
    }

    /**
     * Matches stacks equal to the given stack in every respect, including count and NBT.
     */
    static @NonNull ItemMatcher exact(@NonNull CreativeStack stack) {
        return new Exact(stack);
    }

    static @NonNull ItemMatcher namespace(@NonNull String namespace) {
        return new Namespace(namespace.endsWith(":") ? namespace : namespace + ":");
    }

    static @NonNull ItemMatcher of(@NonNull String description, @NonNull Predicate<CreativeStack> predicate) {
        return new Custom(description, predicate);
    }

    record Id(@NonNull String identifier) implements ItemMatcher {
        @Override
        public boolean matches(@NonNull CreativeStack stack) {
            return stack.identifier().equals(identifier);
        }

        @Override
        public String toString() {
            return identifier;
        }
    }

    record Damage(int damage) implements ItemMatcher {
        @Override
        public boolean matches(@NonNull CreativeStack stack) {
            return stack.damage() == damage;
        }

        @Override
        public String toString() {
            return "damage=" + damage;
        }
    }

    record BlockState(@NonNull NbtMap blockState) implements ItemMatcher {
        @Override
        public boolean matches(@NonNull CreativeStack stack) {
            return blockState.equals(stack.blockState());
        }

        @Override
        public String toString() {
            return "block=" + blockState;
        }
    }

    record Exact(@NonNull CreativeStack stack) implements ItemMatcher {
        @Override
        public boolean matches(@NonNull CreativeStack other) {
            return stack.equals(other);
        }

        @Override
        public String toString() {
            return "exactly " + stack;
        }
    }

    record Namespace(@NonNull String prefix) implements ItemMatcher {
        @Override
        public boolean matches(@NonNull CreativeStack stack) {
            return stack.identifier().startsWith(prefix);
        }

        @Override
        public String toString() {
            return prefix + "*";
        }
    }

    record And(@NonNull ItemMatcher first, @NonNull ItemMatcher second) implements ItemMatcher {
        @Override
        public boolean matches(@NonNull CreativeStack stack) {
            return first.matches(stack) && second.matches(stack);
        }

        @Override
        public String toString() {
            return first + " & " + second;
        }
    }

    record Not(@NonNull ItemMatcher matcher) implements ItemMatcher {
        @Override
        public boolean matches(@NonNull CreativeStack stack) {
            return !matcher.matches(stack);
        }

        @Override
        public String toString() {
            return "not(" + matcher + ")";
        }
    }

    record Custom(@NonNull String description, @NonNull Predicate<CreativeStack> predicate) implements ItemMatcher {
        public Custom {
            Objects.requireNonNull(predicate);
        }

        @Override
        public boolean matches(@NonNull CreativeStack stack) {
            return predicate.test(stack);
        }

        @Override
        public String toString() {
            return description;
        }
    }
}
