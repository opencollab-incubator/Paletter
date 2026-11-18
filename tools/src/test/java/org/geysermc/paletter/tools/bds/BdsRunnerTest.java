package org.geysermc.paletter.tools.bds;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BdsRunnerTest {
    private static final Path LOG = Path.of("probe.log");

    @Test
    void doneIsSuccess() {
        assertDoesNotThrow(() -> BdsRunner.checkResult("[2026-11-18 06:29:53:349 WARN] [Scripting] PROBE-DONE", "PROBE", 3600, LOG));
    }

    @Test
    void aFailedLineEndsTheRunWithThatLineNotWithATimeout() {
        IOException failed = assertThrows(IOException.class, () -> BdsRunner.checkResult(
            "[2026-11-18 06:29:53:349 WARN] [Scripting] PROBE-FAILED in light cells: Error: Block type probe:plain not found.\n", "PROBE", 3600, LOG));
        assertTrue(failed.getMessage().contains("PROBE-FAILED in light cells: Error: Block type probe:plain not found."), failed.getMessage());
        assertTrue(!failed.getMessage().contains("3600"), failed.getMessage());
    }

    @Test
    void noLineIsAnExitOrATimeout() {
        IOException none = assertThrows(IOException.class, () -> BdsRunner.checkResult(null, "PROBE", 3600, LOG));
        assertTrue(none.getMessage().contains("3600s"), none.getMessage());
    }
}
