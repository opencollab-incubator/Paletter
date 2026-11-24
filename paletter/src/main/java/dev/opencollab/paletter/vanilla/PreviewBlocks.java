package dev.opencollab.paletter.vanilla;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.internal.CanonicalBlocks;
import dev.opencollab.paletter.internal.vanilla.BlockLayout;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The handles of the blocks a palette's newest version added or changed since the newest release, which the generated
 * constants and {@link VanillaIndex} are linked to: with previews, those of the preview. They are made from the canonical
 * blocks of the palette, once, when the first handle of such a root is asked for. A block only the preview has is its
 * constant, which carries the preview's states ({@code @SincePreview}): its handles here are the constant's states. A
 * block of the release that only gained a value or property has all its states here, so each identifier has one type
 * per root; that type equals the constant if the states are the same. The states know their canonical id in their
 * root, and find it by their NBT in others.
 */
final class PreviewBlocks {
    private final Int2ObjectMap<BedrockBlockState> states = new Int2ObjectOpenHashMap<>();
    /** The constants of blocks the bundled release lacks that this root has, to the canonical id of their first state. */
    private final Map<BedrockBlockType, Integer> firsts = new IdentityHashMap<>();

    private PreviewBlocks(CanonicalBlocks root) {
        VanillaIndex index = VanillaIndex.get();
        int[] bundled = root.vanillaIdsIn(index.blocks);
        List<NbtMap> all = new ArrayList<>(root.vanillaSize());
        for (int id = 0; id < root.vanillaSize(); id++) {
            all.add(root.state(id));
        }
        for (BlockLayout source : BlockLayout.of(all)) {
            if (!hasNewState(source, bundled)) {
                continue;
            }
            // A block only the preview has: its states are those of its constant, one object for one state everywhere
            BedrockBlockType constant = index.blockOrConstant(source.identifier());
            if (constant != null && !constant.isAvailable() && constant.layout().sameStates(source)) {
                BlockTypeLayout layout = constant.layout();
                firsts.put(constant, source.first());
                for (int offset = 0; offset < source.count(); offset++) {
                    states.put(source.first() + offset, layout.byOffset(offset));
                }
                continue;
            }
            // Equal to the constant (a vanilla type is its identifier), which names it
            BedrockBlockType type = new BedrockBlockType(constant == null ? null : constant.constantName(), source.identifier(),
                VanillaTypes.UNKNOWN, false, null);
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
        return of(root).states.get(id);
    }

    /** The root keeps them: a volatile read per lookup, no lock (this is on the runtime id to key path of a preview palette). */
    private static PreviewBlocks of(CanonicalBlocks root) {
        return (PreviewBlocks) root.previewBlocks(PreviewBlocks::new);
    }

    /**
     * @param root the canonical blocks of a palette lineage, not those of the bundled release
     * @param constant a constant the bundled release lacks
     * @return the canonical id of its first state in the root, or -1 if the root lacks the block (or has other states)
     */
    static int first(CanonicalBlocks root, BedrockBlockType constant) {
        Integer first = of(root).firsts.get(constant);
        return first == null ? -1 : first;
    }
}
