package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;

/**
 * Makes an item wearable (add-on JSON {@code minecraft:wearable}). The client limits wearable items to a stack of one
 * unless a max stack size is set as well.
 *
 * @since 2.0.0
 */
public final class Wearable {
    private final EquipmentSlot slot;
    private final int protection;

    private Wearable(EquipmentSlot slot, int protection) {
        this.slot = Objects.requireNonNull(slot, "slot");
        this.protection = protection;
    }

    /**
     * @param slot where the item is worn
     * @param protection the armour points it gives
     */
    public static @NonNull Wearable of(@NonNull EquipmentSlot slot, int protection) {
        return new Wearable(slot, protection);
    }

    public @NonNull EquipmentSlot slot() {
        return slot;
    }

    public int protection() {
        return protection;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Wearable other && slot == other.slot && protection == other.protection;
    }

    @Override
    public int hashCode() {
        return slot.hashCode() * 31 + protection;
    }

    @Override
    public String toString() {
        return "Wearable[" + slot + ", " + protection + "]";
    }
}
