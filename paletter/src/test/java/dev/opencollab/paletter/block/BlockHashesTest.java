package dev.opencollab.paletter.block;

import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.data.PaletteDataSource;
import dev.opencollab.paletter.internal.block.BlockStates;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BlockHashes#hash} feeds the bytes of the little endian NBT writer to the hash itself: it gives what the writer
 * gives, for every bundled state (that the writer's hash is the client's is checked against BDS by the tools).
 */
class BlockHashesTest {
    @Test
    void theDirectHashIsTheHashOfTheWrittenNbt() throws IOException {
        PaletteDataSource bundled = PaletteDataSource.bundled();
        int states = 0;
        for (DataVersion version : bundled.versions()) {
            for (NbtMap state : bundled.load(version).blockStates()) {
                String name = BlockStates.name(state);
                if (!name.equals("minecraft:unknown")) {
                    assertEquals(BlockHashes.hashWithWriter(name, state), BlockHashes.hash(state), state.toString());
                    states++;
                }
            }
        }
        assertTrue(states > 10_000);
        // Values of other types, a short here, take the writer's path
        NbtMap odd = NbtMap.builder().putString("name", "example:odd")
            .putCompound("states", NbtMap.builder().putShort("example:level", (short) 3).putString("example:face", "up").build()).build();
        assertEquals(BlockHashes.hashWithWriter("example:odd", odd), BlockHashes.hash(odd));
        assertEquals(-604749536, BlockHashes.hash(NbtMap.builder().putString("name", "minecraft:air").putCompound("states", NbtMap.EMPTY).build()));
    }
}
