package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A replacement for a block in a version.
 *
 * @param target the replacement block identifier
 * @param clearStates true to use the replacement's default state instead of carrying over matching state values
 * @param force true to replace the block even though it exists in this version. Needed for blocks that are in the
 *              palette, but only usable with an experiment enabled
 *
 * @since 2.0.0
 */
public record BlockFallback(@NonNull String target, boolean clearStates, boolean force) {
    /**
     * The default for blocks a version lacks: {@code minecraft:unknown}, which every client knows and shows as a
     * placeholder block.
     *
     * @since 2.0.0
     */
    public static final BlockFallback UNKNOWN = new BlockFallback("minecraft:unknown", true, false);


    public static @NonNull BlockFallback of(@NonNull String target) {
        return new BlockFallback(target, false, false);
    }

    /**
     * @since 2.0.0
     */
    public static @NonNull BlockFallback of(org.geysermc.paletter.vanilla.@NonNull BedrockBlockType target) {
        return of(target.identifier());
    }
}
