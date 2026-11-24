package dev.opencollab.paletter.item.component;

import dev.opencollab.paletter.Vec3;
import dev.opencollab.paletter.annotation.Experimental;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * {@code minecraft:render_offsets}: where the item is drawn in each hand, per view.
 * <p>
 * Retired from the add-on format (the Bedrock Dedicated Server of Bedrock 26.60 rejects it at every {@code format_version}). Paletter
 * assumes current clients still honour it on the wire and keeps sending it for legacy content. It is {@link Experimental}:
 * outside the compatibility promise, so it can change or go in a minor release if clients stop honouring it.
 *
 * A {@link Preset} is the legacy form Geyser sends for armor converted from v1 custom mappings: a bare string
 * {@code render_offsets} (no {@code minecraft:} prefix, not a component) that names the client's built-in offsets of an
 * armor piece. It is written next to the hands (if any); with only a preset, {@code minecraft:render_offsets} is not sent.
 *
 * @see <a href="https://learn.microsoft.com/en-us/minecraft/creator/reference/content/itemreference/examples/itemcomponents/minecraft_render_offsets?view=minecraft-bedrock-stable">Microsoft Learn: minecraft:render_offsets</a>
 * @since 1.0.0
 */
@Experimental
public final class RenderOffsets implements WireOnlyItemComponent {
    /**
     * The component name.
     *
     * @since 1.0.0
     */
    public static final String NAME = "minecraft:render_offsets";

    private final @Nullable Hand mainHand;
    private final @Nullable Hand offHand;
    private final @Nullable Preset preset;

    private RenderOffsets(Builder builder) {
        this.mainHand = builder.mainHand;
        this.offHand = builder.offHand;
        this.preset = builder.preset;
    }

    /**
     * Creates a builder with nothing set.
     *
     * @return a builder of render offsets
     * @since 1.0.0
     */
    public static @NonNull Builder builder() {
        return new Builder();
    }

    /**
     * Creates render offsets that only name a built-in preset.
     *
     * @param preset the built-in offsets of an armor piece
     * @return render offsets that only name a preset
     * @since 1.0.0
     */
    public static @NonNull RenderOffsets preset(@NonNull Preset preset) {
        return builder().preset(Objects.requireNonNull(preset, "RenderOffsets.preset")).build();
    }

    /**
     * Returns the offsets of the main hand.
     *
     * @return the main hand, or null to leave it out
     * @since 1.0.0
     */
    public @Nullable Hand mainHand() {
        return mainHand;
    }

    /**
     * Returns the offsets of the off hand.
     *
     * @return the off hand, or null to leave it out
     * @since 1.0.0
     */
    public @Nullable Hand offHand() {
        return offHand;
    }

    /**
     * Returns the preset.
     *
     * @return the built-in offsets of an armor piece, or null
     * @since 1.0.0
     */
    public @Nullable Preset preset() {
        return preset;
    }

    /**
     * Creates a builder holding this value's fields, to change some of them.
     *
     * @return a builder with this value's fields
     * @since 1.0.0
     */
    public @NonNull Builder toBuilder() {
        return builder().mainHand(mainHand).offHand(offHand).preset(preset);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof RenderOffsets other && Objects.equals(mainHand, other.mainHand)
            && Objects.equals(offHand, other.offHand) && preset == other.preset;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mainHand, offHand, preset);
    }

    @Override
    public String toString() {
        return "RenderOffsets[mainHand=" + mainHand + ", offHand=" + offHand + ", preset=" + preset + "]";
    }

    /**
     * Builds {@link RenderOffsets}; every part is optional.
     *
     * @since 1.0.0
     */
    public static final class Builder {
        private @Nullable Hand mainHand;
        private @Nullable Hand offHand;
        private @Nullable Preset preset;

        private Builder() {
        }

        /**
         * Sets the main hand.
         *
         * @param mainHand the main hand, or null to leave it out
         * @return this builder
         * @since 1.0.0
         */
        public @NonNull Builder mainHand(@Nullable Hand mainHand) {
            this.mainHand = mainHand;
            return this;
        }

        /**
         * Sets the off hand.
         *
         * @param offHand the off hand, or null to leave it out
         * @return this builder
         * @since 1.0.0
         */
        public @NonNull Builder offHand(@Nullable Hand offHand) {
            this.offHand = offHand;
            return this;
        }

        /**
         * Sets the preset.
         *
         * @param preset the built-in offsets of an armor piece, or null
         * @return this builder
         * @since 1.0.0
         */
        public @NonNull Builder preset(@Nullable Preset preset) {
            this.preset = preset;
            return this;
        }

        /**
         * Builds the render offsets.
         *
         * @return the render offsets
         * @since 1.0.0
         */
        public @NonNull RenderOffsets build() {
            return new RenderOffsets(this);
        }
    }

    /**
     * The client's built-in render offsets of an armor piece (the legacy {@code render_offsets} string).
     *
     * @since 1.0.0
     */
    public enum Preset {
        /** {@code boots} */
        BOOTS("boots"),
        /** {@code leggings} */
        LEGGINGS("leggings"),
        /** {@code chestplates} */
        CHESTPLATES("chestplates"),
        /** {@code helmets} */
        HELMETS("helmets");

        private final String value;

        Preset(String value) {
            this.value = value;
        }

        /**
         * Returns the wire value of the preset.
         *
         * @return the string that is sent
         * @since 1.0.0
         */
        public @NonNull String value() {
            return value;
        }
    }

    @Override
    public @NonNull String name() {
        return NAME;
    }

    /**
     * The offsets of one hand.
     *
     * @since 1.0.0
     */
    public static final class Hand {
        private final @Nullable Offset firstPerson;
        private final @Nullable Offset thirdPerson;

        private Hand(Builder builder) {
            this.firstPerson = builder.firstPerson;
            this.thirdPerson = builder.thirdPerson;
        }

        /**
         * Creates a builder with nothing set.
         *
         * @return a builder of the offsets of a hand
         * @since 1.0.0
         */
        public static @NonNull Builder builder() {
            return new Builder();
        }

        /**
         * Returns the first person view.
         *
         * @return the first person view, or null to leave it out
         * @since 1.0.0
         */
        public @Nullable Offset firstPerson() {
            return firstPerson;
        }

        /**
         * Returns the third person view.
         *
         * @return the third person view, or null to leave it out
         * @since 1.0.0
         */
        public @Nullable Offset thirdPerson() {
            return thirdPerson;
        }

        /**
         * Creates a builder holding this value's fields, to change some of them.
         *
         * @return a builder with this value's fields
         * @since 1.0.0
         */
        public @NonNull Builder toBuilder() {
            return builder().firstPerson(firstPerson).thirdPerson(thirdPerson);
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof Hand other && Objects.equals(firstPerson, other.firstPerson)
                && Objects.equals(thirdPerson, other.thirdPerson);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(firstPerson) + Objects.hashCode(thirdPerson);
        }

        @Override
        public String toString() {
            return "Hand[firstPerson=" + firstPerson + ", thirdPerson=" + thirdPerson + "]";
        }

        /**
         * Builds {@link Hand}; both views are optional.
         *
         * @since 1.0.0
         */
        public static final class Builder {
            private @Nullable Offset firstPerson;
            private @Nullable Offset thirdPerson;

            private Builder() {
            }

            /**
             * Sets the first person view.
             *
             * @param firstPerson the first person view, or null to leave it out
             * @return this builder
             * @since 1.0.0
             */
            public @NonNull Builder firstPerson(@Nullable Offset firstPerson) {
                this.firstPerson = firstPerson;
                return this;
            }

            /**
             * Sets the third person view.
             *
             * @param thirdPerson the third person view, or null to leave it out
             * @return this builder
             * @since 1.0.0
             */
            public @NonNull Builder thirdPerson(@Nullable Offset thirdPerson) {
                this.thirdPerson = thirdPerson;
                return this;
            }

            /**
             * Builds the offsets of the hand.
             *
             * @return the offsets of the hand
             * @since 1.0.0
             */
            public @NonNull Hand build() {
                return new Hand(this);
            }
        }
    }

    /**
     * A transform; a null part is left out.
     *
     * @since 1.0.0
     */
    public static final class Offset {
        private final @Nullable Vec3 position;
        private final @Nullable Vec3 rotation;
        private final @Nullable Vec3 scale;

        private Offset(Builder builder) {
            this.position = builder.position;
            this.rotation = builder.rotation;
            this.scale = builder.scale;
        }

        /**
         * Creates a builder with nothing set.
         *
         * @return a builder of a transform
         * @since 1.0.0
         */
        public static @NonNull Builder builder() {
            return new Builder();
        }

        /**
         * Creates an offset that only scales.
         *
         * @param x the scale on the x axis
         * @param y the scale on the y axis
         * @param z the scale on the z axis
         * @return an offset that only scales
         * @since 1.0.0
         */
        public static @NonNull Offset scale(float x, float y, float z) {
            return builder().scale(Vec3.of(x, y, z)).build();
        }

        /**
         * Returns the position offset.
         *
         * @return the position offset, or null
         * @since 1.0.0
         */
        public @Nullable Vec3 position() {
            return position;
        }

        /**
         * Returns the rotation.
         *
         * @return the rotation, or null
         * @since 1.0.0
         */
        public @Nullable Vec3 rotation() {
            return rotation;
        }

        /**
         * Returns the scale.
         *
         * @return the scale, or null
         * @since 1.0.0
         */
        public @Nullable Vec3 scale() {
            return scale;
        }

        /**
         * Creates a builder holding this value's fields, to change some of them.
         *
         * @return a builder with this value's fields
         * @since 1.0.0
         */
        public @NonNull Builder toBuilder() {
            return builder().position(position).rotation(rotation).scale(scale);
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof Offset other && Objects.equals(position, other.position)
                && Objects.equals(rotation, other.rotation) && Objects.equals(scale, other.scale);
        }

        @Override
        public int hashCode() {
            return Objects.hash(position, rotation, scale);
        }

        @Override
        public String toString() {
            return "Offset[position=" + position + ", rotation=" + rotation + ", scale=" + scale + "]";
        }

        /**
         * Builds {@link Offset}; every part is optional.
         *
         * @since 1.0.0
         */
        public static final class Builder {
            private @Nullable Vec3 position;
            private @Nullable Vec3 rotation;
            private @Nullable Vec3 scale;

            private Builder() {
            }

            /**
             * Sets the position offset.
             *
             * @param position the position offset, or null
             * @return this builder
             * @since 1.0.0
             */
            public @NonNull Builder position(@Nullable Vec3 position) {
                this.position = position;
                return this;
            }

            /**
             * Sets the rotation.
             *
             * @param rotation the rotation, or null
             * @return this builder
             * @since 1.0.0
             */
            public @NonNull Builder rotation(@Nullable Vec3 rotation) {
                this.rotation = rotation;
                return this;
            }

            /**
             * Sets the scale.
             *
             * @param scale the scale, or null
             * @return this builder
             * @since 1.0.0
             */
            public @NonNull Builder scale(@Nullable Vec3 scale) {
                this.scale = scale;
                return this;
            }

            /**
             * Builds the transform.
             *
             * @return the transform
             * @since 1.0.0
             */
            public @NonNull Offset build() {
                return new Offset(this);
            }
        }
    }
}
