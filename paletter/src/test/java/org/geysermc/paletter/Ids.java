package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.paletter.block.PaletteBlock;
import org.geysermc.paletter.internal.CanonicalBlocks;
import org.geysermc.paletter.internal.CanonicalItems;
import org.geysermc.paletter.internal.Canonicals;
import org.geysermc.paletter.internal.Forwarding;
import org.geysermc.paletter.item.ItemVariant;
import org.geysermc.paletter.item.ResolvedItem;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;

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
        return Canonicals.item(canonicalItems(p), canonicalBlocks(p), variant);
    }

    public static int item(Palette p, ItemVariant variant) {
        return Canonicals.item(canonicalItems(p), canonicalBlocks(p), variant);
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
        BedrockBlockState block = p.blockOf(variant);
        return block == null ? -1 : block(p, block);
    }

    public static Forwarding forwardBlock(Paletter p, NbtMap state) {
        return Canonicals.forwardBlock(canonicalBlocks(p), state);
    }

    public static Forwarding forwardBlock(Palette p, NbtMap state) {
        return Canonicals.forwardBlock(canonicalBlocks(p), state);
    }

    public static Forwarding forwardItem(Paletter p, ItemVariant variant) {
        return Canonicals.forwardItem(canonicalItems(p), canonicalBlocks(p), variant);
    }

    public static Forwarding forwardItem(Palette p, ItemVariant variant) {
        return Canonicals.forwardItem(canonicalItems(p), canonicalBlocks(p), variant);
    }

    public static int runtimeId(Palette p, int canonical) {
        return p.blocks().runtimeId(canonical);
    }

    public static PaletteBlock blockAt(Palette p, int canonical) {
        return p.blocks().block(canonical);
    }

    public static ResolvedItem itemAt(Palette p, int canonical) {
        return p.items().item(canonical);
    }

    public static int match(Palette p, ItemData data) {
        return p.items().match(data);
    }
}
