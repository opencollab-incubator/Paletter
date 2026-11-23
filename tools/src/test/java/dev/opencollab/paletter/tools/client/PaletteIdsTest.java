package dev.opencollab.paletter.tools.client;

import com.google.gson.JsonObject;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.data.DataVersion;
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
            {"cells": [[1, 201, 1, "minecraft:stone", {}], [3, 201, 1, "minecraft:sand", {}], [5, 201, 1, "minecraft:lever", {"open_bit": true, "lever_direction": "up_east_west"}], [7, 201, 1, "minecraft:dirt", {}]], "moved": [1]}""";
        List<String> lines = chunks(json, 37).lines().toList();
        // Out of order, and the PALETTER lines of another add-on are not the probe's
        List<String> shuffled = new ArrayList<>(lines.reversed());
        shuffled.add("[Scripting] PALETTER 0/1 {}");
        PaletteIds.Result result = PaletteIds.pair(shuffled, Map.of("1 201 1", 5, "3 201 1", 9, "5 201 1", 2));
        assertEquals(List.of(2, 5), result.ids().stream().map(PaletteIds.Entry::id).toList(), "by id; the sand fell, the dirt sent nothing");
        assertEquals("minecraft:lever[lever_direction=\"up_east_west\",open_bit=true,]", result.ids().getFirst().state().key());
        assertEquals(2, result.notObserved().size());

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

    @Test
    void anIdOutsideItsPlaceIsAMismatch() {
        List<NbtMap> palette = List.of(BlockStates.of("minecraft:a"), BlockStates.of("minecraft:b"));
        List<PaletteIds.Entry> observed = List.of(new PaletteIds.Entry(0, new PaletteIds.State("minecraft:a", new JsonObject())),
            new PaletteIds.Entry(0, new PaletteIds.State("minecraft:b", new JsonObject())));
        List<String> wrong = PaletteIds.mismatches(palette, observed);
        assertEquals(1, wrong.size());
        assertTrue(wrong.getFirst().startsWith("minecraft:b[]: BDS 0, palette 1"), wrong.getFirst());
    }

    /**
     * The sequential id BDS gave every state it was probed with is the index of that state in the bundled palette of its protocol: the
     * order of the blocks (name hash) and of the states within each block, which the creative stacks alone cannot check.
     */
    @Test
    void theCommittedIdsAreTheIndexOfTheirStateInTheBundledPalette() throws IOException {
        CommittedBuilds.assertPending("vanilla/" + PaletteIds.FILE, build -> Files.exists(file(build)));
        Paletter bundled = Paletter.builder().previews(true).build();
        List<String> problems = new ArrayList<>();
        for (String build : CommittedBuilds.all()) {
            Path file = file(build);
            if (!Files.exists(file)) {
                continue;
            }
            DataVersion version = bundled.version(Json.object(file).get("protocol").getAsInt());
            List<PaletteIds.Entry> observed = PaletteIds.read(file);
            List<String> wrong = PaletteIds.mismatches(bundled.vanillaData(version).blockStates(), observed);
            if (!wrong.isEmpty()) {
                problems.add(build + " against " + version.name() + ": " + wrong.size() + " of " + observed.size() + " states, e.g. "
                    + String.join("; ", wrong.subList(0, Math.min(10, wrong.size()))));
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static Path file(String build) {
        return CommittedBuilds.directory(build).resolve("vanilla").resolve(PaletteIds.FILE);
    }
}
