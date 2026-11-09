package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.update.Update;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.internal.data.PaletteDataCodec;
import org.geysermc.paletter.internal.data.VanillaData;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The state order of blocks the update pipeline generates from the Script API ({@link Update#keyOrder}), replayed on
 * the bundled releases: every block whose states changed from one release to the next, or that is new, generated from
 * the previous release must enumerate its states like the next release does.
 */
class KeyOrderTest {

    @Test
    void from2650To2660() throws Exception {
        // Script API key order as BDS 1.26.63.1 reported it (not the palette order)
        Map<String, List<String>> script = Map.of(
            "minecraft:redstone_wire", List.of("redstone_signal", "redstone_north", "redstone_east", "redstone_south", "redstone_west"),
            "minecraft:fire", List.of("age", "minecraft:connection_north", "minecraft:connection_up", "minecraft:connection_east",
                "minecraft:connection_west", "minecraft:connection_south"),
            "minecraft:chorus_plant", List.of("minecraft:connection_north", "minecraft:connection_up", "minecraft:connection_east",
                "minecraft:connection_west", "minecraft:connection_south", "minecraft:connection_down"),
            "minecraft:icicle", List.of("attached_bit", "hanging", "dripstone_thickness"));
        Map<String, List<String>> before = strides(read("26.50"));
        Map<String, List<String>> after = strides(read("26.60"));
        script.forEach((block, keys) -> {
            List<String> base = before.containsKey(block) ? before.get(block) : Update.similarBlockOrder(new LinkedHashSet<>(keys), before);
            assertEquals(after.get(block), Update.keyOrder(base, keys, before), block);
        });
    }

    @Test
    void from2640To2650() throws Exception {
        Map<String, List<String>> before = strides(read("26.40"));
        Map<String, List<String>> after = strides(read("26.50"));
        List<String> wrong = new ArrayList<>();
        int changed = 0;
        for (Map.Entry<String, List<String>> entry : after.entrySet()) {
            List<String> old = before.get(entry.getKey());
            if (old == null || new LinkedHashSet<>(old).equals(new LinkedHashSet<>(entry.getValue()))) {
                continue;
            }
            changed++;
            // The Script API's order is unknown here; the palette's, reversed, is the least helpful input
            List<String> keys = new ArrayList<>(entry.getValue());
            java.util.Collections.reverse(keys);
            if (!entry.getValue().equals(Update.keyOrder(old, keys, before))) {
                wrong.add(entry.getKey());
            }
        }
        // 67 stairs gained minecraft:corner (appended as the slowest state), 55 fences, panes and bars their connections
        assertEquals(123, changed, "wrong: " + wrong);
        // trip_wire put the connections it gained before its existing states
        assertEquals(List.of("minecraft:trip_wire"), wrong);
    }

    /** Generated states enumerate the last key fastest, but store their keys sorted, like BDS's palette. */
    @Test
    void combinationsKeepKeysSorted() {
        Map<String, List<Object>> values = new LinkedHashMap<>();
        values.put("z", List.of((byte) 0, (byte) 1));
        values.put("a", List.of(0, 1, 2));
        List<NbtMap> states = Update.combinations("test:block", values);
        assertEquals(6, states.size());
        assertEquals(List.of("z", "a"), Update.strideOrder(states).reversed());
        states.forEach(state -> assertEquals(List.of("a", "z"), new ArrayList<>(BlockStates.states(state).keySet())));
    }

    private static VanillaData read(String version) throws Exception {
        try (InputStream stream = KeyOrderTest.class.getResourceAsStream("/paletter/" + version + ".nbt")) {
            assertNotNull(stream);
            return PaletteDataCodec.read(stream);
        }
    }

    private static Map<String, List<String>> strides(VanillaData data) {
        Map<String, List<NbtMap>> blocks = new LinkedHashMap<>();
        data.blockStates().forEach(state -> blocks.computeIfAbsent(BlockStates.name(state), n -> new ArrayList<>()).add(state));
        Map<String, List<String>> strides = new LinkedHashMap<>();
        blocks.forEach((name, states) -> strides.put(name, Update.strideOrder(states)));
        return strides;
    }
}
