package org.geysermc.paletter.internal.block;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntIndexTest {
    @Test
    void agreesWithAMap() {
        Random random = new Random(1);
        int size = 25_000;
        IntIndex index = new IntIndex(size);
        Map<Integer, Integer> expected = new HashMap<>();
        for (int i = 0; i < size; i++) {
            // Runtime ids are hashes: any int, -1 and 0 included
            int key = i == 0 ? -1 : i == 1 ? 0 : random.nextInt();
            Integer previous = expected.put(key, i);
            assertEquals(previous == null ? -1 : previous, index.put(key, i));
        }
        for (int key : expected.keySet()) {
            assertEquals((int) expected.get(key), index.get(key));
        }
        for (int i = 0; i < size; i++) {
            int key = random.nextInt();
            assertEquals((int) expected.getOrDefault(key, -1), index.get(key));
        }
    }

    @Test
    void putReplacesTheValue() {
        IntIndex index = new IntIndex(4);
        assertEquals(-1, index.put(7, 1));
        assertEquals(1, index.put(7, 2));
        assertEquals(2, index.get(7));
        assertEquals(-1, index.get(8));
    }
}
