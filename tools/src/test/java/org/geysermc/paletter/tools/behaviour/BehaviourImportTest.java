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

        String report = new BehaviourReport(bundled(), importer.captures(), store, importer.log, importer.customDefaults()).render();
        assertTrue(report.contains("| 1.0: ALLAY_DATA vs CLOUDBURST_DATA") || report.contains("### 1.0: ALLAY_DATA vs CLOUDBURST_DATA"), report);
        assertTrue(report.contains("b1[] (1): 0.5 / 0.7"), report);
        assertTrue(report.contains("inherited from 2.0 CLOUDBURST_DATA 101"), report);
        assertTrue(report.contains("inherited from 1.0 ALLAY_DATA 1"), report);
        assertTrue(report.contains("`block:climbable` | none 100"), report);
    }

    private static BehaviourImport.Capture capture(Provenance.Source source, DataVersion version, Values<Integer> blocks) {
        return new BehaviourImport.Capture(source, source.name().toLowerCase() + "@" + version.name(), "2026-01-01", version, "test", blocks, new Values<>());
    }

    private static Values<Integer> friction(float... values) {
        Values<Integer> blocks = new Values<>();
        for (int i = 0; i < values.length; i++) {
            blocks.put(BlockBehaviour.FRICTION, i + 1, values[i]);
        }
        return blocks;
    }

    /** V1: Data and Allay disagree on state 1; a direct source settles state 2. V2: only Data, the same values. */
    private static BehaviourImport disputeScenario() {
        BehaviourImport importer = new BehaviourImport(bundled());
        importer.captures().add(capture(Provenance.Source.CLOUDBURST_DATA, V1, friction(0.7f, 0.7f, 0.7f)));
        importer.captures().add(capture(Provenance.Source.ALLAY_DATA, V1, friction(0.5f, 0.5f, 0.7f)));
        Values<Integer> direct = new Values<>();
        direct.put(BlockBehaviour.FRICTION, 2, 0.6f);
        importer.captures().add(capture(Provenance.Source.SCRIPT_API, V1, direct));
        importer.captures().add(capture(Provenance.Source.CLOUDBURST_DATA, V2, friction(0.7f, 0.7f, 0.7f)));
        return importer;
    }

    @Test
    void disagreementsAreDisputedUnlessBdsSettlesThem() {
        BehaviourStore store = disputeScenario().build();
        BehaviourStore.Resolved one = store.block(BlockBehaviour.FRICTION, 1, V1);
        assertEquals(0.7f, one.value(), "CloudburstMC/Data ranks above Allay");
        assertEquals(1, one.disputes().size());
        assertEquals(0.5f, one.disputes().getFirst().value());
        assertEquals(Provenance.Source.ALLAY_DATA, one.disputes().getFirst().capture().source());
        assertEquals(0.6f, store.block(BlockBehaviour.FRICTION, 2, V1).value(), "the direct capture decides");
        assertTrue(store.block(BlockBehaviour.FRICTION, 2, V1).disputes().isEmpty(), "BDS settled it");
        assertTrue(store.block(BlockBehaviour.FRICTION, 3, V1).disputes().isEmpty(), "they agree");
    }

    @Test
    void aDisputeIsCarriedToVersionsWithTheSameValueAndNoOtherSource() {
        BehaviourStore store = disputeScenario().build();
        BehaviourStore.Resolved carried = store.block(BlockBehaviour.FRICTION, 1, V2);
        assertEquals(1, carried.disputes().size(), "V2 only has Data, but its value is the one Allay disputed in V1");
        assertEquals("1.0", carried.disputes().getFirst().capture().version());
        assertTrue(store.block(BlockBehaviour.FRICTION, 3, V2).disputes().isEmpty());
    }

    @Test
    void whatASourceSaysItPostProcessedIsMarkedAndCarried() {
        BehaviourImport importer = new BehaviourImport(bundled());
        Values<Integer> newer = friction(0.7f);
        newer.reportsPostProcessing.add(BlockBehaviour.FRICTION);
        newer.postProcessed(BlockBehaviour.FRICTION, 1, "shapeSource: corrected");
        importer.captures().add(capture(Provenance.Source.CLOUDBURST_DATA, V2, newer));
        importer.captures().add(capture(Provenance.Source.CLOUDBURST_DATA, V1, friction(0.7f, 0.8f)));
        BehaviourStore store = importer.build();
        assertEquals("shapeSource: corrected", store.block(BlockBehaviour.FRICTION, 1, V2).postProcessing());
        String carried = store.block(BlockBehaviour.FRICTION, 1, V1).postProcessing();
        assertTrue(carried.startsWith("shapeSource: corrected (carried from 2.0"), carried);
        assertNull(store.block(BlockBehaviour.FRICTION, 2, V1).postProcessing());
    }

    @Test
    void theOutputIsReproducibleAndSaysWhereItCameFrom() throws IOException {
        byte[] first = bytes(disputeScenario().build());
        assertTrue(java.util.Arrays.equals(first, bytes(disputeScenario().build())), "the same inputs give the same bytes");
        BehaviourStore read = BehaviourStore.read(new java.io.ByteArrayInputStream(first));
        NbtMap metadata = read.metadata();
        assertEquals(BehaviourImport.COMMAND, metadata.getString("command"));
        assertTrue(metadata.getString("do_not_edit").contains("Generated"));
        assertEquals(4, metadata.getList("sources", org.cloudburstmc.nbt.NbtType.COMPOUND).size());
        NbtMap source = metadata.getList("sources", org.cloudburstmc.nbt.NbtType.COMPOUND).stream()
            .filter(m -> m.getString("source").equals("CLOUDBURST_DATA")).findFirst().orElseThrow();
        assertEquals("third-party dump of BDS", source.getString("kind"));
        assertTrue(source.getString("what").contains("CloudburstMC/Data"));
        BehaviourImport importer = disputeScenario();
        String report = new BehaviourReport(bundled(), importer.captures(), importer.build(), importer.log, importer.customDefaults()).render();
        assertTrue(report.startsWith("<!-- Generated by `behaviour import`"), report.substring(0, 60));
        assertTrue(report.contains("## Disputed values"), report);
        assertTrue(report.contains("0.7 (CLOUDBURST_DATA) / 0.5 (ALLAY_DATA@1.0)"), report);
    }

    @Test
    void customDefaultsComeFromTheirDataFileWithEvidence(@TempDir Path directory) throws IOException {
        BehaviourImport importer = new BehaviourImport(bundled());
        importer.customDefaults(directory.resolve("missing.json"));
        assertNull(importer.build().defaultOf(BlockBehaviour.FRICTION), "no file: unknown");

        Path file = directory.resolve("defaults.json");
        Files.writeString(file, """
            {"defaults": [{"key": "block:friction", "value": 0.6, "source": "NATIVE", "reference": "LeviLamina capture"},
                          {"key": "item:max_stack_size", "value": 64, "source": "SCRIPT_API", "reference": "probe"}]}
            """);
        importer.customDefaults(file);
        BehaviourStore store = importer.build();
        assertEquals(0.6f, store.defaultOf(BlockBehaviour.FRICTION).value());
        assertEquals(Provenance.Source.NATIVE, store.defaultOf(BlockBehaviour.FRICTION).source());
        assertEquals(64, store.defaultOf(ItemBehaviour.MAX_STACK_SIZE).value());
        assertNull(store.defaultOf(BlockBehaviour.DESTROY_TIME));

        Files.writeString(file, "{\"defaults\": [{\"key\": \"block:friction\", \"value\": 0.6, \"source\": \"CLOUDBURST_DATA\", \"reference\": \"x\"}]}");
        BehaviourImport third = new BehaviourImport(bundled());
        third.customDefaults(file);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, third::build);
    }

    private static byte[] bytes(BehaviourStore store) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        store.write(out);
        return out.toByteArray();
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
