package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Thrown when a palette cannot be compiled, e.g. a duplicate custom identifier or a conflicting attachment.
 * These are always programming errors in a layer, never caused by the client.
 */
public class PaletteException extends RuntimeException {

    public PaletteException(@NonNull String message) {
        super(message);
    }

    public PaletteException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
    }
}
