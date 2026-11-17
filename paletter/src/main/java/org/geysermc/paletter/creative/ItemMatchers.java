package org.geysermc.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.item.BedrockItemStack;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * The implementations of {@link ItemMatcher}, which are not part of the API.
 */
final class ItemMatchers {
    private ItemMatchers() {
    }

    record Id(@NonNull String identifier) implements ItemMatcher {
        @Override
        public boolean matches(@NonNull BedrockItemStack stack) {
            return stack.type().identifier().equals(identifier);
        }

        @Override
        public String toString() {
            return identifier;
        }
    }

    record Damage(int damage) implements ItemMatcher {
        @Override
        public boolean matches(@NonNull BedrockItemStack stack) {
            return stack.variant().aux() == damage;
        }

        @Override
        public String toString() {
            return "damage=" + damage;
        }
    }

    record BlockState(@NonNull NbtMap blockState) implements ItemMatcher {
        @Override
        public boolean matches(@NonNull BedrockItemStack stack) {
            return stack.blockState() != null && blockState.equals(stack.blockState().toNbt());
        }

        @Override
        public String toString() {
            return "block=" + blockState;
        }
    }

    record Exact(@NonNull BedrockItemStack stack) implements ItemMatcher {
        @Override
        public boolean matches(@NonNull BedrockItemStack other) {
            return stack.equals(other);
        }

        @Override
        public String toString() {
            return "exactly " + stack;
        }
    }

    record Namespace(@NonNull String prefix) implements ItemMatcher {
        @Override
        public boolean matches(@NonNull BedrockItemStack stack) {
            return stack.type().identifier().startsWith(prefix);
        }

        @Override
        public String toString() {
            return prefix + "*";
        }
    }

    record And(@NonNull ItemMatcher first, @NonNull ItemMatcher second) implements ItemMatcher {
        @Override
        public boolean matches(@NonNull BedrockItemStack stack) {
            return first.matches(stack) && second.matches(stack);
        }

        @Override
        public String toString() {
            return first + " & " + second;
        }
    }

    record Not(@NonNull ItemMatcher matcher) implements ItemMatcher {
        @Override
        public boolean matches(@NonNull BedrockItemStack stack) {
            return !matcher.matches(stack);
        }

        @Override
        public String toString() {
            return "not(" + matcher + ")";
        }
    }

    record Custom(@NonNull String description, @NonNull Predicate<BedrockItemStack> predicate) implements ItemMatcher {
        public Custom {
            Objects.requireNonNull(predicate);
        }

        @Override
        public boolean matches(@NonNull BedrockItemStack stack) {
            return predicate.test(stack);
        }

        @Override
        public String toString() {
            return description;
        }
    }
}
