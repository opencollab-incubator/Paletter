package dev.opencollab.paletter.tools.blocks;

import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.internal.vanilla.BlockDefinitions;
import dev.opencollab.paletter.internal.vanilla.StateValues;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@link BlockModel} refuses to read from a palette, each with what it found, and how it orders what does not matter. */
class BlockModelTest {
    private static final StateValues FACING = StateValues.ints("facing", 0, 1);
    private static final StateValues OPEN = StateValues.bool("open_bit");

    private static BlockDefinitions.Palette door() {
        BlockDefinitions definitions = new BlockDefinitions();
        definitions.block("test:door", FACING, OPEN);
        definitions.block("test:stone");
        return definitions.derive();
    }

    private static NbtMap state(String name, Object... keysAndValues) {
        org.cloudburstmc.nbt.NbtMapBuilder states = NbtMap.builder();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            states.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return BlockStates.of(name, states.build());
    }

    private static void refused(String expected, List<NbtMap> states, Map<String, NbtMap> defaults) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> BlockModel.definitions("1.1", new BlockDefinitions.Palette(states, defaults)));
        assertTrue(e.getMessage().startsWith("1.1: ") && e.getMessage().contains(expected), e.getMessage());
    }

    @Test
    void wrongDefaultsAreRefused() {
        List<NbtMap> states = door().states();
        refused("open_bit has no value 7", states, Map.of("test:door", state("test:door", "facing", 0, "open_bit", (byte) 7)));
        refused("a default for test:none, which has no states", states, Map.of("test:none", state("test:none")));
        refused("the default of test:door is a state of test:stone", states, Map.of("test:door", state("test:stone")));
        refused("the default of test:door lacks open_bit", states, Map.of("test:door", state("test:door", "facing", 1)));
        refused("are not what the definitions give", states, Map.of("test:door", state("test:door", "facing", 1, "open_bit", (byte) 0, "extra", 1)));
    }

    @Test
    void statesTheDefinitionsCannotSayAreRefused() {
        List<NbtMap> split = new ArrayList<>(door().states());
        NbtMap stone = split.stream().filter(s -> BlockStates.name(s).equals("test:stone")).findFirst().orElseThrow();
        split.remove(stone);
        split.add(2, stone);
        refused("are not one run", split, Map.of());
        refused("have different keys", List.of(state("test:x", "a", 0), state("test:x", "b", 1)), Map.of());
        refused("values of another or of several types", List.of(state("test:x", "a", 0), state("test:x", "a", "one")), Map.of());
        refused("byte values [1, 0]", List.of(state("test:x", "a", (byte) 1), state("test:x", "a", (byte) 0)), Map.of());
        // Keys not sorted: the definitions write them sorted
        List<NbtMap> backwards = new ArrayList<>();
        for (int a = 0; a < 2; a++) {
            java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("b", 0);
            values.put("a", a);
            backwards.add(BlockStates.of("test:x", NbtMap.fromMap(values)));
        }
        refused("keys of state 0 of test:x are in the order [b, a]", backwards, Map.of());
    }

    @Test
    void propertiesOfOneValueGoFirstByName() {
        List<NbtMap> states = List.of(state("test:single", "a", 0, "y", "b", "z", "c"), state("test:single", "a", 1, "y", "b", "z", "c"));
        BlockDefinitions definitions = BlockModel.definitions("1.1", new BlockDefinitions.Palette(states, Map.of()));
        assertEquals(List.of("y", "z", "a"), definitions.get("test:single").properties().stream().map(StateValues::name).toList());
    }
}
