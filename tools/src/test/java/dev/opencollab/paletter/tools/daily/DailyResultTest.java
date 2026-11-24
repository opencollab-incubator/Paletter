package dev.opencollab.paletter.tools.daily;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the daily capture job hands to its publish job. */
class DailyResultTest {
    private static final String BUILD = "1.26.66.1";
    private static final String REPORT = """
        # Paletter data update: BDS 1.26.66.1

        - **Date**: 2026-11-24
        - **Work directory**: `D:/a/_temp/bds-cache/runs/win-1.26.66.1` (logs, captures)
        - **Server**: `D:/a/_temp/bds-cache/servers/win-1.26.66.1` (removed after the run)
        - **Mode**: update

        %s
        """;

    private static Path report(Path dir, String verdict) throws IOException {
        return Files.writeString(dir.resolve("report.md"), REPORT.formatted(verdict));
    }

    private static String diff(String path) {
        return "diff --git a/" + path + " b/" + path + "\nindex 1..2 100644\n--- a/" + path + "\n+++ b/" + path + "\n@@ -1 +1 @@\n-a\n+b\n";
    }

    private static Path patch(Path dir, String... paths) throws IOException {
        StringBuilder text = new StringBuilder();
        for (String path : paths) {
            text.append(diff(path));
        }
        return Files.writeString(dir.resolve("data.patch"), text);
    }

    @Test
    void aCleanRunDiffersNotAndPublishesNothing(@TempDir Path dir) throws IOException {
        Path patch = patch(dir, "data/bds/" + BUILD + "/update-report.md");
        DailyResult.Result result = DailyResult.of(BUILD, 0, 0, false, report(dir, "OK: no changes"), patch, "123");
        assertEquals(new DailyResult.Result(BUILD, 0, false, result.hash(), false), result);
        assertFalse(Files.exists(patch), "a report alone is no data");
    }

    @Test
    void decisionsWithAPatchArePublished(@TempDir Path dir) throws IOException {
        Path patch = patch(dir, "data/bds/" + BUILD + "/spec/capture.json", "data/model/fixtures.json");
        DailyResult.Result result = DailyResult.of(BUILD, 2, 0, false, report(dir, "Changed: 3 decisions"), patch, "123");
        assertEquals(2, result.update());
        assertTrue(result.differs());
        assertTrue(result.addon(), "fixtures.json is an input of the spec add-on");
        assertTrue(Files.exists(patch));
        assertEquals("version=" + BUILD + "\nupdate=2\ndiffers=true\nhash=" + result.hash() + "\naddon=true\n", result.text());
    }

    @Test
    void aChangeOutsideTheAddonInputsIsNoAddonChange(@TempDir Path dir) throws IOException {
        Path patch = patch(dir, "data/model/mappings.json", "paletter/src/generated/X.java");
        assertFalse(DailyResult.of(BUILD, 0, 0, false, report(dir, "Changed: mappings"), patch, "123").addon());
    }

    @Test
    void aFailedCodeGenerationIsExitThreeAndPublishesNothing(@TempDir Path dir) throws IOException {
        Path patch = patch(dir, "data/bds/" + BUILD + "/spec/capture.json");
        DailyResult.Result result = DailyResult.of(BUILD, 0, 1, false, report(dir, "Changed: x"), patch, "123");
        assertEquals(DailyResult.EXIT_CODEGEN, result.update());
        assertTrue(result.differs());
        assertFalse(Files.exists(patch));
    }

    @Test
    void aFailedUpdatePublishesNothing(@TempDir Path dir) throws IOException {
        Path patch = patch(dir, "data/bds/" + BUILD + "/spec/capture.json");
        DailyResult.Result result = DailyResult.of(BUILD, 1, 0, false, report(dir, "Failed: 1 check"), patch, "123");
        assertEquals(1, result.update());
        assertTrue(result.differs());
        assertFalse(Files.exists(patch));
    }

    @Test
    void aVerificationDiffersOnlyWhenItFailed(@TempDir Path dir) throws IOException {
        Path patch = patch(dir, "data/model/mappings.json");
        assertFalse(DailyResult.of(BUILD, 0, 7, true, report(dir, "OK: no changes"), patch, "123").differs(), "no code generation after a verification");
        assertFalse(Files.exists(patch));
        assertTrue(DailyResult.of(BUILD, 1, 0, true, report(dir, "Failed: x"), patch, "123").differs());
    }

    @Test
    void withoutAReportTheHashIsTheRunId(@TempDir Path dir) throws IOException {
        DailyResult.Result result = DailyResult.of(BUILD, 1, 0, false, dir.resolve("report.md"), dir.resolve("data.patch"), "987654321");
        assertEquals("987654321", result.hash());
        assertTrue(result.differs());
        assertThrows(IllegalArgumentException.class, () -> DailyResult.of(BUILD, 1, 0, false, dir.resolve("report.md"), dir.resolve("data.patch"), "abc"));
        assertThrows(IllegalArgumentException.class, () -> DailyResult.of("1.26", 1, 0, false, dir.resolve("report.md"), dir.resolve("data.patch"), "1"));
    }

    @Test
    void theHashIgnoresTheDateAndThePaths() {
        String one = REPORT.formatted("Failed: 1 check");
        String other = one.replace("2026-11-24", "2026-11-25").replace("D:/a/_temp", "C:/temp");
        assertEquals(DailyResult.hash(one), DailyResult.hash(other));
        assertFalse(DailyResult.hash(one).equals(DailyResult.hash(REPORT.formatted("Failed: 2 checks"))));
        assertTrue(DailyResult.hash(one).matches("[0-9a-f]{16}"));
        // As `grep -v ... | sha256sum | cut -c1-16` of the workflow before it: sha256 of "a\n" starts with 87428fc522803d31
        assertEquals("87428fc522803d31", DailyResult.hash("a\n- **Date**: today\n"));
    }

    @Test
    void thePathsOfAPatch() {
        assertEquals(List.of("data/a.json", "paletter/api/paletter.api"), DailyResult.paths(diff("data/a.json") + diff("paletter/api/paletter.api")));
    }
}
