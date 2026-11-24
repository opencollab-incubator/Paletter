package dev.opencollab.paletter.internal;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.internal.item.ItemVariant;

/**
 * The lookups from content to canonical ids that {@link dev.opencollab.paletter.Palette} builds its handle returning API on.
 * Not API.
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
    public static int item(@NonNull CanonicalItems items, @NonNull ItemVariant variant) {
        int exact = items.index(variant);
        return exact != -1 ? exact : items.forward(variant).canonical();
    }

    public static @NonNull Forwarding forwardBlock(@NonNull CanonicalBlocks blocks, @NonNull NbtMap state) {
        return blocks.forward(state);
    }

    /**
     * @return the canonical id of a state for a {@code Lookup.RENAMED} lookup: like {@link #block}, but a value or
     *         combination the newest block lacks is no match ({@link CanonicalBlocks#forward(NbtMap, boolean)}), or -1
     */
    public static int lookupBlock(@NonNull CanonicalBlocks blocks, @NonNull NbtMap state) {
        int exact = blocks.index(state);
        return exact != -1 ? exact : blocks.forward(state, true).canonical();
    }

    public static @NonNull Forwarding lookupForwardBlock(@NonNull CanonicalBlocks blocks, @NonNull NbtMap state) {
        return blocks.forward(state, true);
    }

    public static @NonNull Forwarding forwardItem(@NonNull CanonicalItems items, @NonNull ItemVariant variant) {
        return items.forward(variant);
    }
}
