package org.geysermc.paletter.tools.behaviour;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.behaviour.BlockBehaviour;
import org.geysermc.paletter.behaviour.BlockShape;
import org.geysermc.paletter.behaviour.ItemBehaviour;
import org.geysermc.paletter.internal.behaviour.Provenance;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals("2.0", store.block(BlockBehaviour.FRICTION, 1, V3).capture().version().name());
        assertNull(store.block(BlockBehaviour.FRICTION, 101, V1));
        assertEquals(7, store.item(ItemBehaviour.ATTACK_DAMAGE, "minecraft:sword", V3).value());

        String report = new BehaviourReport(bundled(), importer.captures(), store, importer.log, importer.customDefaults()).render();
        assertTrue(report.contains("| 1.0: ALLAY_DATA vs CLOUDBURST_DATA") || report.contains("### 1.0: ALLAY_DATA vs CLOUDBURST_DATA"), report);
        assertTrue(report.contains("b1[] (1): 0.5 / 0.7"), report);
        assertTrue(report.contains("inherited from 2.0 CLOUDBURST_DATA 101"), report);
        assertTrue(report.contains("inherited from 1.0 ALLAY_DATA 1"), report);
        // keys no dump has, and what serves them instead: the component data if a wire rule reads the key
        assertTrue(report.contains("- `block:light_emission`: read at runtime from the component data (WIRE) of component based blocks"), report);
        assertTrue(report.contains("- `item:use_duration`: read at runtime from the component data (WIRE) of component based items"), report);
        assertFalse(report.contains("- `block:friction`:"), report);
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
        assertEquals("1.0", carried.disputes().getFirst().capture().version().name());
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

    private static NbtMap door(String name, String direction, boolean open, boolean hinge, boolean upper) {
        return NbtMap.builder().putString("name", name).putCompound("states", NbtMap.builder()
            .putString("minecraft:cardinal_direction", direction).putByte("open_bit", (byte) (open ? 1 : 0))
            .putByte("door_hinge_bit", (byte) (hinge ? 1 : 0)).putByte("upper_block_bit", (byte) (upper ? 1 : 0)).build()).build();
    }

    private static final String PROBE_CAPTURE = """
        {"bds": {"version": "1.0.0.1", "build": "42"},
         "defaults": [{"key": "block:friction", "value": 0.6, "method": "slid"},
                      {"key": "block:light_dampening", "value": 15, "method": "tunnel"},
                      {"key": "block:collision_shape", "value": [[0, 0, 0, 1, 1, 1]], "method": "pushed"}],
         "doors": {"minecraft:door": [
           {"states": {"minecraft:cardinal_direction": "north", "open_bit": false, "door_hinge_bit": false, "upper_block_bit": false}, "axis": "x", "min": 0.8175, "max": 1.0},
           {"states": {"minecraft:cardinal_direction": "north", "open_bit": true, "door_hinge_bit": true, "upper_block_bit": true}, "axis": "z", "min": 0.0, "max": 0.1825},
           {"states": {"minecraft:cardinal_direction": "west", "open_bit": true, "door_hinge_bit": true, "upper_block_bit": true}, "axis": "z", "min": 0.0, "max": 0.1825}]}}
        """;

    /** V1 has two door states the probe measured, one it did not (hash 3) and a block that is no door (4). */
    private static BehaviourImport.Bundled withDoors() {
        Map<Integer, NbtMap> states = new LinkedHashMap<>();
        states.put(1, door("minecraft:door", "north", false, false, false));
        states.put(2, door("minecraft:door", "north", true, true, true));
        states.put(3, door("minecraft:door", "east", false, false, false));
        states.put(4, NbtMap.builder().putString("name", "minecraft:stone").putCompound("states", NbtMap.EMPTY).build());
        return new BehaviourImport.Bundled(V1, states, Set.of());
    }

    private static Path probeDirectory(Path bds) throws IOException {
        Path build = Files.createDirectories(bds.resolve("1.0.0.1").resolve("behaviour-probe"));
        Files.writeString(bds.resolve("1.0.0.1").resolve("experiments.json"), "{\"protocol\": 10}");
        Files.writeString(build.resolve("capture.json"), PROBE_CAPTURE);
        return build;
    }

    @Test
    void theProbeSettlesTheDoorDisputeForTheStatesItMeasured(@TempDir Path bds) throws IOException {
        probeDirectory(bds);
        BehaviourImport importer = new BehaviourImport(List.of(withDoors()));
        importer.probe(bds);
        assertEquals(1, importer.captures().size());
        BehaviourImport.Capture probe = importer.captures().getFirst();
        assertEquals(Provenance.Source.BDS_PROBE, probe.source());
        assertEquals("BDS 1.0.0.1, Build ID 42", probe.match());
        assertEquals(Set.of(1, 2), probe.blocks().ids, "the states of the capture, found in the palette by name and state values");

        // Data says 0.1875 (corrected), Allay 0.1825 raw: disputed everywhere but where the probe measured
        Values<Integer> data = new Values<>();
        Values<Integer> allay = new Values<>();
        for (int hash = 1; hash <= 4; hash++) {
            data.put(BlockBehaviour.COLLISION_SHAPE, hash, BlockShape.of(0, 0, 0, 0.1875f, 1, 1));
            allay.put(BlockBehaviour.COLLISION_SHAPE, hash, BlockShape.of(0, 0, 0, 0.1825f, 1, 1));
        }
        importer.captures().add(capture(Provenance.Source.CLOUDBURST_DATA, V1, data));
        importer.captures().add(capture(Provenance.Source.ALLAY_DATA, V1, allay));
        BehaviourStore store = importer.build();

        BehaviourStore.Resolved north = store.block(BlockBehaviour.COLLISION_SHAPE, 1, V1);
        assertEquals(BlockShape.of(0.8175f, 0, 0, 1, 1, 1), north.value());
        assertEquals(Provenance.Source.BDS_PROBE, north.capture().source());
        assertTrue(north.disputes().isEmpty(), "BDS settled it");
        assertTrue(north.postProcessing().startsWith("composed:"), north.postProcessing());
        assertEquals(BlockShape.of(0, 0, 0, 1, 1, 0.1825f), store.block(BlockBehaviour.COLLISION_SHAPE, 2, V1).value());

        BehaviourStore.Resolved unmeasured = store.block(BlockBehaviour.COLLISION_SHAPE, 3, V1);
        assertEquals(Provenance.Source.CLOUDBURST_DATA, unmeasured.capture().source());
        assertEquals(1, unmeasured.disputes().size(), "a door state the probe did not measure stays disputed");
        assertEquals(1, store.block(BlockBehaviour.COLLISION_SHAPE, 4, V1).disputes().size());
        assertNull(store.block(BlockBehaviour.OUTLINE_SHAPE, 1, V1), "the probe measured collision only");
    }

    @Test
    void aProbeOfAnotherBuildOnlyCountsForItsOwnVersion(@TempDir Path bds) throws IOException {
        probeDirectory(bds);
        BehaviourImport importer = new BehaviourImport(List.of(withDoors(), version(V2, 4, Set.of())));
        importer.probe(bds);
        assertEquals(List.of(V1), importer.captures().stream().map(BehaviourImport.Capture::version).toList(), "protocol 10 is V1's");
    }

    @Test
    void customDefaultsAreWhatTheNewestProbeCaptureMeasured(@TempDir Path bds) throws IOException {
        BehaviourImport none = new BehaviourImport(bundled());
        none.customDefaults(bds.resolve("missing"));
        assertNull(none.build().defaultOf(BlockBehaviour.FRICTION), "no capture: unknown");

        probeDirectory(bds);
        BehaviourImport importer = new BehaviourImport(bundled());
        importer.customDefaults(bds);
        BehaviourStore store = importer.build();
        assertEquals(0.6f, store.defaultOf(BlockBehaviour.FRICTION).value());
        assertEquals(15, store.defaultOf(BlockBehaviour.LIGHT_DAMPENING).value());
        assertEquals(BlockShape.FULL_CUBE, store.defaultOf(BlockBehaviour.COLLISION_SHAPE).value());
        assertEquals(Provenance.Source.BDS_PROBE, store.defaultOf(BlockBehaviour.FRICTION).source());
        assertEquals("data/bds/1.0.0.1/behaviour-probe/capture.json (BDS 1.0.0.1, Build ID 42)", store.defaultOf(BlockBehaviour.FRICTION).reference());
        assertNull(store.defaultOf(BlockBehaviour.FLAME_ODDS), "not measured: unknown");
        BehaviourStore read = BehaviourStore.read(new java.io.ByteArrayInputStream(bytes(store)));
        assertEquals(BlockShape.FULL_CUBE, read.defaultOf(BlockBehaviour.COLLISION_SHAPE).value(), "shapes survive the file");
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
