package dev.opencollab.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * An add-on JSON vector {@code [x, y, z]}, shared by every component field that takes one ({@code CollisionBox},
 * {@code SelectionBox}, {@code Transformation}, {@code Leashable}, {@code RenderOffsets}). A record: three floats is the
 * shape of a vector, not of anything Bedrock can extend.
 *
 * @param x the x component
 * @param y the y component
 * @param z the z component
 * @since 1.0.0
 */
public record Vec3(float x, float y, float z) {
    /**
     * Creates a vector.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @return the vector
     * @since 1.0.0
     */
    public static @NonNull Vec3 of(float x, float y, float z) {
        return new Vec3(x, y, z);
    }
}
