package dev.opencollab.paletter.tools.bds;

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
        assertDoesNotThrow(() -> BdsRunner.checkResult("[2026-11-18 06:29:53:349 WARN] [Scripting] PROBE-DONE", "PROBE", 3600, false, null, LOG));
    }

    @Test
    void aFailedLineEndsTheRunWithThatLineNotWithATimeout() {
        IOException failed = assertThrows(IOException.class, () -> BdsRunner.checkResult(
            "[2026-11-18 06:29:53:349 WARN] [Scripting] PROBE-FAILED in light cells: Error: Block type probe:plain not found.\n", "PROBE", 3600,
            false, null, LOG));
        assertTrue(failed.getMessage().contains("PROBE-FAILED in light cells: Error: Block type probe:plain not found."), failed.getMessage());
        assertTrue(!failed.getMessage().contains("3600"), failed.getMessage());
    }

    /** The behaviour dump of BDS 1.26.70.24 (preview): the last progress line names where it hung. */
    @Test
    void silenceNamesTheLastProgressLine() {
        IOException none = assertThrows(IOException.class, () -> BdsRunner.checkResult(null, "BEHAVIOUR", 300, false,
            "[2026-11-18 14:44:38:927 WARN] [Scripting] BEHAVIOUR-PROGRESS world sample 1800/2144", LOG));
        assertTrue(none.getMessage().startsWith("BDS was silent: no BEHAVIOUR-PROGRESS line for 300s, after BEHAVIOUR-PROGRESS world sample 1800/2144;"),
            none.getMessage());
    }

    @Test
    void silenceWithoutAnyProgressNamesTheMissingDone() {
        IOException none = assertThrows(IOException.class, () -> BdsRunner.checkResult(null, "PROBE", 3600, false, null, LOG));
        assertTrue(none.getMessage().startsWith("BDS was silent: no PROBE-DONE within 3600s, before any PROBE-PROGRESS line;"), none.getMessage());
    }

    @Test
    void anExitIsNotASilence() {
        IOException none = assertThrows(IOException.class, () -> BdsRunner.checkResult(null, "PROBE", 3600, true, null, LOG));
        assertTrue(none.getMessage().startsWith("BDS exited without PROBE-DONE, before any PROBE-PROGRESS line;"), none.getMessage());
    }
}
