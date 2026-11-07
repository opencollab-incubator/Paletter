package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A distance range in blocks, e.g. the reach of a weapon.
 *
 * @param min the minimum distance
 * @param max the maximum distance
 * @since 2.0.0
 */
public record Reach(float min, float max) {
    /**
     * The default reach of a weapon: 0 to 3 blocks.
     */
    public static final Reach DEFAULT = new Reach(0, 3);

    public Reach {
        if (min > max) {
            throw new IllegalArgumentException("Reach min " + min + " is above max " + max);
        }
    }

    public static @NonNull Reach of(float min, float max) {
        return new Reach(min, max);
    }
}
