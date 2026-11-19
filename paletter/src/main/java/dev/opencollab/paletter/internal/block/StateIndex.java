package dev.opencollab.paletter.internal.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;

/**
 * The positions of block states in an array, found by {@link BlockStates#hash}: open addressing over the hashes, which are
 * compared before the states are. Built once, then immutable, so reads need no synchronization and never allocate.
 * <p>
 * States are mostly looked up as the kept instance itself (a handle's {@code toNbt()}, a definition's {@code getState()}),
 * so a lookup first looks for the instance and compares contents only if it is not there. The hash ignores the order of
 * the values (it adds them up), so states of one block that differ in two values often share a hash.
 */
public final class StateIndex {
    private final NbtMap[] states;
    private final int[] hashes;
    /** The position plus one; 0 for an empty slot. */
    private final int[] positions;
    private final int mask;

    /**
     * @param states the states to index, not copied: they must not change. Add them with {@link #add}
     */
    public StateIndex(@NonNull NbtMap @NonNull [] states) {
        this.states = states;
        // At most three quarters full
        int capacity = Integer.highestOneBit(Math.max(4, states.length * 4 / 3) * 2 - 1);
        this.hashes = new int[capacity];
        this.positions = new int[capacity];
        this.mask = capacity - 1;
    }

    private static int mix(int hash) {
        int h = hash * 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    /**
     * Indexes the state at a position; only while the index is built.
     *
     * @return -1, or the position of an equal state indexed before, in which case nothing is added
     */
    public int add(int position) {
        NbtMap state = states[position];
        int existing = indexOf(state);
        if (existing != -1) {
            return existing;
        }
        int hash = BlockStates.hash(state);
        int slot = mix(hash) & mask;
        while (positions[slot] != 0) {
            slot = (slot + 1) & mask;
        }
        hashes[slot] = hash;
        positions[slot] = position + 1;
        return -1;
    }

    /**
     * @param state a block state as {@code {name, states}}; other keys are ignored
     * @return the position of the state, or -1
     */
    public int indexOf(@NonNull NbtMap state) {
        int hash = BlockStates.hash(state);
        int start = mix(hash) & mask;
        int position;
        for (int slot = start; (position = positions[slot]) != 0; slot = (slot + 1) & mask) {
            if (hashes[slot] == hash && states[position - 1] == state) {
                return position - 1;
            }
        }
        for (int slot = start; (position = positions[slot]) != 0; slot = (slot + 1) & mask) {
            if (hashes[slot] == hash && BlockStates.same(state, states[position - 1])) {
                return position - 1;
            }
        }
        return -1;
    }
}
