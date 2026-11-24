package dev.opencollab.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * Where a registered item or block of a palette comes from: the vanilla data, or the layer that added it.
 *
 * @param layer the id of the layer ({@link PaletteLayer#id()}) that added the content, or null for vanilla content
 * @since 1.0.0
 */
public record Origin(@Nullable String layer) {
    /**
     * Content of the vanilla data.
     *
     * @since 1.0.0
     */
    public static final Origin VANILLA = new Origin(null);

    /**
     * Creates the origin of content a layer added.
     *
     * @param layer the id of the layer ({@link PaletteLayer#id()})
     * @return the origin of content a layer added
     * @since 1.0.0
     */
    public static @NonNull Origin custom(@NonNull String layer) {
        return new Origin(Objects.requireNonNull(layer));
    }

    /**
     * Tells whether the content is vanilla.
     *
     * @return true for {@link #VANILLA}
     * @since 1.0.0
     */
    public boolean isVanilla() {
        return layer == null;
    }

    /**
     * Tells whether a layer added the content.
     *
     * @return true if a layer added the content
     * @since 1.0.0
     */
    public boolean isCustom() {
        return layer != null;
    }

    @Override
    public String toString() {
        return layer == null ? "vanilla" : "custom(" + layer + ")";
    }
}
