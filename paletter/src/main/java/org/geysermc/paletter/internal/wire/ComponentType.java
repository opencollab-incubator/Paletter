package org.geysermc.paletter.internal.wire;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;

import java.util.function.Function;

/**
 * Describes one generated wire shape: its network name, the protocol versions it is valid for, how well it is
 * verified, and how to read it. Internal: not part of Paletter's API.
 * <p>
 * A component whose network shape changed between Bedrock versions has one type per shape, each with its own protocol
 * window ({@code codegen/spec/inferred.json}, {@code sinceProtocol}/{@code untilProtocol}); the encoders pick the one
 * that covers the target palette's protocol.
 *
 * @param name the component name on the network, which is also its add-on JSON name
 * @param kind whether it is an item or a block component
 * @param confidence what its shape was inferred from
 * @param since the minimum add-on format version that accepts the component, if known
 * @param experimental whether add-ons need an experimental toggle for the component
 * @param sinceProtocol the first protocol version this shape is valid for (inclusive), 0 if unbounded
 * @param untilProtocol the protocol version this shape stops being valid at (exclusive), {@link Integer#MAX_VALUE} if unbounded
 * @param reader reads the network NBT
 * @param <T> the generated record
 */
public record ComponentType<T>(@NonNull String name, @NonNull Kind kind, @NonNull Confidence confidence, @Nullable String since,
                               boolean experimental, int sinceProtocol, int untilProtocol, @NonNull Function<NbtMap, T> reader) {
    public static final int UNBOUNDED_SINCE = 0;
    public static final int UNBOUNDED_UNTIL = Integer.MAX_VALUE;

    public enum Kind {
        ITEM, BLOCK
    }

    /**
     * @return whether this shape is the one to use for a palette of this protocol version
     */
    public boolean supports(int protocol) {
        return protocol >= sinceProtocol && protocol < untilProtocol;
    }

    /**
     * Reads a component from network NBT. Reading is lenient: numbers of another width are converted and missing
     * fields take their defaults, so NBT written by older encoders can be read and re-encoded in the right shape.
     */
    public @NonNull T read(@NonNull NbtMap nbt) {
        return reader.apply(nbt);
    }

    /**
     * @return the protocol window, e.g. {@code [2168, 2250)}
     */
    public @NonNull String window() {
        return "[" + (sinceProtocol == UNBOUNDED_SINCE ? "" : sinceProtocol) + ", "
            + (untilProtocol == UNBOUNDED_UNTIL ? "" : untilProtocol) + ")";
    }

    @Override
    public String toString() {
        return "ComponentType[" + name + ", " + kind + ", " + confidence + ", protocol " + window() + "]";
    }
}
