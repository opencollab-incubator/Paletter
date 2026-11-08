package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A replacement for an item in a version.
 *
 * @param target the replacement item identifier
 * @param force true to replace the item even though it exists in this version, e.g. because it is experimental there
 * @since 1.0.0
 */
public record ItemFallback(@NonNull String target, boolean force) {
    /**
     * The default for items a version lacks: the {@code minecraft:unknown} item (the block item of
     * {@code minecraft:unknown}).
     *
     * @since 1.0.0
     */
    public static final ItemFallback UNKNOWN = new ItemFallback("minecraft:unknown", false);


    public static @NonNull ItemFallback of(@NonNull String target) {
        return new ItemFallback(target, false);
    }

    /**
     * @since 1.0.0
     */
    public static @NonNull ItemFallback of(org.geysermc.paletter.vanilla.@NonNull BedrockItemType target) {
        return of(target.identifier());
    }
}
