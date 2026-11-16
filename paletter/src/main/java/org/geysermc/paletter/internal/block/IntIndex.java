package org.geysermc.paletter.internal.block;

import java.util.Arrays;

/**
 * Minimal open addressing int to non negative int map, for hashed runtime id to palette position. Immutable after
 * construction, so reads need no synchronization and never allocate.
 * <p>
 * Keys and values are interleaved in one array ({@code slots[2 * i]} is a key, {@code slots[2 * i + 1]} its value, -1 for
 * an empty slot), so a lookup reads one cache line rather than one in each of two arrays.
 */
public final class IntIndex {
    private final int[] slots;
    private final int mask;

    public IntIndex(int expected) {
        int capacity = Integer.highestOneBit(Math.max(4, expected) * 2 - 1) << 1;
        this.slots = new int[capacity * 2];
        this.mask = capacity - 1;
        Arrays.fill(slots, -1);
    }

    private static int mix(int key) {
        int h = key * 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    /**
     * @return the previous value, or -1
     */
    public int put(int key, int value) {
        int slot = mix(key) & mask;
        int previous;
        while ((previous = slots[2 * slot + 1]) != -1) {
            if (slots[2 * slot] == key) {
                slots[2 * slot + 1] = value;
                return previous;
            }
            slot = (slot + 1) & mask;
        }
        slots[2 * slot] = key;
        slots[2 * slot + 1] = value;
        return -1;
    }

    /**
     * @return the value, or -1
     */
    public int get(int key) {
        int slot = mix(key) & mask;
        int value;
        while ((value = slots[2 * slot + 1]) != -1) {
            if (slots[2 * slot] == key) {
                return value;
            }
            slot = (slot + 1) & mask;
        }
        return -1;
    }
}
