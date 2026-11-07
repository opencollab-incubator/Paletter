package org.geysermc.paletter.block;

import java.util.Arrays;

/**
 * Minimal open addressing int to non negative int map, for hashed runtime id to palette position. Immutable after
 * construction, so reads need no synchronization and never allocate.
 */
final class IntIndex {
    private final int[] keys;
    private final int[] values;
    private final int mask;

    IntIndex(int expected) {
        int capacity = Integer.highestOneBit(Math.max(4, expected) * 2 - 1) << 1;
        this.keys = new int[capacity];
        this.values = new int[capacity];
        this.mask = capacity - 1;
        Arrays.fill(values, -1);
    }

    private static int mix(int key) {
        int h = key * 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    /**
     * @return the previous value, or -1
     */
    int put(int key, int value) {
        int slot = mix(key) & mask;
        while (values[slot] != -1) {
            if (keys[slot] == key) {
                int previous = values[slot];
                values[slot] = value;
                return previous;
            }
            slot = (slot + 1) & mask;
        }
        keys[slot] = key;
        values[slot] = value;
        return -1;
    }

    /**
     * @return the value, or -1
     */
    int get(int key) {
        int slot = mix(key) & mask;
        int value;
        while ((value = values[slot]) != -1) {
            if (keys[slot] == key) {
                return value;
            }
            slot = (slot + 1) & mask;
        }
        return -1;
    }
}
