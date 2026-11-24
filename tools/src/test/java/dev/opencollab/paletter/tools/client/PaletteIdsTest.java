package dev.opencollab.paletter.tools.client;

import com.google.gson.JsonObject;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.tools.CommittedBuilds;
import dev.opencollab.paletter.tools.Json;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaletteIdsTest {
    private static String chunks(String json, int size) {
        StringBuilder lines = new StringBuilder();
        int total = (json.length() + size - 1) / size;
        for (int i = 0; i < total; i++) {
            lines.append("[Scripting] PALETTE ").append(i).append('/').append(total).append(' ')
                .append(json, i * size, Math.min(json.length(), (i + 1) * size)).append('\n');
        }
        return lines.toString();
    }

    @Test
    void pairsEachCellWithTheLastBlockUpdateOfItsPosition() throws IOException {
        String json = """
            {"cells": [[1, 201, 1, "minecraft:stone", {}], [3, 201, 1, "minecraft:sand", {}], [5, 201, 1, "minecraft:lever", {"open_bit": true, "lever_direction": "up_east_west"}], [7, 201, 1, "minecraft:dirt", {}], [9, 201, 1, "minecraft:gravel", {}]], "moved": [[1, "minecraft:air", {}], [4, "minecraft:air", {}]]}""";
        List<String> lines = chunks(json, 37).lines().toList();
        // Out of order, and the PALETTER lines of another add-on are not the probe's
        List<String> shuffled = new ArrayList<>(lines.reversed());
        shuffled.add("[Scripting] PALETTER 0/1 {}");
        PaletteIds.Result result = PaletteIds.pair(shuffled, Map.of("1 201 1", 5, "3 201 1", 0, "5 201 1", 2, "9 201 1", 0));
        // The sand and the gravel fell: their cells hold air, which they observe; the dirt sent nothing
        assertEquals(List.of(0, 2, 5), result.ids().stream().map(PaletteIds.Entry::id).toList(), "by id");
        assertEquals("minecraft:air[]", result.ids().getFirst().state().key());
        assertEquals("minecraft:lever[lever_direction=\"up_east_west\",open_bit=true,]", result.ids().get(1).state().key());
        assertEquals(List.of("minecraft:sand[]", "minecraft:dirt[]", "minecraft:gravel[]"),
            result.notObserved().keySet().stream().map(PaletteIds.State::key).toList());
        // Two cells that end in one state with different updates observe nothing of it
        PaletteIds.Result conflict = PaletteIds.pair(shuffled, Map.of("1 201 1", 5, "3 201 1", 0, "5 201 1", 2, "9 201 1", 7));
        assertEquals(List.of(2, 5), conflict.ids().stream().map(PaletteIds.Entry::id).toList());
        // A cell that changed again after the read back says nothing
        List<String> unsettled = new ArrayList<>(shuffled);
        unsettled.add("[Scripting] PALETTE-UNSETTLED [2]");
        assertEquals(List.of(0, 5), PaletteIds.pair(unsettled, Map.of("1 201 1", 5, "3 201 1", 0, "5 201 1", 2, "9 201 1", 0)).ids().stream()
            .map(PaletteIds.Entry::id).toList());

        assertThrows(IOException.class, () -> PaletteIds.pair(lines.subList(1, lines.size()), Map.of()), "a missing chunk");
    }

    @Test
    void aPaletteStateHasTheKeyOfTheScriptApiValues() {
        NbtMap lever = BlockStates.of("minecraft:lever", Map.of("open_bit", (byte) 1, "lever_direction", "up_east_west"));
        JsonObject states = new JsonObject();
        states.addProperty("lever_direction", "up_east_west");
        states.addProperty("open_bit", true);
        assertEquals(new PaletteIds.State("minecraft:lever", states).key(), PaletteIds.State.key(lever));
        NbtMap wheat = BlockStates.of("minecraft:wheat", Map.of("growth", 3));
        JsonObject growth = new JsonObject();
        growth.addProperty("growth", 3);
        assertEquals(new PaletteIds.State("minecraft:wheat", growth).key(), PaletteIds.State.key(wheat));
    }

    private static PaletteIds.Entry entry(int id, String name, String state, Object value) {
        JsonObject states = new JsonObject();
        if (state != null) {
            states.add(state, value instanceof Integer i ? new com.google.gson.JsonPrimitive(i) : new com.google.gson.JsonPrimitive((String) value));
        }
        return new PaletteIds.Entry(id, new PaletteIds.State(name, states));
    }

    @Test
    void theStatesOfABlockMustKeepThePalettesOrder() {
        List<NbtMap> palette = List.of(BlockStates.of("minecraft:a"), BlockStates.of("minecraft:shelf", Map.of("t", 0)),
            BlockStates.of("minecraft:shelf", Map.of("t", 1)), BlockStates.of("minecraft:z"));
        // A block the palette lacks shifts what follows it; an alias state of the Script API is left out
        PaletteIds.Comparison shifted = PaletteIds.compare(palette, List.of(entry(0, "minecraft:a", "alias", "x"),
            entry(1, "minecraft:new", null, null), entry(2, "minecraft:shelf", "t", 0), entry(3, "minecraft:shelf", "t", 1),
            entry(4, "minecraft:z", null, null)));
        assertEquals(List.of(), shifted.wrong());
        assertEquals(java.util.Set.of("minecraft:new"), shifted.otherBlocks());
        assertEquals(Map.of("minecraft:shelf", 1, "minecraft:z", 1), shifted.offset());
        // Swapped within the block
        PaletteIds.Comparison swapped = PaletteIds.compare(palette, List.of(entry(1, "minecraft:shelf", "t", 1), entry(2, "minecraft:shelf", "t", 0)));
        assertEquals(1, swapped.wrong().size());
        assertTrue(swapped.wrong().getFirst().startsWith("minecraft:shelf: the states are in another order"), swapped.wrong().getFirst());
        // A state the palette does not have
        assertEquals(1, PaletteIds.compare(palette, List.of(entry(1, "minecraft:shelf", "t", 2))).wrong().size());
    }

    /**
     * The sequential ids BDS gave the states of each block are in the order of the bundled palette of its protocol, with no state
     * between them the palette lacks: the order within a block, which the creative stacks alone cannot check. The probe's world has
     * the build's toggles on, so blocks a palette captured without them lacks may shift the ids of others (not a difference).
     */
    @Test
    void theCommittedIdsKeepTheOrderOfTheBundledPalettes() throws IOException {
        Paletter bundled = Paletter.builder().previews(true).build();
        List<String> problems = new ArrayList<>();
        for (String build : CommittedBuilds.all()) {
            Path file = file(build);
            if (!Files.exists(file)) {
                continue;
            }
            DataVersion version = bundled.version(Json.object(file).get("protocol").getAsInt());
            List<PaletteIds.Entry> observed = PaletteIds.read(file);
            List<String> wrong = PaletteIds.compare(Access.palette().vanillaData(bundled, version).blockStates(), observed).wrong();
            if (!wrong.isEmpty()) {
                problems.add(build + " against " + version.name() + ": " + wrong.size() + " of " + observed.size() + " states, e.g. "
                    + String.join("; ", wrong.subList(0, Math.min(10, wrong.size()))));
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        CommittedBuilds.assertPending("vanilla/" + PaletteIds.FILE, build -> Files.exists(file(build)));
    }

    private static Path file(String build) {
        return CommittedBuilds.directory(build).resolve("vanilla").resolve(PaletteIds.FILE);
    }
}
