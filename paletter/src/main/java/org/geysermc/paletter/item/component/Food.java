package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * Makes an item edible (add-on JSON {@code minecraft:food}). Combine with {@code useAnimation(EAT)} and
 * {@code useModifiers(...)} for the eating animation and duration.
 *
 * @since 2.0.0
 */
public final class Food {
    private final int nutrition;
    private final float saturationModifier;
    private final boolean canAlwaysEat;
    private final @Nullable String usingConvertsTo;

    private Food(int nutrition, float saturationModifier, boolean canAlwaysEat, @Nullable String usingConvertsTo) {
        this.nutrition = nutrition;
        this.saturationModifier = saturationModifier;
        this.canAlwaysEat = canAlwaysEat;
        this.usingConvertsTo = usingConvertsTo;
    }

    /**
     * @param nutrition the hunger points restored
     * @param saturationModifier the saturation modifier (add-on JSON default 0.6)
     * @return food that can only be eaten when hungry
     */
    public static @NonNull Food of(int nutrition, float saturationModifier) {
        return new Food(nutrition, saturationModifier, false, null);
    }

    public @NonNull Food canAlwaysEat(boolean canAlwaysEat) {
        return new Food(nutrition, saturationModifier, canAlwaysEat, usingConvertsTo);
    }

    /**
     * @param item the identifier of the item left after eating, e.g. {@code minecraft:bowl}, or null for none
     */
    public @NonNull Food usingConvertsTo(@Nullable String item) {
        return new Food(nutrition, saturationModifier, canAlwaysEat, item);
    }

    public int nutrition() {
        return nutrition;
    }

    public float saturationModifier() {
        return saturationModifier;
    }

    public boolean canAlwaysEat() {
        return canAlwaysEat;
    }

    public @Nullable String usingConvertsTo() {
        return usingConvertsTo;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Food other && nutrition == other.nutrition && saturationModifier == other.saturationModifier
            && canAlwaysEat == other.canAlwaysEat && Objects.equals(usingConvertsTo, other.usingConvertsTo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nutrition, saturationModifier, canAlwaysEat, usingConvertsTo);
    }

    @Override
    public String toString() {
        return "Food[" + nutrition + ", " + saturationModifier + ", always " + canAlwaysEat + ", converts to " + usingConvertsTo + "]";
    }
}
