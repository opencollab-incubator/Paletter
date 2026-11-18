package org.geysermc.paletter.vanilla;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.internal.CanonicalBlocks;
import org.geysermc.paletter.internal.vanilla.BlockLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The handles of the blocks a palette's newest version added or changed since the newest release, which the generated
 * constants and {@link VanillaIndex} are linked to: with previews, those of the preview. They are made from the canonical
 * blocks of the palette, once, when the first handle of such a root is asked for. A block that only gained a value or
 * property has all its states here, so each identifier has one type per root. Their types are not the constants (those
 * resolve through their fallback); a type equals the constant if the constant is available with the same states. The states know their canonical
 * id in their root, and find it by their NBT in others.
 */
final class PreviewBlocks {
    private static final Map<CanonicalBlocks, PreviewBlocks> BY_ROOT = Collections.synchronizedMap(new WeakHashMap<>());

    private final Int2ObjectMap<BedrockBlockState> states = new Int2ObjectOpenHashMap<>();

    private PreviewBlocks(CanonicalBlocks root) {
        int[] bundled = root.vanillaIdsIn(VanillaIndex.get().blocks);
        List<NbtMap> all = new ArrayList<>(root.vanillaSize());
        for (int id = 0; id < root.vanillaSize(); id++) {
            all.add(root.state(id));
        }
        for (BlockLayout source : BlockLayout.of(all)) {
            if (!hasNewState(source, bundled)) {
                continue;
            }
            BedrockBlockType type = new BedrockBlockType(source.identifier(), VanillaTypes.UNKNOWN);
            NbtMap defaultState = root.defaultState(source.identifier());
            int defaultOffset = defaultState == null ? 0 : root.index(defaultState) - source.first();
            if (defaultOffset < 0 || defaultOffset >= source.count()) {
                defaultOffset = 0;
            }
            BlockTypeLayout layout = BlockTypeLayout.preview(type, source, root, defaultOffset);
            type.link(layout);
            for (int offset = 0; offset < source.count(); offset++) {
                states.put(source.first() + offset, layout.byOffset(offset));
            }
        }
    }

    private static boolean hasNewState(BlockLayout source, int[] bundled) {
        for (int offset = 0; offset < source.count(); offset++) {
            if (bundled[source.first() + offset] == -1) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param root the canonical blocks of a palette lineage
     * @param id a vanilla canonical id of it
     * @return its state if the block is one the bundled release lacks or has differently, else null
     */
    static BedrockBlockState state(CanonicalBlocks root, int id) {
        return BY_ROOT.computeIfAbsent(root, PreviewBlocks::new).states.get(id);
    }
}
