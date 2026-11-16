package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MappingInference#windowCaptures}: the build a data version was imported from defines its window; other kept
 * builds with the same data are replaced by it, those with other data are a gap, and captures of different add-ons fail.
 */
class NewestOfWindowsTest {
    private final List<MappingInference.Capture> captures = new ArrayList<>();
    private final Map<String, Path> directories = new HashMap<>();

    /** A synthetic spec capture of {@code build} ({@link SpecDiffTest#capture}); all are of protocol 1. */
    private void build(Path root, String build, double minX, boolean secondRejected) throws IOException {
        Path spec = root.resolve(build).resolve("spec");
        SpecDiffTest.capture(spec, minX, false, secondRejected);
        captures.add(MappingInference.load(spec));
        directories.put(build, spec);
    }

    @Test
    void theImportBuildStandsForKeptBuildsWithTheSameData(@TempDir Path root) throws IOException {
        build(root, "1.26.40.8", 1, false);
        build(root, "1.26.45.1", 1, false);
        build(root, "1.26.42.1", 1, false);
        Map<Integer, MappingInference.Window> windows = MappingInference.windowCaptures(captures, protocol -> 2168, directories,
            Set.of("1.26.40.8", "1.26.42.1"));
        assertEquals("1.26.45.1", windows.get(2168).capture().bdsVersion());
        assertEquals(List.of("1.26.40.8", "1.26.42.1"), windows.get(2168).replaces());
        assertTrue(windows.get(2168).differs().isEmpty());
    }

    @Test
    void aNewerHistoryKeptBuildWithOtherDataIsAGapNotTheWindowsCapture(@TempDir Path root) throws IOException {
        // 1.26.63.1 was imported; 1.26.65.1, newer, sends other values and was kept by a history check
        build(root, "1.26.63.1", 1, false);
        build(root, "1.26.65.1", -1, false);
        MappingInference.Window window = MappingInference.windowCaptures(captures, protocol -> 2225, directories, Set.of("1.26.65.1"))
            .get(2225);
        assertEquals("1.26.63.1", window.capture().bdsVersion());
        assertEquals(List.of("1.26.65.1"), window.differs());
        assertTrue(window.replaces().isEmpty());
    }

    @Test
    void otherAcceptanceIsAGapToo(@TempDir Path root) throws IOException {
        build(root, "1.26.63.1", 1, false);
        build(root, "1.26.65.1", 1, true);
        assertEquals(List.of("1.26.65.1"), MappingInference.windowCaptures(captures, protocol -> 2225, directories, Set.of("1.26.65.1"))
            .get(2225).differs());
    }

    @Test
    void aBuildAloneInItsWindowReplacesNothing(@TempDir Path root) throws IOException {
        build(root, "1.26.45.1", 1, false);
        MappingInference.Window window = MappingInference.windowCaptures(captures, protocol -> 2169, directories, Set.of()).get(2169);
        assertTrue(window.replaces().isEmpty() && window.differs().isEmpty());
    }

    @Test
    void capturesOfDifferentAddonsCannotBeCompared(@TempDir Path root) throws IOException {
        build(root, "1.26.63.1", 1, false);
        // The same variants under other identifiers: nothing in common, so values and acceptance would pass vacuously
        Path spec = root.resolve("1.26.65.1").resolve("spec");
        SpecDiffTest.capture(spec, 1, false, false);
        Path variants = spec.resolve("variants.json");
        JsonArray renamed = JsonParser.parseString(Files.readString(variants)).getAsJsonArray();
        renamed.forEach(variant -> variant.getAsJsonObject().addProperty("identifier", variant.getAsJsonObject().get("identifier").getAsString() + "_x"));
        Files.writeString(variants, renamed.toString());
        captures.add(MappingInference.load(spec));
        directories.put("1.26.65.1", spec);
        assertFalse(SpecDiff.pairing(directories.get("1.26.63.1"), spec).same());
        assertEquals(0, SpecDiff.pairing(directories.get("1.26.63.1"), spec).variants());
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> MappingInference.windowCaptures(captures, protocol -> 2225, directories, Set.of("1.26.65.1")));
        assertTrue(e.getMessage().contains("captured from different add-ons"), e.getMessage());
    }
}
