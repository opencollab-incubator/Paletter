package dev.opencollab.paletter.internal.block;

import dev.opencollab.paletter.block.RegisteredBlockState;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Open addressing map from block runtime id to the state's definition and its palette position: what decoding an id reads.
 * Keys, definitions and positions are parallel arrays of a power of two size, at most half full. A lookup of the definition
 * reads the key and the definition of a slot, which do not depend on each other, so their cache misses overlap; with the
 * position in its place, the definition would be a second, dependent read. Immutable once filled, so reads need no
 * synchronization and never allocate.
 */
public final class RuntimeIdTable {
    private final int[] keys;
    /** Null for an empty slot: a runtime id can be any int, so the key cannot mark one. */
    private final RegisteredBlockState[] values;
    private final int[] indexes;
    private final int mask;

    public RuntimeIdTable(int expected) {
        int capacity = Integer.highestOneBit(Math.max(4, expected) * 2 - 1) << 1;
        this.keys = new int[capacity];
        this.values = new RegisteredBlockState[capacity];
        this.indexes = new int[capacity];
        this.mask = capacity - 1;
    }

    private static int mix(int key) {
        int h = key * 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    /**
     * Adds a state; only while the table is built.
     *
     * @param key the runtime id, which must not be in the table yet
     * @param index the position of the state
     * @param value the state
     */
    public void put(int key, int index, RegisteredBlockState value) {
        int slot = mix(key) & mask;
        while (values[slot] != null) {
            if (keys[slot] == key) {
                throw new IllegalStateException("runtime id " + key + " is in the table already");
            }
            slot = (slot + 1) & mask;
        }
        keys[slot] = key;
        values[slot] = value;
        indexes[slot] = index;
    }

    /**
     * @return the state with this runtime id, or null
     */
    public @Nullable RegisteredBlockState get(int key) {
        int slot = mix(key) & mask;
        RegisteredBlockState value;
        while ((value = values[slot]) != null) {
            if (keys[slot] == key) {
                return value;
            }
            slot = (slot + 1) & mask;
        }
        return null;
    }

    /**
     * @return the position of the state with this runtime id, or -1
     */
    public int index(int key) {
        int slot = mix(key) & mask;
        while (values[slot] != null) {
            if (keys[slot] == key) {
                return indexes[slot];
            }
            slot = (slot + 1) & mask;
        }
        return -1;
    }
}
