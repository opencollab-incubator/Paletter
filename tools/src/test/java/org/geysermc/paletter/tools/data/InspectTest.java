package org.geysermc.paletter.tools.data;

import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.block.BlockNetworkIds;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectTest {
    private static final Paletter PALETTER = Paletter.bundled();

    private static String run(String version, String block, String item) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Inspect.run(PALETTER, version, block, item, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void blockForEveryVersion() {
        String out = run("all", "oak_stairs[upside_down_bit=true]", null);
        assertEquals(PALETTER.versions().size(), out.lines().filter(l -> l.startsWith("== ")).count());
        assertTrue(out.contains("block minecraft:oak_stairs"), out);
        assertTrue(out.contains("upside_down_bit=1"), out);
    }

    @Test
    void itemForOneVersion() {
        String out = run(PALETTER.latest().name(), null, "minecraft:potion:5");
        assertTrue(out.contains("== " + PALETTER.latest().name()), out);
        assertTrue(out.contains("item minecraft:potion:5"), out);
        assertTrue(out.contains("creative:"), out);
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> run("all", null, null));
        assertThrows(IllegalArgumentException.class, () -> run("all", "stone", "apple"));
        assertThrows(IllegalArgumentException.class, () -> run("0.0", "stone", null));
    }

    @Test
    void parsesStateTypes() {
        var newest = PALETTER.vanilla(PALETTER.latest(), BlockNetworkIds.HASHED);
        var state = Inspect.parseBlock(newest, "oak_stairs[upside_down_bit=true,weirdo_direction=2]");
        assertEquals((byte) 1, state.getCompound("states").get("upside_down_bit"));
        assertEquals(2, state.getCompound("states").get("weirdo_direction"));
        assertEquals(5, Inspect.parseItem("potion:5").damage());
        assertEquals("minecraft:potion", Inspect.parseItem("potion:5").identifier());
    }
}
