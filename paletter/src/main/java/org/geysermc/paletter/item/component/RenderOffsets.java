package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * {@code minecraft:render_offsets}: where the item is drawn in each hand, per view.
 * <p>
 * Retired from the add-on format (Bedrock Dedicated Server 1.26.63.1 rejects it at every {@code format_version}). Paletter
 * assumes current clients still honour it on the wire and keeps sending it for legacy content; it will be removed if
 * clients stop honouring it.
 *
 * A {@link Preset} is the legacy form Geyser sends for armor converted from v1 custom mappings: a bare string
 * {@code render_offsets} (no {@code minecraft:} prefix, not a component) that names the client's built-in offsets of an
 * armor piece. It is written next to the hands (if any); with only a preset, {@code minecraft:render_offsets} is not sent.
 *
 * @param mainHand the main hand, or null to leave it out
 * @param offHand the off hand, or null to leave it out
 * @param preset the built-in offsets of an armor piece, or null
 * @see <a href="https://learn.microsoft.com/en-us/minecraft/creator/reference/content/itemreference/examples/itemcomponents/minecraft_render_offsets?view=minecraft-bedrock-stable">Microsoft Learn: minecraft:render_offsets</a>
 * @since 1.0.0
 * @deprecated retired from the add-on format, still sent for legacy content; will be removed if clients stop honouring it
 */
@Deprecated
public record RenderOffsets(@Nullable Hand mainHand, @Nullable Hand offHand, @Nullable Preset preset) implements WireOnlyItemComponent {
    /**
     * The component name.
     */
    public static final String NAME = "minecraft:render_offsets";

    /**
     * @param mainHand the main hand, or null to leave it out
     * @param offHand the off hand, or null to leave it out
     */
    public RenderOffsets(@Nullable Hand mainHand, @Nullable Hand offHand) {
        this(mainHand, offHand, null);
    }

    /**
     * @param preset the built-in offsets of an armor piece
     * @return render offsets that only name a preset
     */
    public static @NonNull RenderOffsets preset(@NonNull Preset preset) {
        return new RenderOffsets(null, null, Objects.requireNonNull(preset, "RenderOffsets.preset"));
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
         * @return the string that is sent
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
     * @param firstPerson the first person view, or null to leave it out
     * @param thirdPerson the third person view, or null to leave it out
     * @since 1.0.0
     */
    public record Hand(@Nullable Offset firstPerson, @Nullable Offset thirdPerson) {
    }

    /**
     * A transform; a null part is left out.
     *
     * @param position the position offset, or null
     * @param rotation the rotation, or null
     * @param scale the scale, or null
     * @since 1.0.0
     */
    public record Offset(@Nullable Vec3 position, @Nullable Vec3 rotation, @Nullable Vec3 scale) {
        /**
         * @return an offset that only scales
         */
        public static @NonNull Offset scale(float x, float y, float z) {
            return new Offset(null, null, new Vec3(x, y, z));
        }
    }

    /**
     * Three floats, sent as a list of three.
     *
     * @since 1.0.0
     */
    public record Vec3(float x, float y, float z) {
    }
}
