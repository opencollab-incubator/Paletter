package dev.opencollab.paletter.internal.block;

import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A block state over a {@link StateMap} is the same NBT as one a builder makes: equal, same hash, same bytes. */
class StateMapTest {
    private static final NbtMap STATES = NbtMap.builder().putString("pillar_axis", "y").putBoolean("lit", true).build();

    private static NbtMap built() {
        return NbtMap.builder().putString("name", "minecraft:log").putCompound("states", STATES).build();
    }

    private static byte[] write(NbtMap nbt) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (NBTOutputStream output = NbtUtils.createNetworkWriter(bytes)) {
            output.writeTag(nbt);
        }
        return bytes.toByteArray();
    }

    @Test
    void equalToABuiltState() {
        NbtMap compact = BlockStates.of("minecraft:log", STATES);
        NbtMap built = built();
        assertEquals(built, compact);
        assertEquals(compact, built);
        assertEquals(built.hashCode(), compact.hashCode());
        assertEquals(built.toString(), compact.toString());
        assertEquals(Map.of(built, 1).get(compact), 1);
        assertEquals(Map.of(compact, 1).get(built), 1);
        assertFalse(compact.equals(BlockStates.of("minecraft:log", NbtMap.EMPTY)));
        assertFalse(compact.equals(BlockStates.of("minecraft:stone", STATES)));
    }

    @Test
    void writesTheSameBytes() throws IOException {
        assertArrayEquals(write(built()), write(BlockStates.of("minecraft:log", STATES)));
        // Inside a list, as a palette sends its states
        NbtMap built = NbtMap.builder().putList("blocks", org.cloudburstmc.nbt.NbtType.COMPOUND, List.of(built(), built())).build();
        NbtMap compact = NbtMap.builder().putList("blocks", org.cloudburstmc.nbt.NbtType.COMPOUND,
            List.of(BlockStates.of("minecraft:log", STATES), BlockStates.of("minecraft:log", STATES))).build();
        assertArrayEquals(write(built), write(compact));
    }

    @Test
    void readsLikeAMap() {
        NbtMap compact = BlockStates.of("minecraft:log", STATES);
        assertEquals(List.of("name", "states"), List.copyOf(compact.keySet()));
        assertEquals("minecraft:log", compact.getString("name"));
        assertEquals(STATES, compact.getCompound("states"));
        assertNull(compact.get("version"));
        assertTrue(compact.containsKey("states"));
        assertEquals(2, compact.size());
        assertEquals(built(), compact.toBuilder().build());
        assertEquals(BlockStates.with(built(), "lit", false), BlockStates.with(compact, "lit", false));
        assertThrows(UnsupportedOperationException.class, () -> compact.put("version", 1));
    }
}
