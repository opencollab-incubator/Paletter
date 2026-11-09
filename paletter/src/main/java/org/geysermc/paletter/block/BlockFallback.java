package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockBlockType;
import org.geysermc.paletter.vanilla.BedrockBlocks;

import java.util.Objects;

/**
 * A replacement for a block in a version. The target is a key: it needs no existence in any version, custom keys are
 * made with {@code BedrockBlockType.of("namespace:id")}.
 *
 * @param target the replacement block type
 * @param state the exact replacement state, or null to carry over the state values the replaced block and the
 *              replacement have in common (the others use the replacement's defaults)
 * @param force true to replace the block even though it exists in this version. Needed for blocks that are in the
 *              palette, but only usable with an experiment enabled
 * @since 1.0.0
 */
public record BlockFallback(@NonNull BedrockBlockType target, @Nullable BedrockBlockState state, boolean force) {
    /**
     * The default for blocks a version lacks: {@code minecraft:unknown}, which every client knows and shows as a
     * placeholder block.
     *
     * @since 1.0.0
     */
    public static final BlockFallback UNKNOWN = new BlockFallback(BedrockBlocks.UNKNOWN, null, false);

    public BlockFallback {
        Objects.requireNonNull(target);
        if (state != null && !state.type().equals(target)) {
            throw new IllegalArgumentException("The state " + state + " is not of " + target);
        }
    }

    /**
     * @return a fallback to a block type, carrying over matching state values
     * @since 1.0.0
     */
    public static @NonNull BlockFallback of(@NonNull BedrockBlockType target) {
        return new BlockFallback(target, null, false);
    }

    /**
     * @return a fallback to exactly one block state
     * @since 1.0.0
     */
    public static @NonNull BlockFallback of(@NonNull BedrockBlockState target) {
        return new BlockFallback(target.type(), target, false);
    }

    /**
     * @return this fallback, used even in versions that have the block
     * @since 1.0.0
     */
    public @NonNull BlockFallback forced() {
        return force ? this : new BlockFallback(target, state, true);
    }
}
