package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * A weapon that hits every entity along its reach (add-on JSON {@code minecraft:piercing_weapon}).
 *
 * @since 2.0.0
 */
public final class PiercingWeapon {
    private final Reach reach;
    private final @Nullable Reach creativeReach;
    private final float hitboxMargin;

    private PiercingWeapon(Reach reach, @Nullable Reach creativeReach, float hitboxMargin) {
        this.reach = Objects.requireNonNull(reach, "reach");
        this.creativeReach = creativeReach;
        this.hitboxMargin = hitboxMargin;
    }

    public static @NonNull PiercingWeapon of(@NonNull Reach reach) {
        return new PiercingWeapon(reach, null, 0);
    }

    /**
     * @param creativeReach the reach in creative mode, or null for the same as {@link #reach()}
     */
    public @NonNull PiercingWeapon creativeReach(@Nullable Reach creativeReach) {
        return new PiercingWeapon(reach, creativeReach, hitboxMargin);
    }

    public @NonNull PiercingWeapon hitboxMargin(float hitboxMargin) {
        return new PiercingWeapon(reach, creativeReach, hitboxMargin);
    }

    public @NonNull Reach reach() {
        return reach;
    }

    public @Nullable Reach creativeReach() {
        return creativeReach;
    }

    public float hitboxMargin() {
        return hitboxMargin;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PiercingWeapon other && reach.equals(other.reach) && Objects.equals(creativeReach, other.creativeReach)
            && hitboxMargin == other.hitboxMargin;
    }

    @Override
    public int hashCode() {
        return Objects.hash(reach, creativeReach, hitboxMargin);
    }

    @Override
    public String toString() {
        return "PiercingWeapon[" + reach + ", creative " + creativeReach + ", margin " + hitboxMargin + "]";
    }
}
