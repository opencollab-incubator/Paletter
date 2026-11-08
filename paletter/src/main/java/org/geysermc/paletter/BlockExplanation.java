package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.annotation.Experimental;
import org.geysermc.paletter.block.BlockResolution;

import java.util.List;

/**
 * What a palette does with a block state, for debugging: {@link Palette#explain(NbtMap)}.
 *
 * @param requested the state asked for, as {@code {name, states}}
 * @param canonical its canonical id, or -1 if it has none in this palette
 * @param kind how it was resolved for this palette's version
 * @param chain the identifiers involved: the requested block, then the block it resolved to if that is another one
 * @param sent the state that is sent to the client, or null if nothing fits ({@link BlockResolution.Kind#MISSING})
 * @param runtimeId the runtime id of the sent state in this palette's network id mode, or -1
 * @param hashedId the hashed network id of the sent state, whatever the palette's mode, or -1
 * @since 1.0.0
 */
@Experimental
public record BlockExplanation(@NonNull NbtMap requested, int canonical, BlockResolution.@NonNull Kind kind,
                               @NonNull List<String> chain, @Nullable NbtMap sent, int runtimeId, int hashedId) {
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        out.append("block ").append(describe(requested)).append('\n');
        out.append("  canonical id: ").append(canonical == -1 ? "none" : canonical).append('\n');
        out.append("  resolution:   ").append(kind.name().toLowerCase());
        if (chain.size() > 1) {
            out.append(" (").append(String.join(" -> ", chain)).append(')');
        }
        out.append('\n');
        if (sent != null) {
            out.append("  sent:         ").append(describe(sent)).append('\n');
            out.append("  runtime id:   ").append(runtimeId).append('\n');
            out.append("  hashed id:    ").append(hashedId).append('\n');
        }
        return out.toString().stripTrailing();
    }

    static String describe(NbtMap state) {
        NbtMap states = state.getCompound("states", NbtMap.EMPTY);
        if (states.isEmpty()) {
            return state.getString("name");
        }
        StringBuilder out = new StringBuilder(state.getString("name")).append('[');
        states.keySet().stream().sorted().forEach(key -> out.append(out.charAt(out.length() - 1) == '[' ? "" : ",")
            .append(key).append('=').append(states.get(key)));
        return out.append(']').toString();
    }
}
