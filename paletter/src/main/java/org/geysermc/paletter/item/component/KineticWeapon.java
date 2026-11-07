package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * A charged weapon like the spear (add-on JSON {@code minecraft:kinetic_weapon}): while the item is held in use, it
 * deals damage, knockback or dismounts depending on the relative speed of attacker and target.
 * <pre>{@code
 * KineticWeapon.builder()
 *     .delayTicks(12)
 *     .reach(Reach.of(2, 4.5f))
 *     .damageConditions(KineticWeapon.Conditions.of(300, 0, 4.6f))
 *     .build()
 * }</pre>
 *
 * @since 2.0.0
 */
public final class KineticWeapon {
    private final int delayTicks;
    private final Reach reach;
    private final @Nullable Reach creativeReach;
    private final float hitboxMargin;
    private final float damageMultiplier;
    private final float damageModifier;
    private final @Nullable Conditions damageConditions;
    private final @Nullable Conditions knockbackConditions;
    private final @Nullable Conditions dismountConditions;

    private KineticWeapon(Builder builder) {
        this.delayTicks = builder.delayTicks;
        this.reach = builder.reach;
        this.creativeReach = builder.creativeReach;
        this.hitboxMargin = builder.hitboxMargin;
        this.damageMultiplier = builder.damageMultiplier;
        this.damageModifier = builder.damageModifier;
        this.damageConditions = builder.damageConditions;
        this.knockbackConditions = builder.knockbackConditions;
        this.dismountConditions = builder.dismountConditions;
    }

    public static @NonNull Builder builder() {
        return new Builder();
    }

    public @NonNull Builder toBuilder() {
        Builder builder = new Builder();
        builder.delayTicks = delayTicks;
        builder.reach = reach;
        builder.creativeReach = creativeReach;
        builder.hitboxMargin = hitboxMargin;
        builder.damageMultiplier = damageMultiplier;
        builder.damageModifier = damageModifier;
        builder.damageConditions = damageConditions;
        builder.knockbackConditions = knockbackConditions;
        builder.dismountConditions = dismountConditions;
        return builder;
    }

    /**
     * @return ticks after starting to use the item before it becomes active
     */
    public int delayTicks() {
        return delayTicks;
    }

    public @NonNull Reach reach() {
        return reach;
    }

    /**
     * @return the reach in creative mode, or null for the same as {@link #reach()}
     */
    public @Nullable Reach creativeReach() {
        return creativeReach;
    }

    public float hitboxMargin() {
        return hitboxMargin;
    }

    public float damageMultiplier() {
        return damageMultiplier;
    }

    public float damageModifier() {
        return damageModifier;
    }

    public @Nullable Conditions damageConditions() {
        return damageConditions;
    }

    public @Nullable Conditions knockbackConditions() {
        return knockbackConditions;
    }

    public @Nullable Conditions dismountConditions() {
        return dismountConditions;
    }

    /**
     * When an effect of a kinetic weapon applies.
     *
     * @since 2.0.0
     */
    public static final class Conditions {
        private final int maxDurationTicks;
        private final float minSpeed;
        private final float minRelativeSpeed;

        private Conditions(int maxDurationTicks, float minSpeed, float minRelativeSpeed) {
            this.maxDurationTicks = maxDurationTicks;
            this.minSpeed = minSpeed;
            this.minRelativeSpeed = minRelativeSpeed;
        }

        /**
         * @param maxDurationTicks for how many ticks of use the effect applies, or -1 for no limit
         * @param minSpeed the minimum speed of the attacker, in blocks per second
         * @param minRelativeSpeed the minimum speed of the attacker relative to the target, in blocks per second
         */
        public static @NonNull Conditions of(int maxDurationTicks, float minSpeed, float minRelativeSpeed) {
            return new Conditions(maxDurationTicks, minSpeed, minRelativeSpeed);
        }

        public int maxDurationTicks() {
            return maxDurationTicks;
        }

        public float minSpeed() {
            return minSpeed;
        }

        public float minRelativeSpeed() {
            return minRelativeSpeed;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Conditions other && maxDurationTicks == other.maxDurationTicks && minSpeed == other.minSpeed
                && minRelativeSpeed == other.minRelativeSpeed;
        }

        @Override
        public int hashCode() {
            return Objects.hash(maxDurationTicks, minSpeed, minRelativeSpeed);
        }

        @Override
        public String toString() {
            return "Conditions[" + maxDurationTicks + " ticks, speed " + minSpeed + ", relative " + minRelativeSpeed + "]";
        }
    }

    /**
     * @since 2.0.0
     */
    public static final class Builder {
        private int delayTicks;
        private Reach reach = Reach.DEFAULT;
        private @Nullable Reach creativeReach;
        private float hitboxMargin;
        private float damageMultiplier = 1;
        private float damageModifier;
        private @Nullable Conditions damageConditions;
        private @Nullable Conditions knockbackConditions;
        private @Nullable Conditions dismountConditions;

        private Builder() {
        }

        public @NonNull Builder delayTicks(int delayTicks) {
            this.delayTicks = delayTicks;
            return this;
        }

        /**
         * @param reach the reach, {@link Reach#DEFAULT} by default
         */
        public @NonNull Builder reach(@NonNull Reach reach) {
            this.reach = Objects.requireNonNull(reach, "reach");
            return this;
        }

        public @NonNull Builder creativeReach(@Nullable Reach creativeReach) {
            this.creativeReach = creativeReach;
            return this;
        }

        public @NonNull Builder hitboxMargin(float hitboxMargin) {
            this.hitboxMargin = hitboxMargin;
            return this;
        }

        /**
         * @param damageMultiplier multiplies the relative speed into damage, 1 by default
         */
        public @NonNull Builder damageMultiplier(float damageMultiplier) {
            this.damageMultiplier = damageMultiplier;
            return this;
        }

        public @NonNull Builder damageModifier(float damageModifier) {
            this.damageModifier = damageModifier;
            return this;
        }

        public @NonNull Builder damageConditions(@Nullable Conditions conditions) {
            this.damageConditions = conditions;
            return this;
        }

        public @NonNull Builder knockbackConditions(@Nullable Conditions conditions) {
            this.knockbackConditions = conditions;
            return this;
        }

        public @NonNull Builder dismountConditions(@Nullable Conditions conditions) {
            this.dismountConditions = conditions;
            return this;
        }

        public @NonNull KineticWeapon build() {
            return new KineticWeapon(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof KineticWeapon other && delayTicks == other.delayTicks && reach.equals(other.reach)
            && Objects.equals(creativeReach, other.creativeReach) && hitboxMargin == other.hitboxMargin
            && damageMultiplier == other.damageMultiplier && damageModifier == other.damageModifier
            && Objects.equals(damageConditions, other.damageConditions) && Objects.equals(knockbackConditions, other.knockbackConditions)
            && Objects.equals(dismountConditions, other.dismountConditions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(delayTicks, reach, creativeReach, hitboxMargin, damageMultiplier, damageModifier, damageConditions,
            knockbackConditions, dismountConditions);
    }

    @Override
    public String toString() {
        return "KineticWeapon[delay " + delayTicks + ", reach " + reach + ", creative " + creativeReach + ", margin " + hitboxMargin
            + ", damage x" + damageMultiplier + " +" + damageModifier + ", damage " + damageConditions + ", knockback "
            + knockbackConditions + ", dismount " + dismountConditions + "]";
    }
}
