package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * How using an item behaves (add-on JSON {@code minecraft:use_modifiers}).
 *
 * @since 2.0.0
 */
public final class UseModifiers {
    private final float useDuration;
    private final @Nullable Float movementModifier;

    private UseModifiers(float useDuration, @Nullable Float movementModifier) {
        this.useDuration = useDuration;
        this.movementModifier = movementModifier;
    }

    /**
     * @param seconds how long the item is used, e.g. how long eating takes
     */
    public static @NonNull UseModifiers of(float seconds) {
        return new UseModifiers(seconds, null);
    }

    /**
     * @param modifier the factor applied to the movement speed of the player using the item
     */
    public @NonNull UseModifiers movementModifier(float modifier) {
        return new UseModifiers(useDuration, modifier);
    }

    /**
     * @return the use duration in seconds
     */
    public float useDuration() {
        return useDuration;
    }

    public @Nullable Float movementModifier() {
        return movementModifier;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UseModifiers other && useDuration == other.useDuration && Objects.equals(movementModifier, other.movementModifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(useDuration, movementModifier);
    }

    @Override
    public String toString() {
        return "UseModifiers[" + useDuration + " s, movement " + movementModifier + "]";
    }
}
