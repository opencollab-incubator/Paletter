package dev.opencollab.paletter.internal.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import org.checkerframework.checker.nullness.qual.Nullable;
import dev.opencollab.paletter.Origin;
import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.block.BlockHashes;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.internal.access.Access;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shares what hashed palettes of different versions have in common: vanilla
 * {@link RegisteredBlockState}s (a state has the same hash in every version) and pages of canonical id tables with equal
 * content. One per {@link dev.opencollab.paletter.Paletter}.
 * <p>
 * Internal: shared between Paletter's packages, not API.
 * <p>
 * Only vanilla content is interned, which is bounded by the bundled versions; custom blocks of (possibly short lived)
 * layers are not, so session palettes do not accumulate here.
 */
@Internal
public final class BlockInterner {
    /**
     * The vanilla states, by {@link BlockStates#hash}: palettes look their vanilla states up here, see {@link #knownVanillaBlock}.
     * Lookups read without a lock; states are added under the lock of this interner and published by writing this field.
     */
    private volatile Slots blocks = new Slots(64);
    /** The maps below share between the versions of a Paletter while they compile; null once {@link #releaseCompileMaps released}. */
    private @Nullable Map<VanillaBlockKey, RegisteredBlock> vanillaTypes = new HashMap<>();
    private @Nullable Map<IntPage, int[]> intPages = new HashMap<>();
    private @Nullable Map<BlockPage, RegisteredBlockState[]> blockPages = new HashMap<>();
    private int sharedPages;
    private int pages;

    /**
     * @return the shared hashed definition of a vanilla state; its hash is computed once
     */
    public @NonNull RegisteredBlockState vanillaBlock(@NonNull NbtMap state) {
        int hash = BlockStates.hash(state);
        RegisteredBlockState block = blocks.find(state, hash);
        if (block != null) {
            return block;
        }
        // The network hash allocates and is most of the work: not under the lock. Threads that race make equal blocks and
        // agree on the first
        RegisteredBlockState made = Access.block().registeredBlockState(BlockHashes.hash(state), state, false);
        synchronized (this) {
            Slots current = blocks;
            block = current.find(state, hash);
            if (block != null) {
                return block;
            }
            if (current.size * 2 >= current.blocks.length) {
                current = current.grown();
            }
            current.add(hash, made);
            // Publishes the added state (and a grown table) to lookups, which read this field first
            blocks = current;
        }
        return made;
    }

    /**
     * @return the shared definition of a vanilla state if one was made, else null. Takes no lock and does not allocate
     */
    public @Nullable RegisteredBlockState knownVanillaBlock(@NonNull NbtMap state) {
        return blocks.find(state, BlockStates.hash(state));
    }

    /**
     * @return the shared vanilla block with these states (shared instances, compared by identity) and data
     */
    public synchronized @NonNull RegisteredBlock vanillaType(@NonNull String identifier, @NonNull List<RegisteredBlockState> states,
                                                      @Nullable NbtMap propertyData, @Nullable NbtMap defaultState) {
        if (vanillaTypes == null) {
            // Released: a version compiled after that has blocks of its own, with the same values
            return new RegisteredBlock(identifier, Origin.VANILLA, states, null, propertyData, defaultState);
        }
        return vanillaTypes.computeIfAbsent(new VanillaBlockKey(identifier, states, propertyData, defaultState),
            key -> new RegisteredBlock(identifier, Origin.VANILLA, states, null, propertyData, defaultState));
    }

    /**
     * Drops the maps that share blocks and pages between versions (about 0.4 MB with every bundled version). Called once
     * every palette a Paletter serves is compiled: only the compilation of another vanilla palette uses them. A palette
     * compiled after that (a version not served) works the same, but its blocks and pages are copies of its own, like
     * the states of a version loaded after the intern map of {@code Paletter} was dropped. {@code extend} keeps
     * the canonical pages of its parent and builds only pages that hold custom blocks, which are not interned anyway.
     * The states ({@link #vanillaBlock}) stay: every palette looks its vanilla states up here.
     */
    public synchronized void releaseCompileMaps() {
        vanillaTypes = null;
        intPages = null;
        blockPages = null;
    }

    /** For tests: whether {@link #releaseCompileMaps} ran. */
    public synchronized boolean compileMapsReleased() {
        return vanillaTypes == null;
    }

    public synchronized int @NonNull [] intern(int @NonNull [] page) {
        pages++;
        if (intPages == null) {
            return page;
        }
        int[] existing = intPages.putIfAbsent(new IntPage(page), page);
        if (existing != null) {
            sharedPages++;
            return existing;
        }
        return page;
    }

    public synchronized @NonNull RegisteredBlockState @NonNull [] intern(@NonNull RegisteredBlockState @NonNull [] page) {
        pages++;
        if (blockPages == null) {
            return page;
        }
        RegisteredBlockState[] existing = blockPages.putIfAbsent(new BlockPage(page), page);
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

    /**
     * Open addressing over {@link BlockStates#hash}, like {@link StateIndex}, with the definitions in the slots. Only grows:
     * a lookup that reads it while a state is added may miss that state, never another one.
     */
    private static final class Slots {
        private final int[] hashes;
        /** Null for an empty slot. */
        private final RegisteredBlockState[] blocks;
        private final int mask;
        private int size;

        Slots(int capacity) {
            this.hashes = new int[capacity];
            this.blocks = new RegisteredBlockState[capacity];
            this.mask = capacity - 1;
        }

        private static int mix(int hash) {
            int h = hash * 0x9E3779B9;
            return h ^ (h >>> 16);
        }

        @Nullable RegisteredBlockState find(NbtMap state, int hash) {
            int start = mix(hash) & mask;
            RegisteredBlockState block;
            // Mostly looked up as the kept instance itself: by identity first, see StateIndex
            for (int slot = start; (block = blocks[slot]) != null; slot = (slot + 1) & mask) {
                if (hashes[slot] == hash && block.getState() == state) {
                    return block;
                }
            }
            for (int slot = start; (block = blocks[slot]) != null; slot = (slot + 1) & mask) {
                if (hashes[slot] == hash && BlockStates.same(state, block.getState())) {
                    return block;
                }
            }
            return null;
        }

        void add(int hash, RegisteredBlockState block) {
            int slot = mix(hash) & mask;
            while (blocks[slot] != null) {
                slot = (slot + 1) & mask;
            }
            // The hash first: a lookup that sees the block compares its hash
            hashes[slot] = hash;
            blocks[slot] = block;
            size++;
        }

        Slots grown() {
            Slots grown = new Slots(blocks.length * 2);
            for (int slot = 0; slot < blocks.length; slot++) {
                if (blocks[slot] != null) {
                    grown.add(hashes[slot], blocks[slot]);
                }
            }
            return grown;
        }
    }

    /** The default state, a kept state, is compared by {@link BlockStates#STRATEGY}: it is not iterated. */
    private record VanillaBlockKey(String identifier, List<RegisteredBlockState> states, @Nullable NbtMap propertyData,
                                   @Nullable NbtMap defaultState) {
        @Override
        public boolean equals(Object o) {
            return o instanceof VanillaBlockKey other && identifier.equals(other.identifier) && states.equals(other.states)
                && Objects.equals(propertyData, other.propertyData) && BlockStates.STRATEGY.equals(defaultState, other.defaultState);
        }

        @Override
        public int hashCode() {
            return Objects.hash(identifier, states, propertyData, BlockStates.STRATEGY.hashCode(defaultState));
        }
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
    private record BlockPage(RegisteredBlockState[] values) {
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
            for (RegisteredBlockState block : values) {
                hash = 31 * hash + System.identityHashCode(block);
            }
            return hash;
        }
    }
}
