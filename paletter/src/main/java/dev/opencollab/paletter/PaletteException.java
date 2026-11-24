package dev.opencollab.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import dev.opencollab.paletter.data.DataVersion;

/**
 * Thrown when a palette cannot be compiled: content of a layer that cannot be (a duplicate identifier, a block network id
 * hash collision, a callback that throws), or data of the data source that cannot be (no vanilla voxel shapes for a
 * version). Never caused by a client. {@link #version()}, {@link #layer()} and {@link #identifier()} name where the problem
 * comes from. A configuration that is wrong in itself, such as a version that is not served, is an
 * {@link IllegalArgumentException} instead.
 *
 * @since 1.0.0
 */
public final class PaletteException extends RuntimeException {
    /** The data version of the palette, or null. */
    private final DataVersion version;
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
        this(message, null, null, null, null);
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
        this(message, null, layer, identifier, cause);
    }

    /**
     * Creates the exception with the version and place of the problem and a cause.
     *
     * @param message what could not be compiled, and why
     * @param version the data version of the palette that cannot be compiled, or null if the problem is not about one
     * @param layer the id of the layer that cannot be compiled, or null if the problem is not in a layer
     * @param identifier the identifier of the content the problem is about, or null if it is not about one
     * @param cause the underlying failure, or null
     * @since 1.0.0
     */
    public PaletteException(@NonNull String message, @Nullable DataVersion version, @Nullable String layer,
                            @Nullable String identifier, @Nullable Throwable cause) {
        super(message, cause);
        this.version = version;
        this.layer = layer;
        this.identifier = identifier;
    }

    /**
     * Names the data version of the palette that cannot be compiled.
     *
     * @return the data version, or null if the problem is not about one
     * @since 1.0.0
     */
    public @Nullable DataVersion version() {
        return version;
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
