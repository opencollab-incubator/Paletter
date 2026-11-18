package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Thrown when a palette cannot be compiled, e.g. a duplicate custom identifier or a block network id hash collision.
 * These are always programming errors in a layer, never caused by the client.
 * <p>
 * {@link Paletter#palette(int)} and {@link Paletter#warmUp()} throw it for a global layer;
 * {@link Palette#extend(PaletteLayer)} does not, it reports the error in the {@link PaletteReport} instead.
 *
 * @since 1.0.0
 */
public class PaletteException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message what could not be compiled, and why
     */
    public PaletteException(@NonNull String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message what could not be compiled, and why
     * @param cause the underlying failure
     */
    public PaletteException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
    }
}
