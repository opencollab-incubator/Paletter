package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.annotation.Experimental;

import java.util.Objects;

/**
 * Where a held item is rendered (the former add-on JSON {@code minecraft:render_offsets}).
 * <p>
 * Experimental: no longer an add-on component in 1.26 JSON, so a vanilla server never sends it; whether current clients
 * still read it is unverified.
 *
 * @param mainHand offsets in the main hand
 * @param offHand offsets in the off hand
 * @since 2.0.0
 */
@Experimental
public record RenderOffsets(@NonNull Hand mainHand, @NonNull Hand offHand) {
    public RenderOffsets {
        Objects.requireNonNull(mainHand, "mainHand");
        Objects.requireNonNull(offHand, "offHand");
    }

    /**
     * @param firstPerson the transform in first person
     * @param thirdPerson the transform in third person
     * @since 2.0.0
     */
    public record Hand(@NonNull Transform firstPerson, @NonNull Transform thirdPerson) {
        public static final Hand IDENTITY = new Hand(Transform.IDENTITY, Transform.IDENTITY);

        public Hand {
            Objects.requireNonNull(firstPerson, "firstPerson");
            Objects.requireNonNull(thirdPerson, "thirdPerson");
        }
    }

    /**
     * @param positionX translation, x
     * @param positionY translation, y
     * @param positionZ translation, z
     * @param rotationX rotation in degrees, x
     * @param rotationY rotation in degrees, y
     * @param rotationZ rotation in degrees, z
     * @param scaleX scale, x
     * @param scaleY scale, y
     * @param scaleZ scale, z
     * @since 2.0.0
     */
    public record Transform(float positionX, float positionY, float positionZ, float rotationX, float rotationY, float rotationZ,
                            float scaleX, float scaleY, float scaleZ) {
        public static final Transform IDENTITY = new Transform(0, 0, 0, 0, 0, 0, 1, 1, 1);
    }
}
