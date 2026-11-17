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
 * The handles of the states a palette's newest version has and the newest release (which the generated constants and
 * {@link VanillaIndex} are linked to) lacks: with previews, the states of the preview. They are made from the canonical
 * table of the palette, once, when the first one is asked for. Their types are not the constants (those resolve through
 * their fallback), they are equal to them; a state has no canonical id of the bundled data, so it finds its canonical id
 * by its NBT.
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
            // The first state is the default: which one BDS places is not in the canonical table
            BlockTypeLayout layout = BlockTypeLayout.vanilla(type, source, -1, 0);
            type.link(layout);
            for (int offset = 0; offset < source.count(); offset++) {
                if (bundled[source.first() + offset] == -1) {
                    states.put(source.first() + offset, layout.byOffset(offset));
                }
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
     * @param id a vanilla canonical id of it that the bundled release does not have
     */
    static BedrockBlockState state(CanonicalBlocks root, int id) {
        return BY_ROOT.computeIfAbsent(root, PreviewBlocks::new).states.get(id);
    }
}
