package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Thrown when a palette cannot be compiled, e.g. a duplicate identifier or a block network id hash collision.
 * These are always programming errors in a layer, never caused by the client. {@link #layer()} and
 * {@link #identifier()} name where the problem comes from.
 *
 * @since 1.0.0
 */
public class PaletteException extends RuntimeException {
    private final String layer;
    private final String identifier;

    public PaletteException(@NonNull String message) {
        this(message, null, null, null);
    }

    public PaletteException(@NonNull String message, @Nullable String layer, @Nullable String identifier, @Nullable Throwable cause) {
        super(message, cause);
        this.layer = layer;
        this.identifier = identifier;
    }

    /**
     * @return the id of the layer that cannot be compiled, or null if the problem is not in a layer
     */
    public @Nullable String layer() {
        return layer;
    }

    /**
     * @return the identifier of the content (block, item, voxel shape) the problem is about, or null if it is not about one
     */
    public @Nullable String identifier() {
        return identifier;
    }
}
