package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.annotation.Internal;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Shares what {@link BlockNetworkIds#HASHED} palettes of different versions have in common: vanilla
 * {@link PaletteBlock}s (a state has the same hash in every version) and pages of canonical id tables with equal
 * content. One per {@link org.geysermc.paletter.Paletter}.
 * <p>
 * Internal: shared between Paletter's packages, not API.
 * <p>
 * Only vanilla content is interned, which is bounded by the bundled versions; custom blocks of (possibly short lived)
 * layers are not, so session palettes do not accumulate here.
 */
@Internal
public final class BlockInterner {
    private final Map<NbtMap, PaletteBlock> blocks = new HashMap<>();
    private final Map<IntPage, int[]> intPages = new HashMap<>();
    private final Map<BlockPage, PaletteBlock[]> blockPages = new HashMap<>();
    private int sharedPages;
    private int pages;

    /**
     * @return the shared hashed definition of a vanilla state; its hash is computed once
     */
    synchronized @NonNull PaletteBlock vanillaBlock(@NonNull NbtMap state) {
        PaletteBlock block = blocks.get(state);
        if (block == null) {
            block = new PaletteBlock(BlockNetworkIds.hash(state), state, false);
            blocks.put(state, block);
        }
        return block;
    }

    synchronized int @NonNull [] intern(int @NonNull [] page) {
        pages++;
        int[] existing = intPages.putIfAbsent(new IntPage(page), page);
        if (existing != null) {
            sharedPages++;
            return existing;
        }
        return page;
    }

    synchronized @NonNull PaletteBlock @NonNull [] intern(@NonNull PaletteBlock @NonNull [] page) {
        pages++;
        PaletteBlock[] existing = blockPages.putIfAbsent(new BlockPage(page), page);
        if (existing != null) {
            sharedPages++;
            return existing;
        }
        return page;
    }

    /**
     * @return {@code {pages interned, of which shared}}, for diagnostics
     */
    public synchronized int @NonNull [] pageStats() {
        return new int[] {pages, sharedPages};
    }

    private record IntPage(int[] values) {
        @Override
        public boolean equals(Object o) {
            return o instanceof IntPage other && Arrays.equals(values, other.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }
    }

    /**
     * Compared by element identity: interned blocks are unique per state.
     */
    private record BlockPage(PaletteBlock[] values) {
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof BlockPage other) || values.length != other.values.length) {
                return false;
            }
            for (int i = 0; i < values.length; i++) {
                if (values[i] != other.values[i]) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            int hash = 1;
            for (PaletteBlock block : values) {
                hash = 31 * hash + System.identityHashCode(block);
            }
            return hash;
        }
    }
}
