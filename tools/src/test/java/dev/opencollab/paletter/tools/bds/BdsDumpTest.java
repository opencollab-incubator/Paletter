package dev.opencollab.paletter.tools.bds;

import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.internal.block.BlockStates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BdsDumpTest {
    private static final String JSON = """
        {"format":1,"item_tags":{"minecraft:is_food":["minecraft:bread","minecraft:apple"]},        "block_defaults":{"minecraft:oak_stairs":{"upside_down_bit":false,"weirdo_direction":0,"minecraft:corner":"none"}}}""";

    private static final String JSON_V2 = """
        {"format":2,"item_tags":{"minecraft:is_food":["minecraft:bread","minecraft:apple"]},        "items":{"minecraft:bread":{"max_stack":64,"localization_key":"item.bread.name","components":["minecraft:food"]},        "minecraft:iron_sword":{"max_stack":1,"localization_key":"item.iron_sword.name","components":["minecraft:durability"],"max_durability":251}},        "block_defaults":{"minecraft:oak_planks":{"wood_type":"oak"},"minecraft:chest":{"minecraft:cardinal_direction":"north","direction":2}},        "block_states":{"minecraft:oak_planks":{"wood_type":["oak"]},"minecraft:chest":{"minecraft:cardinal_direction":["south","west","north","east"],"direction":[0,1,2,3]}},        "block_tags":{"wood":["minecraft:oak_planks"]},"block_items":{"minecraft:chest":"minecraft:chest"},"state_types":{},"errors":["no stack for item minecraft:air"]}""";

    private static String stateTypes(int format, String values) {
        return "{\"format\":" + format + ",\"item_tags\":{},\"block_defaults\":{},\"state_types\":{\"minecraft:corner\":" + values + ",\"flag\":[true,false]}}";
    }

    @Test
    void keepsTheValueOrderOfStateTypesFromFormatFour() {
        BdsDump dump = BdsDump.parse(stateTypes(4, "[\"none\",\"inner_left\",\"outer_right\"]"), "1.0.0.1");
        assertEquals(List.of("none", "inner_left", "outer_right"), dump.stateTypes().get("minecraft:corner"));
        assertEquals(List.of((byte) 1, (byte) 0), dump.stateTypes().get("flag"));
        // The stored form keeps the order too, other arrays are still sorted
        assertEquals(dump.stateTypes(), BdsDump.parse(BdsDump.canonicalJson(stateTypes(4, "[\"none\",\"inner_left\",\"outer_right\"]")), "1.0.0.1").stateTypes());
    }

    @Test
    void ignoresTheSortedStateTypesOfOlderDumps() {
        assertEquals(Map.of(), BdsDump.parse(stateTypes(3, "[\"inner_left\",\"none\"]"), "1.0.0.1").stateTypes());
    }

    @Test
    void reportsStateTypesWhoseValueOrderDiffers() {
        BdsDump a = BdsDump.parse(stateTypes(4, "[\"none\",\"inner_left\",\"outer_right\"]"), "1.0.0.1");
        BdsDump same = BdsDump.parse(stateTypes(4, "[\"none\",\"new\",\"inner_left\",\"outer_right\"]"), "1.0.0.2");
        BdsDump other = BdsDump.parse(stateTypes(4, "[\"inner_left\",\"none\",\"outer_right\"]"), "1.0.0.3");
        assertEquals(List.of(), a.valueOrderDifferences(same));
        assertEquals(1, a.valueOrderDifferences(other).size());
        assertEquals(List.of(), a.valueOrderDifferences(BdsDump.parse(stateTypes(3, "[\"none\"]"), "old")));
    }

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

    @Test
    void readsFormatTwoAndTheBdsVersion(@TempDir Path directory) throws IOException {
        Path log = directory.resolve("bds.log");
        List<String> lines = new ArrayList<>(logLines(JSON_V2, 100));
        lines.add(1, "[2026-11-07 02:00:00:000 INFO] Version: 1.26.63.1");
        Files.write(log, lines);

        BdsDump dump = BdsDump.read(log);
        assertEquals("1.26.63.1", dump.version());
        assertEquals(new BdsDump.Item(1, "item.iron_sword.name", List.of("minecraft:durability"), 251), dump.items().get("minecraft:iron_sword"));
        assertEquals(List.of(0, 1, 2, 3), dump.blockStates().get("minecraft:chest").get("direction"));
        assertEquals(List.of("minecraft:oak_planks"), dump.blockTags().get("wood"));
        assertEquals("minecraft:chest", dump.blockItems().get("minecraft:chest"));
        assertEquals(List.of("no stack for item minecraft:air"), dump.errors());
    }

    @Test
    void projectsDefaultsOntoPaletteStates(@TempDir Path directory) throws IOException {
        Path log = directory.resolve("bds.log");
        Files.write(log, logLines(JSON_V2, 100));
        BdsDump dump = BdsDump.read(log);

        List<NbtMap> palette = new ArrayList<>();
        palette.add(BlockStates.of("minecraft:oak_planks"));
        for (String direction : List.of("south", "west", "north", "east")) {
            palette.add(BlockStates.of("minecraft:chest", Map.of("minecraft:cardinal_direction", direction)));
        }
        List<String> rejected = new ArrayList<>();
        Map<String, NbtMap> defaults = dump.projectDefaults(palette, rejected);
        // Legacy alias states (wood_type, direction) are not network states and are dropped
        assertEquals(BlockStates.of("minecraft:oak_planks"), defaults.get("minecraft:oak_planks"));
        assertEquals(BlockStates.of("minecraft:chest", Map.of("minecraft:cardinal_direction", "north")), defaults.get("minecraft:chest"));
        assertEquals(List.of(), rejected);

        rejected.clear();
        dump.projectDefaults(List.of(BlockStates.of("minecraft:chest", Map.of("minecraft:cardinal_direction", "south"))), rejected);
        assertEquals(1, rejected.size());
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
