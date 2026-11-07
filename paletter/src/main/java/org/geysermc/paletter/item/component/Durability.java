package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * How much use an item takes before it breaks (add-on JSON {@code minecraft:durability}).
 * <pre>{@code
 * Durability.of(250)                     // takes damage on every use
 * Durability.of(250).damageChance(20, 60) // takes damage on 20 % to 60 % of uses
 * }</pre>
 *
 * @since 2.0.0
 */
public final class Durability {
    private final int maxDurability;
    private final int minDamageChance;
    private final int maxDamageChance;

    private Durability(int maxDurability, int minDamageChance, int maxDamageChance) {
        if (maxDurability < 0) {
            throw new IllegalArgumentException("Max durability must not be negative: " + maxDurability);
        }
        if (minDamageChance < 0 || maxDamageChance > 100 || minDamageChance > maxDamageChance) {
            throw new IllegalArgumentException("Damage chance must satisfy 0 <= min <= max <= 100: " + minDamageChance + ", " + maxDamageChance);
        }
        this.maxDurability = maxDurability;
        this.minDamageChance = minDamageChance;
        this.maxDamageChance = maxDamageChance;
    }

    /**
     * @param maxDurability how many uses the item survives
     * @return a durability that takes damage on every use (a damage chance of 100 %)
     */
    public static @NonNull Durability of(int maxDurability) {
        return new Durability(maxDurability, 100, 100);
    }

    /**
     * @param minPercent the lower bound of the chance, in percent, that a use damages the item
     * @param maxPercent the upper bound, in percent; the client picks a chance in this range
     * @return a copy with this damage chance
     */
    public @NonNull Durability damageChance(int minPercent, int maxPercent) {
        return new Durability(maxDurability, minPercent, maxPercent);
    }

    public int maxDurability() {
        return maxDurability;
    }

    /**
     * @return the lower bound of the damage chance, in percent
     */
    public int minDamageChance() {
        return minDamageChance;
    }

    /**
     * @return the upper bound of the damage chance, in percent
     */
    public int maxDamageChance() {
        return maxDamageChance;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Durability other && maxDurability == other.maxDurability
            && minDamageChance == other.minDamageChance && maxDamageChance == other.maxDamageChance;
    }

    @Override
    public int hashCode() {
        return (maxDurability * 31 + minDamageChance) * 31 + maxDamageChance;
    }

    @Override
    public String toString() {
        return "Durability[" + maxDurability + ", damage chance " + minDamageChance + ".." + maxDamageChance + " %]";
    }
}
