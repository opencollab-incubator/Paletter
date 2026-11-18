package dev.opencollab.paletter;

import dev.opencollab.paletter.item.ResolvedItem;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.internal.CanonicalBlocks;
import dev.opencollab.paletter.internal.CanonicalItems;
import dev.opencollab.paletter.internal.Canonicals;
import dev.opencollab.paletter.internal.Forwarding;
import dev.opencollab.paletter.internal.item.ItemVariant;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;

/**
 * Tests of the plumbing talk in canonical ids, which the public API no longer exposes; this is the one place that
 * reaches for them.
 */
public final class Ids {
    private Ids() {
    }

    public static CanonicalBlocks canonicalBlocks(Paletter p) {
        return p.canonicalBlocks();
    }

    public static CanonicalBlocks canonicalBlocks(Palette p) {
        return p.blocks().canonical();
    }

    public static CanonicalItems canonicalItems(Paletter p) {
        return p.canonicalItems();
    }

    public static CanonicalItems canonicalItems(Palette p) {
        return p.items().canonical();
    }

    public static int blockCount(Paletter p) {
        return canonicalBlocks(p).size();
    }

    public static int blockCount(Palette p) {
        return canonicalBlocks(p).size();
    }

    public static int itemCount(Paletter p) {
        return canonicalItems(p).size();
    }

    public static int itemCount(Palette p) {
        return canonicalItems(p).size();
    }

    public static int block(Paletter p, NbtMap state) {
        return Canonicals.block(canonicalBlocks(p), state);
    }

    public static int block(Palette p, NbtMap state) {
        return Canonicals.block(canonicalBlocks(p), state);
    }

    public static int block(Paletter p, BedrockBlockState state) {
        return state.canonicalIn(canonicalBlocks(p));
    }

    public static int block(Palette p, BedrockBlockState state) {
        return state.canonicalIn(canonicalBlocks(p));
    }

    public static int item(Paletter p, ItemVariant variant) {
        return Canonicals.item(canonicalItems(p), variant);
    }

    public static int item(Palette p, ItemVariant variant) {
        return Canonicals.item(canonicalItems(p), variant);
    }

    public static int item(Paletter p, BedrockItemType type) {
        return type.canonicalIn(canonicalItems(p));
    }

    public static int item(Palette p, BedrockItemType type) {
        return type.canonicalIn(canonicalItems(p));
    }

    public static int item(Paletter p, BedrockItemVariant variant) {
        return variant.canonicalIn(canonicalItems(p));
    }

    public static int item(Palette p, BedrockItemVariant variant) {
        return variant.canonicalIn(canonicalItems(p));
    }

    public static int blockOf(Paletter p, ItemVariant variant) {
        BedrockBlockState block = p.vanilla(p.latest()).blockOf(BedrockItemVariant.fromIdentifier(variant.identifier(), variant.damage()));
        return block == null ? -1 : block(p, block);
    }

    public static Forwarding forwardBlock(Paletter p, NbtMap state) {
        return Canonicals.forwardBlock(canonicalBlocks(p), state);
    }

    public static Forwarding forwardBlock(Palette p, NbtMap state) {
        return Canonicals.forwardBlock(canonicalBlocks(p), state);
    }

    public static Forwarding forwardItem(Paletter p, ItemVariant variant) {
        return Canonicals.forwardItem(canonicalItems(p), variant);
    }

    public static Forwarding forwardItem(Palette p, ItemVariant variant) {
        return Canonicals.forwardItem(canonicalItems(p), variant);
    }

    public static int runtimeId(Palette p, int canonical) {
        return p.blocks().runtimeId(canonical);
    }

    public static RegisteredBlockState blockAt(Palette p, int canonical) {
        return p.blocks().block(canonical);
    }

    public static ResolvedItem itemAt(Palette p, int canonical) {
        return p.items().item(canonical);
    }

    public static int match(Palette p, ItemData data) {
        return p.items().match(data);
    }
}
