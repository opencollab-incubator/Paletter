package org.geysermc.paletter.tools.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Candidate extraction on synthetic binaries shaped like the BDS 1.26.63.1 builds: Linux (every id a NUL terminated
 * string), Windows (ids merged into the tail of longer strings or built from {@code mov r64, imm64}).
 */
class ExperimentDiscoveryTest {

    /** The current toggles of BDS 1.26.63.1 and the UI keys of its binary. */
    private static final List<String> CURRENT = List.of("experimental_creator_cameras", "gametest", "poi",
        "upcoming_creator_features", "villager_trades_rebalance", "voxel_shapes", "y_2026_drop_4");
    private static final List<String> UI_KEYS = List.of("createWorldScreen.experimentalCreatorCameraFeatures",
        "createWorldScreen.experimentalCreatorFeatures", "createWorldScreen.experimentalGameTest", "createWorldScreen.experimentalPoi",
        "createWorldScreen.experimentalVillagerTradesRebalance", "createWorldScreen.experimentalVoxelShapeFeatures",
        "createWorldScreen.experimentalY2026Drop4");

    private static final class Binary {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Binary string(String text) {
            out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
            out.write(0);
            return this;
        }

        Binary bytes(int... values) {
            for (int value : values) {
                out.write(value);
            }
            return this;
        }

        /** {@code mov rax, imm64} (48 b8) or {@code mov r8, imm64} (49 b8) with eight ASCII bytes. */
        Binary immediate(int rex, String eight) {
            assertEquals(8, eight.length());
            bytes(rex, 0xb8);
            out.writeBytes(eight.getBytes(StandardCharsets.US_ASCII));
            return this;
        }

        byte[] build() {
            return out.toByteArray();
        }
    }

    @Test
    void linuxStyleStrings() {
        Binary binary = new Binary().bytes(0x7f, 'E', 'L', 'F', 0);
        CURRENT.forEach(binary::string);
        UI_KEYS.forEach(binary::string);
        binary.string("createWorldScreen.experimentalPoiDescription").string("createWorldScreen.experimentalgameplay");
        ExperimentDiscovery.Candidates candidates = ExperimentDiscovery.scan(binary.build());
        assertTrue(candidates.ids().containsAll(CURRENT), candidates.ids().toString());
        assertEquals(new TreeSet<>(UI_KEYS), candidates.uiKeys(), "descriptions and the general experiments key are no toggles");
    }

    @Test
    void windowsStyleTailMergedStringsAndImmediates() {
        byte[] binary = new Binary()
            .string("behavior_packs/experimental_y_2026_drop_4")          // y_2026_drop_4 only as a tail
            .string("getGeneratedStructures").string("upcoming_creator_features")
            .bytes(0x90, 0x90).immediate(0x48, "gametest")              // gametest only as an immediate
            .bytes(0x48, 0x89, 0x45, 0x10)                               // mov [rbp+10], rax
            .immediate(0x48, "y_2025_d").bytes(0x48, 0x89, 0x45, 0x20).immediate(0x49, "5_drop_1")  // overlapping stores
            .bytes(0, 0, 0)
            .string("saved_with_toggled_experiments").string("experiments_ever_used")
            .build();
        Set<String> ids = ExperimentDiscovery.scan(binary).ids();
        assertTrue(ids.contains("y_2026_drop_4"));
        assertTrue(ids.contains("experimental_y_2026_drop_4"));
        assertTrue(ids.contains("upcoming_creator_features"));
        assertTrue(ids.contains("gametest"));
        assertTrue(ids.contains("y_2025_drop_1"), ids.toString());
        assertFalse(ids.contains("saved_with_toggled_experiments"), "bookkeeping keys are no candidates");
        assertFalse(ids.contains("experiments_ever_used"));
        assertFalse(ids.contains("behavior_packs/experimental_y_2026_drop_4"));
    }

    @Test
    void farApartImmediatesAreNotJoined() {
        Binary binary = new Binary().immediate(0x48, "abcdefgh");
        binary.bytes(new int[100]);
        Set<String> ids = ExperimentDiscovery.scan(binary.immediate(0x48, "hijklmno").build()).ids();
        assertTrue(ids.containsAll(List.of("abcdefgh", "hijklmno")));
        assertFalse(ids.contains("abcdefghijklmno"));
    }

    @Test
    void nothingToFind() {
        ExperimentDiscovery.Candidates candidates = ExperimentDiscovery.scan(new byte[]{0, 1, 2, 'a', 'b', 0, (byte) 0x48, (byte) 0xb8});
        assertTrue(candidates.ids().isEmpty(), candidates.ids().toString());
        assertTrue(candidates.uiKeys().isEmpty());
    }

    @Test
    void scansFiles(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("bedrock_server");
        Files.write(file, new Binary().string("poi").string("createWorldScreen.experimentalPoi").build());
        ExperimentDiscovery.Candidates candidates = ExperimentDiscovery.scan(file);
        assertTrue(candidates.ids().contains("poi"));
        assertEquals(Set.of("createWorldScreen.experimentalPoi"), candidates.uiKeys());

        Files.write(directory.resolve("empty"), new byte[0]);
        assertThrows(IOException.class, () -> ExperimentDiscovery.scan(directory.resolve("empty")));
        assertThrows(IOException.class, () -> ExperimentDiscovery.scan(directory.resolve("missing")));
    }

    @Test
    void suffixesAndOverlaps() {
        Set<String> ids = new TreeSet<>();
        ExperimentDiscovery.suffixes("experimental_creator_cameras", ids);
        assertEquals(Set.of("experimental_creator_cameras", "creator_cameras", "cameras"), ids);
        ids.clear();
        ExperimentDiscovery.suffixes("_2026_drop_4", ids);
        assertEquals(Set.of("drop_4"), ids, "ids start with a letter and have at least three characters");
        assertTrue(ExperimentDiscovery.overlaps("y_2026_d", "6_drop_4").contains("y_2026_drop_4"));
        assertTrue(ExperimentDiscovery.overlaps("scriptin", "g_______").contains("scripting_______"));
    }

    @Test
    void packCandidates(@TempDir Path server) throws IOException {
        Files.createDirectories(server.resolve("behavior_packs/experimental_poi"));
        Files.createDirectories(server.resolve("behavior_packs/vanilla_1.26.60"));
        Files.createDirectories(server.resolve("resource_packs/experimental_y_2026_drop_4"));
        assertEquals(Set.of("experimental_poi", "poi", "experimental_y_2026_drop_4", "y_2026_drop_4"),
            ExperimentDiscovery.packCandidates(server));
        assertEquals(Set.of(), ExperimentDiscovery.packCandidates(server.resolve("nothing")));
    }

    @Test
    void activeCodes() {
        assertEquals(List.of("ucft", "gtst", "26d4"), ExperimentDiscovery.activeCodes(List.of(
            "[2026-11-07 21:08:33:998 INFO] Level Name: exp_a",
            "[2026-11-07 21:08:59:658 INFO] Experiment(s) active: ucft, gtst, 26d4")));
        assertEquals(List.of(), ExperimentDiscovery.activeCodes(List.of("[x INFO] Experiment(s) active: ")));
        assertNull(ExperimentDiscovery.activeCodes(List.of("[x INFO] Server started.")));
    }

    @Test
    void displayNames(@TempDir Path server) throws IOException {
        Path lang = Files.createDirectories(server.resolve("resource_packs/vanilla/texts")).resolve("en_US.lang");
        Files.writeString(lang, """
            ## comment
            createWorldScreen.experimentalCreatorFeatures=Upcoming Creator Features\t#
            createWorldScreen.experimentalCreatorFeaturesDescription=Includes actor properties
            createWorldScreen.experimentalNetherSpawningBalance=Nether Spawning Balance
            """);
        assertEquals(Map.of("createWorldScreen.experimentalCreatorFeatures", "Upcoming Creator Features"),
            ExperimentDiscovery.displayNames(server, Set.of("createWorldScreen.experimentalCreatorFeatures", "createWorldScreen.experimentalPoi")));
        assertEquals(Map.of(), ExperimentDiscovery.displayNames(server.resolve("none"), UI_KEYS));
    }

    @Test
    void uiKeysMatchTheTogglesOf_1_26_63() {
        Map<String, String> matched = ExperimentDiscovery.matchUiKeys(CURRENT, UI_KEYS);
        assertEquals("createWorldScreen.experimentalCreatorCameraFeatures", matched.get("experimental_creator_cameras"));
        assertEquals("createWorldScreen.experimentalCreatorFeatures", matched.get("upcoming_creator_features"));
        assertEquals("createWorldScreen.experimentalGameTest", matched.get("gametest"));
        assertEquals("createWorldScreen.experimentalPoi", matched.get("poi"));
        assertEquals("createWorldScreen.experimentalVillagerTradesRebalance", matched.get("villager_trades_rebalance"));
        assertEquals("createWorldScreen.experimentalVoxelShapeFeatures", matched.get("voxel_shapes"));
        assertEquals("createWorldScreen.experimentalY2026Drop4", matched.get("y_2026_drop_4"));
        assertEquals(Map.of(), ExperimentDiscovery.matchUiKeys(List.of("sniffer"), UI_KEYS));
    }

    @Test
    void docsNames() {
        assertEquals(ExperimentDiscovery.normalizeName("Upcoming Creator Features"), ExperimentDiscovery.normalizeName("upcoming_creator_features"));
    }
}
