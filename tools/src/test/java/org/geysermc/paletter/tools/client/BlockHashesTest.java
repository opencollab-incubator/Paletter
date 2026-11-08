package org.geysermc.paletter.tools.client;

import org.geysermc.paletter.tools.data.Nbt;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hashes BDS 1.26.63 sent in the creative inventory (block-network-ids-are-hashes=true). */
class BlockHashesTest {

    @Test
    void vanillaStates() {
        assertEquals(2186211206L, Integer.toUnsignedLong(BlockHashes.hash(state("minecraft:dirt", Map.of()))));
        assertEquals(4172483059L, Integer.toUnsignedLong(BlockHashes.hash(state("minecraft:oak_stairs", Map.of(
            "weirdo_direction", 0, "upside_down_bit", (byte) 0, "minecraft:corner", "none")))));
    }

    @Test
    void customStatesIncludeTraits() throws IOException {
        Path file = Path.of("../data/bds/1.26.63.1/addon/custom_block_properties.nbt");
        Map<String, NbtMap> blocks = new java.util.HashMap<>();
        for (NbtMap block : Nbt.readGzip(file).getList("blocks", NbtType.COMPOUND)) {
            blocks.put(block.getString("name"), block.getCompound("properties"));
        }
        // Default states (first of each property, then trait states) as the creative inventory sent them
        assertDefault(blocks, "paletter:states_block", 3387770614L, 3 * 2 * 4 * 2 * 4 * 6 * 2);
        assertDefault(blocks, "paletter:multi_block", 428099933L, 2);
        assertDefault(blocks, "paletter:connection_trait_block", 3371306093L, 16);
    }

    private static void assertDefault(Map<String, NbtMap> blocks, String name, long hash, int count) {
        List<NbtMap> states = BlockHashes.customStates(name, blocks.get(name));
        assertEquals(count, states.size(), name);
        assertEquals(hash, Integer.toUnsignedLong(BlockHashes.hash(states.getFirst())), name);
        assertTrue(states.stream().map(BlockHashes::hash).distinct().count() == count, name);
    }

    private static NbtMap state(String name, Map<String, Object> states) {
        return NbtMap.builder().putString("name", name).putCompound("states", NbtMap.fromMap(states)).build();
    }
}
