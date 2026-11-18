package org.geysermc.paletter.tools.behaviour;

import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.tools.data.Nbt;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The structure the food probe's game test runs in. */
class FoodPlatformTest {
    @Test
    void theStructureIsAStonePlatformWithAirAboveIt() throws IOException {
        byte[] bytes = FoodPlatform.bytes();
        NbtMap root = Nbt.readLe(bytes, 0, bytes.length);
        assertEquals(List.of(3, 2, 3), root.getList("size", NbtType.INT));
        NbtMap structure = root.getCompound("structure");
        @SuppressWarnings("rawtypes")
        List<NbtList> indices = structure.getList("block_indices", NbtType.LIST);
        assertEquals(18, indices.get(0).size(), "3 x 2 x 3 blocks");
        assertEquals(18, indices.get(1).size(), "and the second layer, no waterlogging");
        List<NbtMap> palette = structure.getCompound("palette").getCompound("default").getList("block_palette", NbtType.COMPOUND);
        assertEquals(List.of("minecraft:air", "minecraft:stone"), palette.stream().map(block -> block.getString("name")).toList());
        // (x * sizeY + y) * sizeZ + z: the lower layer is stone, the one above it air
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 3; z++) {
                    assertEquals(y == 0 ? 1 : 0, indices.get(0).get((x * 2 + y) * 3 + z), "x " + x + " y " + y + " z " + z);
                }
            }
        }
        assertEquals(List.of(-1), indices.get(1).stream().distinct().toList());
        assertEquals(0, structure.getList("entities", NbtType.COMPOUND).size());
    }

    @Test
    void thePathIsTheStructureNameTheAddOnRegistersItsGameTestWith() throws IOException {
        assertEquals("structures/paletter/platform.mcstructure", FoodPlatform.PATH);
        String script = Files.readString(Path.of(System.getProperty("paletter.root", "..")).resolve("data/addons/food-probe/scripts/main.js"));
        assertTrue(script.contains("structureName(\"paletter:platform\")"));
    }
}
