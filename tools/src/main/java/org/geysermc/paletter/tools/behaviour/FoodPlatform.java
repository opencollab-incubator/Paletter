package org.geysermc.paletter.tools.behaviour;

import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.tools.data.Nbt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The structure a game test of the food probe runs in, {@code paletter:platform}: a platform of stone one block thick, 3 by 3,
 * with one layer of air above it. A game test needs a structure, and a simulated player must not fall, so the probe's pack gets this
 * one from here ({@link org.geysermc.paletter.tools.bds.BdsRunner#withBinaryPackFiles}) instead of a binary file in the repository.
 */
final class FoodPlatform {
    /** Where the structure is in the pack: the name {@code paletter:platform} is this path without {@code structures/} and the extension. */
    static final String PATH = "structures/paletter/platform.mcstructure";
    static final int SIZE_X = 3;
    static final int SIZE_Y = 2;
    static final int SIZE_Z = 3;
    /** The data version of the blocks in the palette (1.21.0): any version BDS can read. */
    private static final int BLOCK_VERSION = (1 << 24) | (21 << 16);

    private FoodPlatform() {
    }

    /** @return the structure as a little endian NBT file */
    static byte[] bytes() throws IOException {
        List<Integer> blocks = new ArrayList<>();
        List<Integer> water = new ArrayList<>();
        for (int x = 0; x < SIZE_X; x++) {
            for (int y = 0; y < SIZE_Y; y++) {
                for (int z = 0; z < SIZE_Z; z++) {
                    blocks.add(y == 0 ? 1 : 0);
                    water.add(-1);
                }
            }
        }
        NbtMap palette = NbtMap.builder().putCompound("default", NbtMap.builder()
            .putList("block_palette", NbtType.COMPOUND, block("minecraft:air"), block("minecraft:stone"))
            .putCompound("block_position_data", NbtMap.EMPTY).build()).build();
        NbtMap structure = NbtMap.builder()
            .putList("block_indices", NbtType.LIST, new NbtList<>(NbtType.INT, blocks), new NbtList<>(NbtType.INT, water))
            .putList("entities", NbtType.COMPOUND)
            .putCompound("palette", palette).build();
        return Nbt.writeLe(NbtMap.builder()
            .putInt("format_version", 1)
            .putList("size", NbtType.INT, SIZE_X, SIZE_Y, SIZE_Z)
            .putCompound("structure", structure)
            .putList("structure_world_origin", NbtType.INT, 0, 0, 0).build());
    }

    private static NbtMap block(String name) {
        return NbtMap.builder().putString("name", name).putCompound("states", NbtMap.EMPTY).putInt("version", BLOCK_VERSION).build();
    }
}
