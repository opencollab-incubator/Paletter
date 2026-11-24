package dev.opencollab.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import dev.opencollab.paletter.ResolutionKind;
import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.behaviour.BlockValues;

import java.util.Objects;

/**
 * A block state resolved against one palette: what it sends for it and what that does, see
 * {@link dev.opencollab.paletter.Palette#resolve(dev.opencollab.paletter.vanilla.BedrockBlockState)}. A palette makes a
 * resolution once per state and keeps it, with its {@link BlockValues} handle; states that resolve alike may share one.
 * Use {@link dev.opencollab.paletter.Palette#runtimeId(dev.opencollab.paletter.vanilla.BedrockBlockState)} where only the
 * id is needed. Resolutions are equal if they have the same definition, kind and behaviour (a handle of the same block in
 * the same palette); compare them with {@code equals}.
 *
 * @since 1.0.0
 */
public final class ResolvedBlock {
    private final RegisteredBlockState definition;
    private final ResolutionKind kind;
    private final BlockValues behaviour;

    /**
     * Internal: plumbing of the palette's {@code resolve}, not part of the API.
     *
     * @param definition the block to send
     * @param kind how the state was resolved, see {@link #kind()}
     * @param behaviour the behaviour values of {@code definition}
     */
    @Internal
    public ResolvedBlock(@NonNull RegisteredBlockState definition, @NonNull ResolutionKind kind, @NonNull BlockValues behaviour) {
        this.definition = definition;
        this.kind = Objects.requireNonNull(kind);
        this.behaviour = behaviour;
    }

    /**
     * The block this resolution sends.
     *
     * @return the block to send, with its fallbacks applied, see {@link dev.opencollab.paletter.Palette#blockDefinition(dev.opencollab.paletter.vanilla.BedrockBlockState)}
     */
    public @NonNull RegisteredBlockState definition() {
        return definition;
    }

    /**
     * How the palette resolved the state for its version.
     *
     * @return {@link ResolutionKind#EXACT} (also for a block asked for by runtime id), {@link ResolutionKind#ADAPTED}
     *         (the same block with state values fitted to the version), {@link ResolutionKind#FALLBACK} (a
     *         {@code BlockFallback}, also a forced one), {@link ResolutionKind#DEFAULT} (the default fallback block) or
     *         {@link ResolutionKind#MISSING} (no fallback at all: air is sent)
     * @since 1.0.0
     */
    public @NonNull ResolutionKind kind() {
        return kind;
    }

    /**
     * Whether the block sent is a replacement.
     *
     * @return true if the state has no block of its own in this version, or is forcibly replaced: {@link #kind()} is
     *         {@code FALLBACK}, {@code DEFAULT} or {@code MISSING}. Not for a state that is only adapted to this
     *         version's state values
     * @since 1.0.0
     */
    public boolean fallback() {
        return kind.isReplacement();
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
        return this == o || o instanceof ResolvedBlock other && kind == other.kind
            && definition.equals(other.definition) && behaviour.equals(other.behaviour);
    }

    @Override
    public int hashCode() {
        return Objects.hash(definition, kind, behaviour);
    }

    @Override
    public String toString() {
        return "ResolvedBlock{" + definition + ", " + kind + '}';
    }
}
