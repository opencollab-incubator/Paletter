package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;

/**
 * An item cooldown (add-on JSON {@code minecraft:cooldown}). Items sharing a category share their cooldown.
 *
 * @since 2.0.0
 */
public final class Cooldown {
    private final String category;
    private final float seconds;
    private final CooldownType type;

    private Cooldown(String category, float seconds, CooldownType type) {
        this.category = Objects.requireNonNull(category, "category");
        this.seconds = seconds;
        this.type = Objects.requireNonNull(type, "type");
    }

    /**
     * @param category the cooldown category, e.g. {@code minecraft:ender_pearl}
     * @param seconds the cooldown duration
     * @return a cooldown started by using the item
     */
    public static @NonNull Cooldown of(@NonNull String category, float seconds) {
        return new Cooldown(category, seconds, CooldownType.USE);
    }

    public @NonNull Cooldown type(@NonNull CooldownType type) {
        return new Cooldown(category, seconds, type);
    }

    public @NonNull String category() {
        return category;
    }

    public float seconds() {
        return seconds;
    }

    public @NonNull CooldownType type() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Cooldown other && category.equals(other.category) && seconds == other.seconds && type == other.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(category, seconds, type);
    }

    @Override
    public String toString() {
        return "Cooldown[" + category + ", " + seconds + " s, " + type + "]";
    }
}
