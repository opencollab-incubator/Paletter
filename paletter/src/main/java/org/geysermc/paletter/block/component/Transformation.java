package org.geysermc.paletter.block.component;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Arrays;

/**
 * Rotates, scales and translates the model of a block (add-on JSON {@code minecraft:transformation}).
 * <pre>{@code
 * Transformation.rotation(0, 90, 0)
 * Transformation.builder().rotation(0, 180, 0).scale(0.5f, 0.5f, 0.5f).translation(0, 0.25f, 0).build()
 * }</pre>
 *
 * @since 2.0.0
 */
public final class Transformation {
    public static final Transformation IDENTITY = builder().build();

    private final float[] values;

    private Transformation(float[] values) {
        this.values = values;
    }

    public static @NonNull Builder builder() {
        return new Builder();
    }

    /**
     * @param x rotation around x in degrees; Bedrock only supports multiples of 90
     * @param y rotation around y in degrees
     * @param z rotation around z in degrees
     */
    public static @NonNull Transformation rotation(float x, float y, float z) {
        return builder().rotation(x, y, z).build();
    }

    public @NonNull Builder toBuilder() {
        Builder builder = new Builder();
        System.arraycopy(values, 0, builder.values, 0, values.length);
        return builder;
    }

    public float rotationX() {
        return values[0];
    }

    public float rotationY() {
        return values[1];
    }

    public float rotationZ() {
        return values[2];
    }

    public float scaleX() {
        return values[3];
    }

    public float scaleY() {
        return values[4];
    }

    public float scaleZ() {
        return values[5];
    }

    public float translationX() {
        return values[6];
    }

    public float translationY() {
        return values[7];
    }

    public float translationZ() {
        return values[8];
    }

    public float rotationPivotX() {
        return values[9];
    }

    public float rotationPivotY() {
        return values[10];
    }

    public float rotationPivotZ() {
        return values[11];
    }

    public float scalePivotX() {
        return values[12];
    }

    public float scalePivotY() {
        return values[13];
    }

    public float scalePivotZ() {
        return values[14];
    }

    /**
     * @return whether a rotation or scale pivot is set
     */
    public boolean hasPivots() {
        for (int i = 9; i < 15; i++) {
            if (values[i] != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * @since 2.0.0
     */
    public static final class Builder {
        private final float[] values = {0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0};

        private Builder() {
        }

        /**
         * Rotation in degrees; Bedrock only supports multiples of 90, other values are rounded down to one.
         */
        public @NonNull Builder rotation(float x, float y, float z) {
            return set(0, x, y, z);
        }

        public @NonNull Builder scale(float x, float y, float z) {
            return set(3, x, y, z);
        }

        /**
         * Translation in blocks.
         */
        public @NonNull Builder translation(float x, float y, float z) {
            return set(6, x, y, z);
        }

        public @NonNull Builder rotationPivot(float x, float y, float z) {
            return set(9, x, y, z);
        }

        public @NonNull Builder scalePivot(float x, float y, float z) {
            return set(12, x, y, z);
        }

        private Builder set(int offset, float x, float y, float z) {
            values[offset] = x;
            values[offset + 1] = y;
            values[offset + 2] = z;
            return this;
        }

        public @NonNull Transformation build() {
            return new Transformation(values.clone());
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Transformation other && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return "Transformation" + Arrays.toString(values);
    }
}
