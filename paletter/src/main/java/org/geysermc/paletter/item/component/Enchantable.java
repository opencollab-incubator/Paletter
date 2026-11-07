package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;

/**
 * Makes an item enchantable (add-on JSON {@code minecraft:enchantable}).
 *
 * @since 2.0.0
 */
public final class Enchantable {
    /**
     * The slot every enchantment applies to.
     */
    public static final String ALL = "all";

    private final String slot;
    private final int value;

    private Enchantable(String slot, int value) {
        this.slot = Objects.requireNonNull(slot, "slot");
        if (value < 0 || value > 127) {
            throw new IllegalArgumentException("Enchantability must be 0 to 127: " + value);
        }
        this.value = value;
    }

    /**
     * @param slot the enchantment slot, as in add-on JSON: {@code all}, {@code sword}, {@code armor_head},
     *             {@code pickaxe}, {@code g_tool}, ...
     * @param value the enchantability: higher values give better enchantments
     */
    public static @NonNull Enchantable of(@NonNull String slot, int value) {
        return new Enchantable(slot, value);
    }

    public @NonNull String slot() {
        return slot;
    }

    public int value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Enchantable other && slot.equals(other.slot) && value == other.value;
    }

    @Override
    public int hashCode() {
        return slot.hashCode() * 31 + value;
    }

    @Override
    public String toString() {
        return "Enchantable[" + slot + ", " + value + "]";
    }
}
