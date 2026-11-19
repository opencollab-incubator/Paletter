package dev.opencollab.paletter.internal.block;

import dev.opencollab.paletter.block.RegisteredBlockState;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeIdTableTest {
    private static RegisteredBlockState state(int id) {
        return new RegisteredBlockState(id, BlockStates.of("test:block_" + id), true);
    }

    @Test
    void everyKeyIsFoundWithItsStateAndPosition() {
        int size = 5000;
        int[] keys = new Random(7).ints(size).distinct().toArray();
        RegisteredBlockState[] states = new RegisteredBlockState[keys.length];
        RuntimeIdTable table = new RuntimeIdTable(keys.length);
        for (int i = 0; i < keys.length; i++) {
            states[i] = state(keys[i]);
            table.put(keys[i], i, states[i]);
        }
        for (int i = 0; i < keys.length; i++) {
            assertSame(states[i], table.get(keys[i]));
            assertEquals(i, table.index(keys[i]));
        }
    }

    @Test
    void positionsAboveACharNeedALargeTable() {
        RuntimeIdTable small = new RuntimeIdTable(RuntimeIdTable.SMALL_LIMIT - 1);
        small.put(1, RuntimeIdTable.SMALL_LIMIT - 2, state(1));
        assertEquals(RuntimeIdTable.SMALL_LIMIT - 2, small.index(1));
        assertThrows(IllegalArgumentException.class, () -> small.put(2, RuntimeIdTable.SMALL_LIMIT, state(2)));

        RuntimeIdTable large = new RuntimeIdTable(RuntimeIdTable.SMALL_LIMIT);
        large.put(1, RuntimeIdTable.SMALL_LIMIT, state(1));
        large.put(2, Integer.MAX_VALUE, state(2));
        assertEquals(RuntimeIdTable.SMALL_LIMIT, large.index(1));
        assertEquals(Integer.MAX_VALUE, large.index(2));
    }

    @Test
    void anyIntIsAKeyAndMissingKeysAreNotFound() {
        RuntimeIdTable table = new RuntimeIdTable(4);
        int[] keys = {-1, 0, Integer.MIN_VALUE, Integer.MAX_VALUE};
        for (int i = 0; i < keys.length; i++) {
            table.put(keys[i], i, state(keys[i]));
        }
        for (int i = 0; i < keys.length; i++) {
            assertEquals(keys[i], table.get(keys[i]).getRuntimeId());
            assertEquals(i, table.index(keys[i]));
        }
        assertNull(table.get(1));
        assertEquals(-1, table.index(-2));
        assertThrows(IllegalStateException.class, () -> table.put(0, 9, state(0)));
    }
}
