package org.geysermc.paletter.internal.vanilla;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.internal.block.BlockStates;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stride derivation on synthetic palettes: full products in any property order, single value properties, and blocks
 * that are no full product (or not in a stride order) falling back to a lookup table.
 */
class BlockLayoutTest {

    private static NbtMap state(String name, Object... keyValues) {
        Map<String, Object> states = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            states.put((String) keyValues[i], keyValues[i + 1]);
        }
        return BlockStates.of(name, states);
    }

    /** The full product of a (bool), b (0..2) and c (x/y), enumerated with the given property varying fastest first. */
    private static List<NbtMap> product(String name, String... fastestFirst) {
        Map<String, List<Object>> values = Map.of("a", List.of((byte) 0, (byte) 1), "b", List.of(0, 1, 2), "c", List.of("x", "y"));
        List<NbtMap> states = new ArrayList<>();
        int total = 12;
        for (int i = 0; i < total; i++) {
            Map<String, Object> state = new LinkedHashMap<>();
            int rest = i;
            for (String property : fastestFirst) {
                List<Object> list = values.get(property);
                state.put(property, list.get(rest % list.size()));
                rest /= list.size();
            }
            states.add(BlockStates.of(name, state));
        }
        return states;
    }

    private static void assertConsistent(BlockLayout layout, List<NbtMap> states) {
        // Every state: its combination maps back to its offset, and the offset follows from the value indices
        for (int offset = 0; offset < layout.count(); offset++) {
            NbtMap nbt = BlockStates.states(states.get(layout.first() + offset));
            int combination = layout.combination(offset);
            assertEquals(offset, layout.offset(combination), layout + " offset " + offset);
            int expected = 0;
            for (int p = 0; p < layout.properties().size(); p++) {
                expected += layout.values().get(p).indexOf(nbt.get(layout.properties().get(p))) * layout.radix(p);
            }
            assertEquals(expected, combination, layout + " offset " + offset);
        }
    }

    @Test
    void stridesFollowThePropertyOrderOfThePalette() {
        for (String[] order : List.of(new String[] {"a", "b", "c"}, new String[] {"c", "b", "a"}, new String[] {"b", "a", "c"})) {
            List<NbtMap> states = product("test:block", order);
            BlockLayout layout = BlockLayout.of(states).getFirst();
            assertTrue(layout.arithmetic());
            assertNull(layout.problem());
            assertEquals(List.of("a", "b", "c"), layout.properties());   // sorted, whatever the palette order
            assertEquals(12, layout.combinations());
            // The fastest property has stride 1, the next the size of the first, ...
            int[] strides = layout.strides();
            int stride = 1;
            for (String property : order) {
                int index = layout.properties().indexOf(property);
                assertEquals(stride, strides[index], String.join(",", order) + " " + property);
                stride *= layout.values().get(index).size();
            }
            assertConsistent(layout, states);
        }
    }

    @Test
    void valuesKeepTheirOrderOfFirstAppearance() {
        List<NbtMap> states = List.of(state("test:b", "dir", "south"), state("test:b", "dir", "west"), state("test:b", "dir", "north"));
        BlockLayout layout = BlockLayout.of(states).getFirst();
        assertEquals(List.of(List.of("south", "west", "north")), layout.values());
        assertArrayEquals(new int[] {1}, layout.strides());
    }

    @Test
    void blocksWithoutStatesAndSingleValues() {
        List<NbtMap> states = List.of(state("test:air"), state("test:one", "only", 3), state("test:two", "only", 3, "lit", (byte) 0),
            state("test:two", "only", 3, "lit", (byte) 1));
        List<BlockLayout> layouts = BlockLayout.of(states);
        assertEquals(3, layouts.size());
        assertEquals(List.of(), layouts.get(0).properties());
        assertEquals(1, layouts.get(0).combinations());
        assertEquals(0, layouts.get(0).offset(0));
        assertEquals(1, layouts.get(1).first());
        assertArrayEquals(new int[] {0}, layouts.get(1).strides());   // a single value never changes the offset
        BlockLayout two = layouts.get(2);
        assertEquals(2, two.first());
        assertTrue(two.arithmetic());
        assertConsistent(two, states);
    }

    @Test
    void anIncompleteProductFallsBackToATable() {
        // age 0, 2 and 5 with lit only set at 5: 3 states of 3 * 2 = 6 combinations
        List<NbtMap> states = List.of(state("test:sparse", "age", 0, "lit", (byte) 0), state("test:sparse", "age", 2, "lit", (byte) 0),
            state("test:sparse", "age", 5, "lit", (byte) 1));
        BlockLayout layout = BlockLayout.of(states).getFirst();
        assertFalse(layout.arithmetic());
        assertNull(layout.strides());
        assertEquals("3 states, but its values have 6 combinations: not a full product", layout.problem());
        assertEquals(6, layout.combinations());
        assertConsistent(layout, states);
        // age=2 (index 1), lit=1 (index 1) does not exist
        int age = layout.properties().indexOf("age");
        int lit = layout.properties().indexOf("lit");
        assertEquals(-1, layout.offset(layout.radix(age) + layout.radix(lit)));
    }

    @Test
    void aFullProductOutOfStrideOrderFallsBackToATable() {
        List<NbtMap> states = new ArrayList<>(product("test:shuffled", "a", "b", "c"));
        // Swap two states in the middle: still every combination once, but no strides describe the order
        NbtMap third = states.get(3);
        states.set(3, states.get(5));
        states.set(5, third);
        BlockLayout layout = BlockLayout.of(states).getFirst();
        assertFalse(layout.arithmetic());
        assertEquals("a full product, but not in a stride order", layout.problem());
        assertConsistent(layout, states);
    }

    @Test
    void brokenPalettesFailWithTheBlockNamed() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> BlockLayout.of(List.of(
            state("test:a", "lit", (byte) 0), state("test:b"), state("test:a", "lit", (byte) 1))));
        assertEquals("The states of test:a are not contiguous in the palette", e.getMessage());

        e = assertThrows(IllegalArgumentException.class, () -> BlockLayout.of(List.of(
            state("test:a", "lit", (byte) 0), state("test:a", "lit", (byte) 0))));
        assertTrue(e.getMessage().startsWith("test:a: duplicate state"), e.getMessage());

        e = assertThrows(IllegalArgumentException.class, () -> BlockLayout.of(List.of(
            state("test:a", "lit", (byte) 0, "age", 1), state("test:a", "lit", (byte) 1))));
        assertTrue(e.getMessage().startsWith("test:a: state ") && e.getMessage().endsWith("lacks age, which other states of the block have"),
            e.getMessage());
    }
}
