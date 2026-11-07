package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A charged ranged weapon like a bow or crossbow (add-on JSON {@code minecraft:shooter}).
 *
 * @since 2.0.0
 */
public final class Shooter {
    private final List<Ammunition> ammunition;
    private final float maxDrawDuration;
    private final boolean chargeOnDraw;
    private final boolean scalePowerByDrawDuration;

    private Shooter(List<Ammunition> ammunition, float maxDrawDuration, boolean chargeOnDraw, boolean scalePowerByDrawDuration) {
        this.ammunition = List.copyOf(ammunition);
        this.maxDrawDuration = maxDrawDuration;
        this.chargeOnDraw = chargeOnDraw;
        this.scalePowerByDrawDuration = scalePowerByDrawDuration;
    }

    /**
     * @param ammunition what the shooter fires, in order of preference
     * @return a shooter that scales its power by draw duration, does not charge on draw and has no max draw duration
     */
    public static @NonNull Shooter of(@NonNull Ammunition... ammunition) {
        return new Shooter(List.of(ammunition), 0, false, true);
    }

    /**
     * @param seconds how long drawing takes until full power
     */
    public @NonNull Shooter maxDrawDuration(float seconds) {
        return new Shooter(ammunition, seconds, chargeOnDraw, scalePowerByDrawDuration);
    }

    /**
     * @param chargeOnDraw whether the shooter is charged while drawn, like a crossbow
     */
    public @NonNull Shooter chargeOnDraw(boolean chargeOnDraw) {
        return new Shooter(ammunition, maxDrawDuration, chargeOnDraw, scalePowerByDrawDuration);
    }

    public @NonNull Shooter scalePowerByDrawDuration(boolean scale) {
        return new Shooter(ammunition, maxDrawDuration, chargeOnDraw, scale);
    }

    public @NonNull Shooter withAmmunition(@NonNull Ammunition ammunition) {
        List<Ammunition> all = new ArrayList<>(this.ammunition);
        all.add(ammunition);
        return new Shooter(all, maxDrawDuration, chargeOnDraw, scalePowerByDrawDuration);
    }

    public @NonNull List<Ammunition> ammunition() {
        return ammunition;
    }

    public float maxDrawDuration() {
        return maxDrawDuration;
    }

    public boolean chargeOnDraw() {
        return chargeOnDraw;
    }

    public boolean scalePowerByDrawDuration() {
        return scalePowerByDrawDuration;
    }

    /**
     * One kind of ammunition.
     *
     * @since 2.0.0
     */
    public static final class Ammunition {
        private final String item;
        private final boolean useOffhand;
        private final boolean searchInventory;
        private final boolean useInCreative;

        private Ammunition(String item, boolean useOffhand, boolean searchInventory, boolean useInCreative) {
            this.item = Objects.requireNonNull(item, "item");
            this.useOffhand = useOffhand;
            this.searchInventory = searchInventory;
            this.useInCreative = useInCreative;
        }

        /**
         * @param item the ammunition item, e.g. {@code minecraft:arrow}
         * @return ammunition taken from the off hand or the inventory, not required in creative mode
         */
        public static @NonNull Ammunition of(@NonNull String item) {
            return new Ammunition(item, true, true, false);
        }

        public @NonNull Ammunition useOffhand(boolean useOffhand) {
            return new Ammunition(item, useOffhand, searchInventory, useInCreative);
        }

        public @NonNull Ammunition searchInventory(boolean searchInventory) {
            return new Ammunition(item, useOffhand, searchInventory, useInCreative);
        }

        public @NonNull Ammunition useInCreative(boolean useInCreative) {
            return new Ammunition(item, useOffhand, searchInventory, useInCreative);
        }

        public @NonNull String item() {
            return item;
        }

        public boolean useOffhand() {
            return useOffhand;
        }

        public boolean searchInventory() {
            return searchInventory;
        }

        public boolean useInCreative() {
            return useInCreative;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Ammunition other && item.equals(other.item) && useOffhand == other.useOffhand
                && searchInventory == other.searchInventory && useInCreative == other.useInCreative;
        }

        @Override
        public int hashCode() {
            return Objects.hash(item, useOffhand, searchInventory, useInCreative);
        }

        @Override
        public String toString() {
            return "Ammunition[" + item + ", offhand " + useOffhand + ", inventory " + searchInventory + ", creative " + useInCreative + "]";
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Shooter other && ammunition.equals(other.ammunition) && maxDrawDuration == other.maxDrawDuration
            && chargeOnDraw == other.chargeOnDraw && scalePowerByDrawDuration == other.scalePowerByDrawDuration;
    }

    @Override
    public int hashCode() {
        return Objects.hash(ammunition, maxDrawDuration, chargeOnDraw, scalePowerByDrawDuration);
    }

    @Override
    public String toString() {
        return "Shooter[" + ammunition + ", draw " + maxDrawDuration + " s, charge on draw " + chargeOnDraw + ", scale "
            + scalePowerByDrawDuration + "]";
    }
}
