package dev.opencollab.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockBlockType;
import dev.opencollab.paletter.vanilla.BedrockBlocks;

import java.util.Objects;

/**
 * A replacement for a block in a version. The target is a key: it needs no existence in any version, custom keys are
 * made with {@link dev.opencollab.paletter.Palette#blockState}. Made with {@link #of(BedrockBlockType)} or
 * {@link #of(BedrockBlockState)}, and {@link #forced()}; a value with {@code equals}, {@code hashCode} and
 * {@code toString}.
 *
 * @since 1.0.0
 */
public final class BlockFallback {
    /**
     * The default for blocks a version lacks: {@code minecraft:unknown}, which every client knows and shows as a
     * placeholder block.
     *
     * @since 1.0.0
     */
    public static final BlockFallback UNKNOWN = of(BedrockBlocks.UNKNOWN);

    private final BedrockBlockType target;
    private final @Nullable BedrockBlockState state;
    private final boolean force;

    private BlockFallback(BedrockBlockType target, @Nullable BedrockBlockState state, boolean force) {
        this.target = Objects.requireNonNull(target, "target");
        this.state = state;
        this.force = force;
    }

    /**
     * Creates a fallback to a block type that carries over the state values both blocks have.
     *
     * @param target the replacement block type
     * @return a fallback to a block type, carrying over matching state values (the others use the replacement's defaults)
     * @throws NullPointerException if the target is null
     * @since 1.0.0
     */
    public static @NonNull BlockFallback of(@NonNull BedrockBlockType target) {
        return new BlockFallback(target, null, false);
    }

    /**
     * Creates a fallback to one exact block state.
     *
     * @param target the replacement state
     * @return a fallback to exactly one block state
     * @throws NullPointerException if the target is null
     * @since 1.0.0
     */
    public static @NonNull BlockFallback of(@NonNull BedrockBlockState target) {
        return new BlockFallback(target.type(), target, false);
    }

    /**
     * Returns the forced variant of this fallback.
     *
     * @return this fallback, used even in versions that have the block. Needed for blocks that are in the palette, but
     *         only usable with an experiment enabled
     * @since 1.0.0
     */
    public @NonNull BlockFallback forced() {
        return force ? this : new BlockFallback(target, state, true);
    }

    /**
     * The replacement block type.
     *
     * @return the replacement block type
     * @since 1.0.0
     */
    public @NonNull BedrockBlockType target() {
        return target;
    }

    /**
     * The exact replacement state.
     *
     * @return the exact replacement state, or null to carry over the state values the replaced block and the
     *         replacement have in common (the others use the replacement's defaults)
     * @since 1.0.0
     */
    public @Nullable BedrockBlockState state() {
        return state;
    }

    /**
     * Whether the fallback is forced.
     *
     * @return true to replace the block even though it exists in this version
     * @since 1.0.0
     */
    public boolean force() {
        return force;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof BlockFallback other && force == other.force && target.equals(other.target)
            && Objects.equals(state, other.state);
    }

    @Override
    public int hashCode() {
        return Objects.hash(target, state, force);
    }

    @Override
    public String toString() {
        return "BlockFallback{" + (state != null ? state : target) + (force ? ", forced" : "") + "}";
    }
}
