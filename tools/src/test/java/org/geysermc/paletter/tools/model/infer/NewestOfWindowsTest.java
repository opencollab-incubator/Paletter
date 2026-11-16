package org.geysermc.paletter.tools.model.infer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MappingInference#newestOfWindows}: the newest kept build defines its window, and only stands for builds with the same data. */
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
    void theNewestBuildStandsForOlderBuildsWithTheSameData(@TempDir Path root) throws IOException {
        build(root, "1.26.40.8", 1, false);
        build(root, "1.26.45.1", 1, false);
        build(root, "1.26.42.1", 1, false);
        Map<Integer, MappingInference.Window> windows = MappingInference.newestOfWindows(captures, protocol -> 2168, directories);
        assertEquals(1, windows.size());
        assertEquals("1.26.45.1", windows.get(2168).newest().bdsVersion());
        assertEquals(List.of("1.26.40.8", "1.26.42.1"), windows.get(2168).replaces());
    }

    @Test
    void aBuildAloneInItsWindowReplacesNothing(@TempDir Path root) throws IOException {
        build(root, "1.26.45.1", 1, false);
        assertEquals(List.of(), MappingInference.newestOfWindows(captures, protocol -> 2169, directories).get(2169).replaces());
    }

    @Test
    void anOlderBuildWithOtherValuesFails(@TempDir Path root) throws IOException {
        build(root, "1.26.40.8", 1, false);
        build(root, "1.26.45.1", -1, false);
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> MappingInference.newestOfWindows(captures, protocol -> 2168, directories));
        assertTrue(e.getMessage().contains("1.26.40.8 vs 1.26.45.1 (window 2168): 2 leaves with other values"), e.getMessage());
    }

    @Test
    void anOlderBuildThatAcceptsOtherVariantsFails(@TempDir Path root) throws IOException {
        build(root, "1.26.40.8", 1, false);
        build(root, "1.26.45.1", 1, true);
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> MappingInference.newestOfWindows(captures, protocol -> 2168, directories));
        assertTrue(e.getMessage().contains("1 variants accepted or logged differently"), e.getMessage());
    }
}
