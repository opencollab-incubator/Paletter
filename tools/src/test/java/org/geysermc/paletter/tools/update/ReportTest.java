package org.geysermc.paletter.tools.update;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportTest {

    @Test
    void passingReportCollapsesEverything() {
        Report report = new Report("Paletter data verification: BDS 1.0.0.1");
        report.header.put("Date", "2026-01-01");
        report.check(Report.Status.PASS, "Script API vs baseline", "identical");
        report.check(Report.Status.INFO, "Fallbacks", "see the list");
        report.evidence("Content", List.of("- 26.60: 1 blocks"));
        String markdown = report.render();
        List<String> visible = markdown.lines().takeWhile(line -> !line.startsWith("<details>")).filter(line -> !line.isBlank()).toList();
        assertEquals(List.of("# Paletter data verification: BDS 1.0.0.1", "OK: no changes", "## Details"), visible);
        assertTrue(markdown.contains("<details><summary>All checks (1 passed)</summary>"), markdown);
        assertTrue(markdown.contains("- **Date**: 2026-01-01"), markdown);
        assertFalse(markdown.contains("Needs attention"), markdown);
    }

    @Test
    void failuresAndDecisionsNeedAttention() {
        Report report = new Report("t");
        report.check(Report.Status.FAIL, "Voxel shapes", "boom");
        report.check(Report.Status.DIFF, "Script API vs baseline", "3 differences");
        report.check(Report.Status.DIFF, "Creative order", "2 differences");
        report.action("update CreativeOrdering.BDS");
        report.check(Report.Status.DIFF, "Spec capture values", "1 leaf differs");
        report.todo("decide");
        assertEquals("Failed: 1 failure, 3 changes, 2 decisions needed", report.verdict());
        String markdown = report.render();
        assertTrue(markdown.contains("## Needs attention\n\n- Failed: **Voxel shapes**: boom\n"), markdown);
        // A difference and what to do about it are one entry
        assertTrue(markdown.contains("- Changed: **Creative order**: 2 differences; to do: update CreativeOrdering.BDS\n"), markdown);
        assertEquals(1, markdown.split("CreativeOrdering", -1).length - 1, markdown);
        assertTrue(markdown.contains("- To do: decide\n"), markdown);
    }
}
