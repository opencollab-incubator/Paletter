package dev.opencollab.paletter.internal.block;

import dev.opencollab.paletter.block.RegisteredBlockState;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Open addressing map from block runtime id to the state's definition and its palette position: what decoding an id reads.
 * Keys, definitions and positions are parallel arrays of a power of two size, at most half full; positions are chars when a
 * table has fewer than 65,536 states (every vanilla table), else ints. A lookup of the definition
 * reads the key and the definition of a slot, which do not depend on each other, so their cache misses overlap; with the
 * position in its place, the definition would be a second, dependent read. Immutable once filled, so reads need no
 * synchronization and never allocate.
 */
public final class RuntimeIdTable {
    /** Tables for fewer states than this keep their positions as chars. */
    public static final int SMALL_LIMIT = 65_536;

    private final int[] keys;
    /** Null for an empty slot: a runtime id can be any int, so the key cannot mark one. */
    private final RegisteredBlockState[] values;
    /**
     * The positions, as chars (half the memory) while every position fits, else as ints: exactly one is not null. Read through
     * {@link #indexAt}.
     */
    private final char @Nullable [] smallIndexes;
    private final int @Nullable [] indexes;
    private final int mask;

    /**
     * @param expected the number of states; their positions are below it
     */
    public RuntimeIdTable(int expected) {
        int capacity = Integer.highestOneBit(Math.max(4, expected) * 2 - 1) << 1;
        this.keys = new int[capacity];
        this.values = new RegisteredBlockState[capacity];
        boolean small = expected < SMALL_LIMIT;
        this.smallIndexes = small ? new char[capacity] : null;
        this.indexes = small ? null : new int[capacity];
        this.mask = capacity - 1;
    }

    private int indexAt(int slot) {
        return smallIndexes != null ? smallIndexes[slot] : indexes[slot];
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
        if (smallIndexes != null) {
            if (index < 0 || index > Character.MAX_VALUE) {
                throw new IllegalArgumentException("position " + index + " of a table created for fewer states");
            }
            smallIndexes[slot] = (char) index;
        } else {
            indexes[slot] = index;
        }
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
                return indexAt(slot);
            }
            slot = (slot + 1) & mask;
        }
        return -1;
    }
}
