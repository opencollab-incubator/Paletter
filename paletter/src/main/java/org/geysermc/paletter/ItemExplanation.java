package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.annotation.Experimental;
import org.geysermc.paletter.block.BlockResolution;
import org.geysermc.paletter.creative.CreativeLocation;
import org.geysermc.paletter.item.ItemVariant;

import java.util.List;

/**
 * What a palette does with an item variant, for debugging: {@link Palette#explain(ItemVariant)}.
 *
 * @param requested the variant asked for
 * @param canonical its canonical id, or -1 if it has none in this palette
 * @param kind how it was resolved for this palette's version ({@code ADAPTED} does not occur for items)
 * @param chain the identifiers involved: the requested item, then the item it resolved to if that is another one
 * @param item the identifier of the item that is sent, or null if nothing fits
 * @param runtimeId the runtime id of the sent item definition, or -1
 * @param damage the damage value that is sent
 * @param block the block sent with the item (its block item's block, or the default block), or null
 * @param blockRuntimeId the runtime id of that block, or -1
 * @param creative where the variant is in the creative inventory, or null if it is not
 * @since 1.1.0
 */
@Experimental
public record ItemExplanation(@NonNull ItemVariant requested, int canonical, BlockResolution.@NonNull Kind kind,
                              @NonNull List<String> chain, @Nullable String item, int runtimeId, int damage,
                              @Nullable String block, int blockRuntimeId, @Nullable CreativeLocation creative) {
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        out.append("item ").append(requested.identifier());
        if (requested.damage() != 0) {
            out.append(':').append(requested.damage());
        }
        if (requested.blockState() != null) {
            out.append(' ').append(BlockExplanation.describe(requested.blockState()));
        }
        out.append('\n');
        out.append("  canonical id: ").append(canonical == -1 ? "none" : canonical).append('\n');
        out.append("  resolution:   ").append(kind.name().toLowerCase());
        if (chain.size() > 1) {
            out.append(" (").append(String.join(" -> ", chain)).append(')');
        }
        out.append('\n');
        if (item != null) {
            out.append("  sent:         ").append(item).append(" damage ").append(damage).append('\n');
            out.append("  runtime id:   ").append(runtimeId).append('\n');
            out.append("  block:        ").append(block == null ? "none" : block + " (runtime id " + blockRuntimeId + ")").append('\n');
        }
        out.append("  creative:     ");
        if (creative == null) {
            out.append("not in the creative inventory");
        } else {
            out.append(creative.category().name().toLowerCase());
            out.append(creative.group() == null ? "" : ", group " + creative.group()).append(", index ").append(creative.index());
        }
        return out.toString();
    }
}
