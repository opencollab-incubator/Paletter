package org.geysermc.paletter.generator;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.block.BlockStates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BdsDumpTest {
    private static final String JSON = """
        {"format":1,"item_tags":{"minecraft:is_food":["minecraft:bread","minecraft:apple"]},\
        "block_defaults":{"minecraft:oak_stairs":{"upside_down_bit":false,"weirdo_direction":0,"minecraft:corner":"none"}}}""";

    @Test
    void reassemblesChunksFromABdsLog(@TempDir Path directory) throws IOException {
        Path log = directory.resolve("bds.log");
        Files.write(log, logLines(JSON, 40));

        BdsDump dump = BdsDump.read(log);
        assertEquals(List.of("minecraft:apple", "minecraft:bread"), dump.itemTags().get("minecraft:is_food"));
        NbtMap stairs = dump.blockDefaults().get("minecraft:oak_stairs");
        assertEquals("minecraft:oak_stairs", BlockStates.name(stairs));
        // Script API booleans and numbers become NBT bytes and ints, like vanilla block states
        assertEquals((byte) 0, BlockStates.states(stairs).get("upside_down_bit"));
        assertEquals(0, BlockStates.states(stairs).get("weirdo_direction"));
        assertEquals("none", BlockStates.states(stairs).get("minecraft:corner"));
    }

    @Test
    void keepsOnlyTheLastDumpOfARestartedServer(@TempDir Path directory) throws IOException {
        Path log = directory.resolve("bds.log");
        List<String> lines = new ArrayList<>(logLines("{\"broken", 3));
        lines.addAll(logLines(JSON, 50));
        Files.write(log, lines);
        assertEquals(1, BdsDump.read(log).itemTags().size());
    }

    @Test
    void rejectsIncompleteDumps(@TempDir Path directory) throws IOException {
        Path log = directory.resolve("bds.log");
        List<String> lines = new ArrayList<>(logLines(JSON, 40));
        lines.remove(1);
        Files.write(log, lines);
        assertThrows(IOException.class, () -> BdsDump.read(log));
    }

    private static List<String> logLines(String json, int chunkSize) {
        List<String> lines = new ArrayList<>();
        lines.add("[2026-11-07 02:00:00:000 INFO] Server started.");
        int total = (json.length() + chunkSize - 1) / chunkSize;
        for (int i = 0; i < total; i++) {
            String slice = json.substring(i * chunkSize, Math.min(json.length(), (i + 1) * chunkSize));
            lines.add("[2026-11-07 02:00:01:000 WARN] [Scripting] PALETTER " + i + "/" + total + " " + slice);
        }
        return lines;
    }
}
