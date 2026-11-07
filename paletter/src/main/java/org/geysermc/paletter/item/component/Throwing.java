package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * Makes an item throwable by holding the use button (add-on JSON {@code minecraft:throwable}). Settings left unset use the
 * client's defaults.
 *
 * @since 2.0.0
 */
public final class Throwing {
    private final boolean swingAnimation;
    private final @Nullable Float launchPowerScale;
    private final @Nullable Float maxLaunchPower;
    private final @Nullable Float minDrawDuration;
    private final @Nullable Float maxDrawDuration;
    private final @Nullable Boolean scalePowerByDrawDuration;

    private Throwing(boolean swingAnimation, @Nullable Float launchPowerScale, @Nullable Float maxLaunchPower,
                      @Nullable Float minDrawDuration, @Nullable Float maxDrawDuration, @Nullable Boolean scalePowerByDrawDuration) {
        this.swingAnimation = swingAnimation;
        this.launchPowerScale = launchPowerScale;
        this.maxLaunchPower = maxLaunchPower;
        this.minDrawDuration = minDrawDuration;
        this.maxDrawDuration = maxDrawDuration;
        this.scalePowerByDrawDuration = scalePowerByDrawDuration;
    }

    /**
     * @param swingAnimation whether the player swings the arm when throwing
     */
    public static @NonNull Throwing of(boolean swingAnimation) {
        return new Throwing(swingAnimation, null, null, null, null, null);
    }

    public @NonNull Throwing launchPowerScale(float scale) {
        return new Throwing(swingAnimation, scale, maxLaunchPower, minDrawDuration, maxDrawDuration, scalePowerByDrawDuration);
    }

    public @NonNull Throwing maxLaunchPower(float power) {
        return new Throwing(swingAnimation, launchPowerScale, power, minDrawDuration, maxDrawDuration, scalePowerByDrawDuration);
    }

    /**
     * @param minSeconds the minimum draw duration
     * @param maxSeconds the draw duration for full power
     */
    public @NonNull Throwing drawDuration(float minSeconds, float maxSeconds) {
        return new Throwing(swingAnimation, launchPowerScale, maxLaunchPower, minSeconds, maxSeconds, scalePowerByDrawDuration);
    }

    public @NonNull Throwing scalePowerByDrawDuration(boolean scale) {
        return new Throwing(swingAnimation, launchPowerScale, maxLaunchPower, minDrawDuration, maxDrawDuration, scale);
    }

    public boolean swingAnimation() {
        return swingAnimation;
    }

    public @Nullable Float launchPowerScale() {
        return launchPowerScale;
    }

    public @Nullable Float maxLaunchPower() {
        return maxLaunchPower;
    }

    public @Nullable Float minDrawDuration() {
        return minDrawDuration;
    }

    public @Nullable Float maxDrawDuration() {
        return maxDrawDuration;
    }

    public @Nullable Boolean scalePowerByDrawDuration() {
        return scalePowerByDrawDuration;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Throwing other && swingAnimation == other.swingAnimation
            && Objects.equals(launchPowerScale, other.launchPowerScale) && Objects.equals(maxLaunchPower, other.maxLaunchPower)
            && Objects.equals(minDrawDuration, other.minDrawDuration) && Objects.equals(maxDrawDuration, other.maxDrawDuration)
            && Objects.equals(scalePowerByDrawDuration, other.scalePowerByDrawDuration);
    }

    @Override
    public int hashCode() {
        return Objects.hash(swingAnimation, launchPowerScale, maxLaunchPower, minDrawDuration, maxDrawDuration, scalePowerByDrawDuration);
    }

    @Override
    public String toString() {
        return "Throwing[swing " + swingAnimation + ", scale " + launchPowerScale + ", max power " + maxLaunchPower + ", draw "
            + minDrawDuration + ".." + maxDrawDuration + ", scale by draw " + scalePowerByDrawDuration + "]";
    }
}
