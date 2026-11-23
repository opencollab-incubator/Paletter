package dev.opencollab.paletter.internal.vanilla;

import dev.opencollab.paletter.block.BlockRegistry;
import dev.opencollab.paletter.internal.block.BlockStates;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The runtime of the generated block definitions: the value lists, a block's states and default, the changes from one
 * version to the next, and the palette they derive (blocks by name hash, states as an odometer, keys sorted).
 */
class BlockDefinitionsTest {
    private static final StateValues FACING = StateValues.ints("facing", 0, 3);
    private static final StateValues OPEN = StateValues.bool("open_bit");
    private static final StateValues COLOUR = StateValues.strings("colour", "red", "blue");

    @Test
    void valueListsHoldWhatTheStateNbtHolds() {
        assertEquals(List.of((byte) 0, (byte) 1), OPEN.values());
        assertEquals(List.of(0, 1, 2, 3), FACING.values());
        assertEquals(List.of(4, 2, 9), StateValues.intList("odd", 4, 2, 9).values());
        assertEquals(1, OPEN.indexOf(true));
        assertEquals(1, OPEN.indexOf((byte) 1));
        assertEquals(1, COLOUR.indexOf("blue"));
        assertEquals(-1, COLOUR.indexOf("green"));
        assertThrows(IllegalArgumentException.class, () -> StateValues.strings("none"));
        assertThrows(IllegalArgumentException.class, () -> StateValues.ints("backwards", 3, 1));
    }

    @Test
    void aBlocksStatesAreAnOdometerWithTheLastPropertyFastest() {
        BlockDefinitions definitions = new BlockDefinitions();
        definitions.block("test:door", FACING, OPEN).defaultState(2, true);
        BlockDefinition door = definitions.get("test:door");
        assertEquals(8, door.stateCount());
        assertArrayEquals(new int[] {0, 1}, door.valueIndices(1));
        assertArrayEquals(new int[] {1, 0}, door.valueIndices(2));
        assertEquals(5, door.defaultState());
        // An inserted property takes its first value in the default; the others keep theirs
        definitions.addProperties("test:door", 1, COLOUR);
        assertEquals(List.of(FACING, COLOUR, OPEN), definitions.get("test:door").properties());
        assertArrayEquals(new int[] {2, 0, 1}, definitions.get("test:door").valueIndices(definitions.get("test:door").defaultState()));
        definitions.removeProperty("test:door", "open_bit");
        assertArrayEquals(new int[] {2, 0}, definitions.get("test:door").valueIndices(definitions.get("test:door").defaultState()));
        definitions.defaultState("test:door", 0, "red");
        assertEquals(0, definitions.get("test:door").defaultState());
    }

    @Test
    void whatCannotBeIsRefused() {
        BlockDefinitions definitions = new BlockDefinitions();
        definitions.block("test:a", FACING);
        assertThrows(IllegalStateException.class, () -> definitions.block("test:a"));
        assertThrows(IllegalStateException.class, () -> definitions.remove("test:b"));
        assertThrows(IllegalArgumentException.class, () -> definitions.block("test:c", OPEN, OPEN));
        assertThrows(IllegalArgumentException.class, () -> definitions.defaultState("test:a", 9));
        assertThrows(IllegalArgumentException.class, () -> definitions.defaultState("test:a", 1, 2));
        assertThrows(IllegalArgumentException.class, () -> definitions.removeProperty("test:a", "open_bit"));
        assertThrows(IllegalArgumentException.class, () -> definitions.addProperties("test:a", 5, OPEN));
    }

    @Test
    void thePaletteIsOrderedByNameHashWithSortedKeysAndTheDefaultsThatAreNotTheFirstState() {
        BlockDefinitions definitions = new BlockDefinitions();
        List<String> names = List.of("test:alpha", "test:beta", "test:gamma", "test:delta");
        names.forEach(name -> definitions.block(name, OPEN, COLOUR));
        definitions.defaultState("test:beta", true, "blue");
        definitions.defaultState("test:gamma", false, "red");
        BlockDefinitions.Palette palette = definitions.derive();
        List<String> expected = new ArrayList<>(names);
        expected.sort((a, b) -> Long.compareUnsigned(BlockRegistry.nameHash(a), BlockRegistry.nameHash(b)));
        List<String> order = new ArrayList<>();
        palette.states().forEach(state -> {
            if (!order.contains(BlockStates.name(state))) {
                order.add(BlockStates.name(state));
            }
            assertEquals(List.of("colour", "open_bit"), new ArrayList<>(BlockStates.states(state).keySet()));
        });
        assertEquals(expected, order);
        int first = order.indexOf("test:beta") * 4;
        assertEquals(NbtMap.builder().putString("colour", "red").putByte("open_bit", (byte) 0).build(), BlockStates.states(palette.states().get(first)));
        assertEquals(NbtMap.builder().putString("colour", "blue").putByte("open_bit", (byte) 0).build(), BlockStates.states(palette.states().get(first + 1)));
        assertEquals(Map.of("test:beta", palette.states().get(first + 3)), palette.defaults());
    }

    @Test
    void aCopyIsChangedOnItsOwn() {
        BlockDefinitions before = new BlockDefinitions();
        before.block("test:a", OPEN);
        BlockDefinitions after = before.copy();
        after.remove("test:a");
        after.block("test:b");
        assertEquals(List.of("test:a"), before.blocks().stream().map(BlockDefinition::identifier).toList());
        assertEquals(List.of("test:b"), after.blocks().stream().map(BlockDefinition::identifier).toList());
    }
}
