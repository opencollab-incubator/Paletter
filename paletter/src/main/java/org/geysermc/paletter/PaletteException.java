package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Thrown when a palette cannot be compiled, e.g. a duplicate custom identifier or a block network id hash collision.
 * These are always programming errors in a layer, never caused by the client.
 *
 * @since 2.0.0
 */
public class PaletteException extends RuntimeException {

    public PaletteException(@NonNull String message) {
        super(message);
    }

    public PaletteException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
    }
}
