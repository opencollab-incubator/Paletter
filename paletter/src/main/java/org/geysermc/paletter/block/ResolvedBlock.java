package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.behaviour.BlockValues;

import java.util.Objects;

/**
 * A block state resolved against one palette: what it sends for it and what that does, see
 * {@link org.geysermc.paletter.Palette#resolve(org.geysermc.paletter.vanilla.BedrockBlockState)}. A resolution is made
 * on each call, with its {@link BlockValues} handle: do not compare resolutions with {@code ==}. Use
 * {@link org.geysermc.paletter.Palette#runtimeId(org.geysermc.paletter.vanilla.BedrockBlockState)} where only the id is
 * needed. Resolutions are equal if they have the same definition, fallback and behaviour (a handle of the same state
 * in the same palette).
 *
 * @since 1.0.0
 */
public final class ResolvedBlock {
    private final RegisteredBlockState definition;
    private final boolean fallback;
    private final BlockValues behaviour;

    /**
     * Internal: plumbing of the palette's {@code resolve}, not part of the API.
     *
     * @param definition the block to send
     * @param fallback whether a fallback was used, see {@link #fallback()}
     * @param behaviour the behaviour values of {@code definition}
     */
    @Internal
    public ResolvedBlock(@NonNull RegisteredBlockState definition, boolean fallback, @NonNull BlockValues behaviour) {
        this.definition = definition;
        this.fallback = fallback;
        this.behaviour = behaviour;
    }

    /**
     * The block this resolution sends.
     *
     * @return the block to send, with its fallbacks applied, see {@link org.geysermc.paletter.Palette#blockDefinition(org.geysermc.paletter.vanilla.BedrockBlockState)}
     */
    public @NonNull RegisteredBlockState definition() {
        return definition;
    }

    /**
     * Whether the block sent is a replacement.
     *
     * @return true if a {@code BlockFallback} or the default fallback block was used: the state has no block of its own
     *         in this version, or is forcibly replaced. Not for a state that is only adapted to this version's state
     *         values. False for a block asked for by runtime id
     */
    public boolean fallback() {
        return fallback;
    }

    /**
     * The behaviour of the block sent.
     *
     * @return the behaviour values (friction, destroy time, collision shape, ...) of {@link #definition()} in the palette that resolved it
     */
    public @NonNull BlockValues behaviour() {
        return behaviour;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof ResolvedBlock other && fallback == other.fallback
            && definition.equals(other.definition) && behaviour.equals(other.behaviour);
    }

    @Override
    public int hashCode() {
        return Objects.hash(definition, fallback, behaviour);
    }

    @Override
    public String toString() {
        return "ResolvedBlock{" + definition + ", fallback=" + fallback + '}';
    }
}
