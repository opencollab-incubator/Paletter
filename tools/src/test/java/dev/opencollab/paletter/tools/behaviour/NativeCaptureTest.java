package dev.opencollab.paletter.tools.behaviour;

import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.behaviour.BlockBehaviour;
import dev.opencollab.paletter.behaviour.ItemBehaviour;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.behaviour.Provenance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reader and its merge into the importer, on the synthetic fixtures in {@code resources/native} (invented values, not BDS data). */
class NativeCaptureTest {
    private static final DataVersion V1 = new DataVersion("1.0", 10);
    private static final String BUILD = "1.0.0.1";
    private static final String CAPTURED = "synthetic-captured.json";
    private static final String NOT_CAPTURED = "synthetic-not-captured.json";

    private static NbtMap block(String name) {
        return NbtMap.builder().putString("name", name).putCompound("states", NbtMap.EMPTY).build();
    }

    /** Hash 1 is a ladder, 2 and 3 are stone. */
    private static BehaviourImport.Bundled bundled() {
        Map<Integer, NbtMap> states = new LinkedHashMap<>();
        states.put(1, block("minecraft:ladder"));
        states.put(2, block("minecraft:stone"));
        states.put(3, block("minecraft:stone"));
        return new BehaviourImport.Bundled(V1, states, Set.of("minecraft:sword", "minecraft:stick"));
    }

    private static String fixtureText(String name) throws IOException {
        try (InputStream in = NativeCaptureTest.class.getResourceAsStream("/native/" + name)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static Path write(Path dir, String text) throws IOException {
        Files.createDirectories(dir);
        return Files.writeString(dir.resolve(NativeCapture.FILE), text);
    }

    /** Writes a fixture to {@code <bds>/<build>/native.json}, with the experiments.json that names the version. */
    private static void install(Path bds, String fixture) throws IOException {
        write(bds.resolve(BUILD), fixtureText(fixture));
        Files.writeString(bds.resolve(BUILD).resolve("experiments.json"), "{\"protocol\": 10}");
    }

    private static NativeCapture read(Path dir, String text) throws IOException {
        return NativeCapture.read(write(dir, text), BUILD);
    }

    private static Map<Object, Object> merged(List<? extends Values<?>> groups) {
        Map<Object, Object> merged = new HashMap<>();
        groups.forEach(group -> group.byKey.forEach(merged::put));
        return merged;
    }

    @Test
    void readsTheRecordOfTheCapture(@TempDir Path dir) throws IOException {
        NativeCapture capture = read(dir, fixtureText(CAPTURED));
        assertTrue(capture.captured());
        assertEquals(BUILD, capture.build);
        assertEquals("campaign-synthetic", capture.campaign);
        assertEquals("synthetic-dumper 0.0.0", capture.dumper);
    }

    @Test
    void blockTypeMembersHoldForEveryStateAndAStatesOwnMembersWin(@TempDir Path dir) throws IOException {
        List<String> log = new ArrayList<>();
        Map<Object, Object> values = merged(read(dir, fixtureText(CAPTURED)).blocks(bundled().states(), log));
        assertEquals(Map.of(1, 0.5f, 2, 0.0f, 3, 0.0f), values.get(BlockBehaviour.TRANSLUCENCY));
        assertEquals(Map.of(2, 0.25f), values.get(BlockBehaviour.FRICTION), "only the state the file has");
        assertEquals(1, log.size(), log.toString());
        assertTrue(log.getFirst().contains("block types are not in the palette"), log.getFirst());
    }

    /** State hashes are signed ints in the palette and unsigned decimals in the file: 3000000000 is -1294967296. */
    @Test
    void aStateHashAboveTwoToThe31IsReadAsUnsigned(@TempDir Path dir) throws IOException {
        Map<Integer, NbtMap> states = Map.of(-1294967296, block("minecraft:stone"));
        List<String> log = new ArrayList<>();
        NativeCapture capture = read(dir, fixtureText(CAPTURED).replace("\"2\": {", "\"3000000000\": {"));
        Map<Object, Object> values = merged(capture.blocks(states, log));
        assertEquals(Map.of(-1294967296, 0.25f), values.get(BlockBehaviour.FRICTION));
        assertEquals(1, log.size(), "only the ladder is missing from this palette's block types, no state is unknown: " + log);
        assertFalse(log.getFirst().contains("block states"), log.getFirst());
    }

    @Test
    void aFileWithoutTheGeneratedMarkerIsRefused(@TempDir Path dir) throws IOException {
        assertRefused(dir, fixtureText(CAPTURED).replace("\"_generated\"", "\"_other\""), "_generated");
    }

    @Test
    void aFlagIsTrueWhenItsListHasIt(@TempDir Path dir) throws IOException {
        Map<Object, Object> values = merged(read(dir, fixtureText(CAPTURED)).blocks(bundled().states(), new ArrayList<>()));
        assertEquals(Map.of(1, true, 2, false, 3, false), values.get(BlockBehaviour.CLIMBABLE));
    }

    @Test
    void readsItemMembersAndLeavesTheRestUnknown(@TempDir Path dir) throws IOException {
        Map<Object, Object> values = merged(read(dir, fixtureText(CAPTURED)).items(bundled().items(), new ArrayList<>()));
        assertEquals(Map.of("minecraft:sword", 8), values.get(ItemBehaviour.ATTACK_DAMAGE));
        assertEquals(Map.of("minecraft:sword", 10), values.get(ItemBehaviour.ENCHANT_VALUE));
        assertNull(values.get(ItemBehaviour.ARMOR_VALUE), "no member in the file: no value, nothing guessed");
    }

    @Test
    void aBuildThatWasNotCapturedSaysWhy(@TempDir Path dir) throws IOException {
        NativeCapture capture = read(dir, fixtureText(NOT_CAPTURED));
        assertFalse(capture.captured());
        assertEquals("the dumper does not support this build", capture.notCapturedReason);
        assertEquals(List.of(), capture.blocks(bundled().states(), new ArrayList<>()));
    }

    @Test
    void theImporterMergesTheCaptureAsTheNativeSource(@TempDir Path bds) throws IOException {
        install(bds, CAPTURED);
        BehaviourImport importer = new BehaviourImport(List.of(bundled()));
        importer.nativeCaptures(bds);
        assertFalse(importer.captures().isEmpty());
        importer.captures().forEach(capture -> {
            assertEquals(Provenance.Source.NATIVE, capture.source());
            assertEquals(V1, capture.version());
            assertEquals("BDS " + BUILD, capture.match());
            assertTrue(capture.reference().contains("synthetic-dumper 0.0.0, campaign-synthetic"), capture.reference());
        });
        var resolved = importer.build().block(BlockBehaviour.TRANSLUCENCY, 1, V1);
        assertEquals(0.5f, resolved.value());
        assertEquals(Provenance.Source.NATIVE, resolved.capture().source());
    }

    @Test
    void theImporterLogsABuildThatWasNotCapturedAndAddsNothing(@TempDir Path bds) throws IOException {
        install(bds, NOT_CAPTURED);
        BehaviourImport importer = new BehaviourImport(List.of(bundled()));
        importer.nativeCaptures(bds);
        assertEquals(List.of(), importer.captures());
        assertEquals(List.of("NATIVE " + BUILD + ": not captured (the dumper does not support this build)"), importer.log);
    }

    @Test
    void withoutAFileNothingChanges(@TempDir Path bds) throws IOException {
        BehaviourImport importer = new BehaviourImport(List.of(bundled()));
        importer.nativeCaptures(bds);
        assertEquals(List.of(), importer.captures());
        assertEquals(List.of(), importer.log);
    }

    @Test
    void refusesFilesThatBreakTheSchema(@TempDir Path dir) throws IOException {
        String ok = fixtureText(CAPTURED);
        assertRefused(dir, ok.replace("\"schema\": 1", "\"schema\": 2"), "schema");
        assertRefused(dir, ok.replace("\"bdsVersion\": \"1.0.0.1\"", "\"bdsVersion\": \"9.9.9.9\""), "build directory");
        assertRefused(dir, ok.replace("\"campaign\": \"campaign-synthetic\",", ""), "missing campaign");
        assertRefused(dir, ok.replace("\"dumper\": {\"name\": \"synthetic-dumper\", \"version\": \"0.0.0\"},", ""), "missing dumper");
        assertRefused(dir, ok.replace("\"status\": \"captured\"", "\"status\": \"maybe\""), "status");
        assertRefused(dir, ok.replace("\"items\": {", "\"unrelated\": {"), "missing section items");
        assertRefused(dir, fixtureText(NOT_CAPTURED).replace("\"reason\":", "\"other\":"), "missing reason");
    }

    @Test
    void aMemberOfTheWrongTypeFails(@TempDir Path dir) throws IOException {
        NativeCapture capture = read(dir, fixtureText(CAPTURED).replace("\"Item.getAttackDamage\": 8", "\"Item.getAttackDamage\": 8.5"));
        assertThrows(IllegalStateException.class, () -> capture.items(bundled().items(), new ArrayList<>()));
    }

    private static void assertRefused(Path dir, String text, String message) throws IOException {
        Path file = write(dir, text);
        IOException e = assertThrows(IOException.class, () -> NativeCapture.read(file, BUILD), message);
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }
}
