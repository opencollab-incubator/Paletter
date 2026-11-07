package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A replacement for an item in a version.
 *
 * @param target the replacement item identifier
 * @param force true to replace the item even though it exists in this version, e.g. because it is experimental there
 * @since 2.0.0
 */
public record ItemFallback(@NonNull String target, boolean force) {

    public static @NonNull ItemFallback of(@NonNull String target) {
        return new ItemFallback(target, false);
    }
}
