package dev.opencollab.paletter;

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
    /** The id of the layer, or null. */
    private final String layer;
    /** The identifier of the content, or null. */
    private final String identifier;

    /**
     * Creates the exception.
     *
     * @param message what could not be compiled, and why
     */
    public PaletteException(@NonNull String message) {
        this(message, null, null, null);
    }

    /**
     * Creates the exception with the place of the problem and a cause.
     *
     * @param message what could not be compiled, and why
     * @param layer the id of the layer that cannot be compiled, or null if the problem is not in a layer
     * @param identifier the identifier of the content the problem is about, or null if it is not about one
     * @param cause the underlying failure, or null
     */
    public PaletteException(@NonNull String message, @Nullable String layer, @Nullable String identifier, @Nullable Throwable cause) {
        super(message, cause);
        this.layer = layer;
        this.identifier = identifier;
    }

    /**
     * Names the layer the problem comes from.
     *
     * @return the id of the layer that cannot be compiled, or null if the problem is not in a layer
     */
    public @Nullable String layer() {
        return layer;
    }

    /**
     * Names the content the problem is about.
     *
     * @return the identifier of the content (block, item, voxel shape) the problem is about, or null if it is not about
     *         one
     */
    public @Nullable String identifier() {
        return identifier;
    }
}
