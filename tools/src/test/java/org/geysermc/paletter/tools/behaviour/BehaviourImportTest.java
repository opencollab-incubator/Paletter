package org.geysermc.paletter.tools.behaviour;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.behaviour.BlockBehaviour;
import org.geysermc.paletter.behaviour.ItemBehaviour;
import org.geysermc.paletter.behaviour.Provenance;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.behaviour.BehaviourStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviourImportTest {
    private static final DataVersion V1 = new DataVersion("1.0", 10);
    private static final DataVersion V2 = new DataVersion("2.0", 20);
    private static final DataVersion V3 = new DataVersion("3.0", 30);

    /** V1 has states 1-100, V2 adds 101, V3 has the same states as V2. */
    private static List<BehaviourImport.Bundled> bundled() {
        return List.of(version(V1, 100, Set.of("minecraft:sword")), version(V2, 101, Set.of("minecraft:sword")),
            version(V3, 101, Set.of("minecraft:sword", "minecraft:spear")));
    }

    private static BehaviourImport.Bundled version(DataVersion version, int states, Set<String> items) {
        Map<Integer, NbtMap> map = new LinkedHashMap<>();
        for (int i = 1; i <= states; i++) {
            map.put(i, NbtMap.builder().putString("name", "minecraft:b" + i).putCompound("states", NbtMap.EMPTY).build());
        }
        return new BehaviourImport.Bundled(version, map, items);
    }

    @Test
    void identifiesVersionsByTheirBlockStates() {
        BehaviourImport importer = new BehaviourImport(bundled());
        assertEquals(V1, importer.identify(ids(100)).version());
        assertEquals(1.0, importer.identify(ids(100)).similarity());
        assertEquals(V2, importer.identify(ids(101)).version(), "V2 and V3 have the same states: ties go to the oldest");
        Set<Integer> almost = ids(100);
        almost.add(1000);
        assertEquals(V1, importer.identify(almost).version(), "one state off in 101");
        assertNull(importer.identify(ids(50)), "not similar enough to any");
    }

    private static Set<Integer> ids(int count) {
        Set<Integer> ids = new java.util.TreeSet<>();
        for (int i = 1; i <= count; i++) {
            ids.add(i);
        }
        return ids;
    }

    @Test
    void readsTheGitHistoryAndBuildsTheStore(@TempDir Path repository) throws IOException, InterruptedException {
        git(repository, "init", "-q");
        commit(repository, dump(100, 0.6f), "first");
        commit(repository, dump(100, 0.7f), "fix of the first");
        commit(repository, dump(101, 0.98f), "second");

        BehaviourImport importer = new BehaviourImport(bundled());
        importer.cloudburstData(new GitHistory(repository));
        assertEquals(List.of(V2, V1), importer.captures().stream().map(BehaviourImport.Capture::version).toList(),
            "newest commit per version");

        // A weaker source of V1 that disagrees on one state, and covers an item
        Values<Integer> allayBlocks = new Values<>();
        allayBlocks.put(BlockBehaviour.FRICTION, 1, 0.5f);
        Values<String> allayItems = new Values<>();
        allayItems.put(ItemBehaviour.ATTACK_DAMAGE, "minecraft:sword", 7);
        importer.captures().add(new BehaviourImport.Capture(Provenance.Source.ALLAY_DATA, "allay", "", V1, "test", allayBlocks, allayItems));

        BehaviourStore store = importer.build();
        assertEquals(0.7f, store.block(BlockBehaviour.FRICTION, 1, V1).value(), "the fix, from the stronger source");
        assertEquals(0.98f, store.block(BlockBehaviour.FRICTION, 1, V3).value(), "V3 inherits V2");
        assertEquals("2.0", store.block(BlockBehaviour.FRICTION, 1, V3).capture().version());
        assertNull(store.block(BlockBehaviour.FRICTION, 101, V1));
        assertEquals(7, store.item(ItemBehaviour.ATTACK_DAMAGE, "minecraft:sword", V3).value());

        String report = new BehaviourReport(bundled(), importer.captures(), store, importer.log).render();
        assertTrue(report.contains("| 1.0: ALLAY_DATA vs CLOUDBURST_DATA") || report.contains("### 1.0: ALLAY_DATA vs CLOUDBURST_DATA"), report);
        assertTrue(report.contains("b1[] (1): 0.5 / 0.7"), report);
        assertTrue(report.contains("inherited from 2.0 CLOUDBURST_DATA 101"), report);
        assertTrue(report.contains("inherited from 1.0 ALLAY_DATA 1"), report);
        assertTrue(report.contains("`block:climbable` | none 100"), report);
    }

    private static String dump(int states, float friction) {
        List<String> entries = new ArrayList<>();
        for (int i = 1; i <= states; i++) {
            entries.add("{\"name\": \"minecraft:b" + i + "\", \"blockStateHash\": " + i + ", \"friction\": " + friction + "}");
        }
        return "[" + String.join(",", entries) + "]";
    }

    private static void commit(Path repository, String blocks, String message) throws IOException, InterruptedException {
        Files.writeString(repository.resolve("blocks.json"), blocks);
        git(repository, "add", "blocks.json");
        git(repository, "-c", "user.name=test", "-c", "user.email=test@example.com", "commit", "-q", "-m", message);
    }

    private static void git(Path repository, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(arguments));
        assertEquals(0, new ProcessBuilder(command).inheritIO().start().waitFor(), String.join(" ", command));
    }
}
