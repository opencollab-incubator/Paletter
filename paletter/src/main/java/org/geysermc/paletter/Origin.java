package org.geysermc.paletter;

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
     */
    public static final Origin VANILLA = new Origin(null);

    /**
     * @return the origin of content a layer added
     */
    public static @NonNull Origin custom(@NonNull String layer) {
        return new Origin(Objects.requireNonNull(layer));
    }

    public boolean isVanilla() {
        return layer == null;
    }

    public boolean isCustom() {
        return layer != null;
    }

    @Override
    public String toString() {
        return layer == null ? "vanilla" : "custom(" + layer + ")";
    }
}
