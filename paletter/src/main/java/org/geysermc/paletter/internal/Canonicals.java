package org.geysermc.paletter.internal;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.item.ItemVariant;

/**
 * The lookups from content to canonical ids that {@link org.geysermc.paletter.Paletter} and
 * {@link org.geysermc.paletter.Palette} build their handle returning API on. Not API.
 */
public final class Canonicals {
    private Canonicals() {
    }

    /**
     * @return the canonical id of a state of any bundled version (mapped forward when the newest lacks it), or -1
     */
    public static int block(@NonNull CanonicalBlocks blocks, @NonNull NbtMap state) {
        int exact = blocks.index(state);
        return exact != -1 ? exact : blocks.forward(state).canonical();
    }

    /**
     * @return the canonical id of a variant of any bundled version (mapped forward when the newest lacks it), or -1
     */
    public static int item(@NonNull CanonicalItems items, @NonNull CanonicalBlocks blocks, @NonNull ItemVariant variant) {
        int exact = items.index(variant);
        return exact != -1 ? exact : items.forward(variant, blocks).canonical();
    }

    public static @NonNull Forwarding forwardBlock(@NonNull CanonicalBlocks blocks, @NonNull NbtMap state) {
        return blocks.forward(state);
    }

    public static @NonNull Forwarding forwardItem(@NonNull CanonicalItems items, @NonNull CanonicalBlocks blocks, @NonNull ItemVariant variant) {
        return items.forward(variant, blocks);
    }
}
