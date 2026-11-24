package dev.opencollab.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.annotation.Experimental;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;

import java.util.List;
import java.util.Locale;

/**
 * What a palette does with an item variant, for debugging: {@link Palette#explain(String, int, NbtMap)}.
 *
 * @param identifier the item identifier asked for
 * @param aux the aux value asked for
 * @param blockState the block state asked for, as {@code {name, states}}, or null
 * @param handle its handle, or null if it has none in this palette
 * @param kind how it was resolved for this palette's version ({@code ADAPTED} does not occur for items)
 * @param chain the identifiers involved: the requested item, then the item it resolved to if that is another one
 * @param item the identifier of the item that is sent, or null if nothing fits
 * @param runtimeId the runtime id of the sent item definition, or -1
 * @param sentAux the aux value that is sent
 * @param block the block sent with the item (its block item's block, or the default block), or null
 * @param blockRuntimeId the runtime id of that block, or -1
 * @param creative whether the variant is in the creative inventory
 * @param creativeGroup the creative group it is in, or null if it is loose or not in the creative inventory
 * @since 1.0.0
 */
@Experimental
public record ItemExplanation(@NonNull String identifier, int aux, @Nullable NbtMap blockState,
                              @Nullable BedrockItemVariant handle, @NonNull ResolutionKind kind,
                              @NonNull List<String> chain, @Nullable String item, int runtimeId, int sentAux,
                              @Nullable String block, int blockRuntimeId, boolean creative, @Nullable String creativeGroup) {

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        out.append("item ").append(identifier);
        if (aux != 0) {
            out.append(':').append(aux);
        }
        if (blockState != null) {
            out.append(' ').append(BlockExplanation.describe(blockState));
        }
        out.append('\n');
        out.append("  handle:       ").append(handle == null ? "none" : handle).append('\n');
        out.append("  resolution:   ").append(kind.name().toLowerCase(Locale.ROOT));
        if (chain.size() > 1) {
            out.append(" (").append(String.join(" -> ", chain)).append(')');
        }
        out.append('\n');
        if (item != null) {
            out.append("  sent:         ").append(item).append(" aux ").append(sentAux).append('\n');
            out.append("  runtime id:   ").append(runtimeId).append('\n');
            out.append("  block:        ").append(block == null ? "none" : block + " (runtime id " + blockRuntimeId + ")").append('\n');
        }
        out.append("  creative:     ");
        if (!creative) {
            out.append("not in the creative inventory");
        } else {
            out.append(creativeGroup == null ? "loose" : "group " + creativeGroup);
        }
        return out.toString();
    }
}
