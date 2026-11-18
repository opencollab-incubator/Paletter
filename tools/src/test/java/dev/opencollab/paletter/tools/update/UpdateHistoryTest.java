package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.tools.bds.BdsDistribution;
import dev.opencollab.paletter.tools.model.infer.SpecDiff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The history check path of {@link Update} without BDS: what it stages, when the build counts as changed, what it keeps. */
class UpdateHistoryTest {
    private static Update historyCheck(Path root) throws IOException {
        Files.createDirectories(root.resolve("data/bds/1.26.36.1/spec"));
        // A root given with a detour still compares with the paths built from it
        Update update = new Update(root.resolve("data/..").resolve("."), Map.of("channel", "release", "against", "1.26.36.1"));
        update.begin(new BdsDistribution("1.26.31.1", false, "win", "https://example.invalid/bedrock-server-1.26.31.1.zip"));
        return update;
    }

    @Test
    void aHistoryCheckStagesOnlyIntoItsBuildDirectory(@TempDir Path root) throws IOException {
        Update update = historyCheck(root);
        update.context().stage(root.resolve("data/bds/1.26.31.1/experiments.json"), "{}".getBytes(StandardCharsets.UTF_8), "experiment record");
        // Anything else is a bug of the pipeline: it throws instead of dropping the file silently
        for (Path outside : List.of(root.resolve("data/addons/creative/manifest.json"), root.resolve("data/bds/1.26.36.1/experiments.json"),
            root.resolve("data/bds/1.26.31.1/../1.26.36.1/x"), root.resolve("data/bds/1.26.31.1"))) {
            assertThrows(IllegalStateException.class, () -> update.context().stage(outside, new byte[0], "x"), outside.toString());
        }
        assertFalse(Files.exists(root.resolve("data/bds/1.26.31.1")), "nothing is written before the build is kept");
    }

    @Test
    void aHistoryCheckStagesNoVariantNames(@TempDir Path root) throws IOException {
        Update update = historyCheck(root);
        new NetworkDataStage(update.context()).stageVariantNames(Map.of());
        assertTrue(update.report().repoChanges.isEmpty());
    }

    @Test
    void capturesOfAnotherAddOnFailAHistoryCheckButNotACaptureOnlyRun(@TempDir Path root, @TempDir Path out) throws IOException {
        SpecDiff.Pairing other = new SpecDiff.Pairing(10, 2, 3, 0);
        Update check = historyCheck(root);
        assertFalse(new SpecStage(check.context(), null).variantsAlike(other, "1.26.36.1"));
        assertTrue(check.report().failed());
        // A recapture's captures are of the new add-on by design, even when it compares with the committed ones of a release
        Update recapture = new Update(root, Map.of("channel", "release", "against", "1.26.36.1", "capture-only", out.toString()));
        recapture.begin(new BdsDistribution("1.26.31.1", false, "win", "https://example.invalid/bedrock-server-1.26.31.1.zip"));
        assertTrue(new SpecStage(recapture.context(), null).variantsAlike(other, "1.26.36.1"));
        assertFalse(recapture.report().failed());
    }

    @Test
    void anyChangedCheckChangesTheBuildAndBundleChecksAreNotCompared(@TempDir Path root) throws IOException {
        Update update = historyCheck(root);
        update.report().check(Report.Status.PASS, "Script API vs baseline 1.26.36.1", "identical");
        update.report().check(Report.Status.INFO, "Block numbering", "3 states");
        update.report().notCompared.add("Block numbering: 3 states");
        assertFalse(update.changed());
        assertEquals(List.of("Block numbering: 3 states"), update.notCompared());
        update.report().check(Report.Status.DIFF, "Experiments: current toggles", "6 streamed");
        assertTrue(update.changed());
    }

    @Test
    void aKeptBuildGetsWhatWasStagedAndItsSpecCapture(@TempDir Path root, @TempDir Path work) throws IOException {
        Update update = historyCheck(root);
        update.context().stage(root.resolve("data/bds/1.26.31.1/experiments.json"), "{}".getBytes(StandardCharsets.UTF_8), "experiment record");
        Files.createDirectories(work.resolve("spec"));
        Files.writeString(work.resolve("spec/capture.json"), "{\"bdsVersion\": \"1.26.31.1\", \"protocol\": 1001}");
        update.context().keep(work);
        assertEquals("{}", Files.readString(root.resolve("data/bds/1.26.31.1/experiments.json")));
        assertTrue(Files.exists(root.resolve("data/bds/1.26.31.1/spec/capture.json")));
        assertTrue(update.report().repoChanges.contains("add `data/bds/1.26.31.1` (captures of this build)"), update.report().repoChanges.toString());
    }

    @Test
    void stagesRunOnASyntheticRunState(@TempDir Path root, @TempDir Path work) throws IOException {
        Update update = historyCheck(root);
        Path earlier = root.resolve("data/bds/1.26.36.1/creative-probe/creative_items.json.gz");
        Files.createDirectories(earlier.getParent());
        Files.write(earlier, UpdateContext.gzip("{\"items\": [\"a\", \"b\"]}"));
        Runs runs = new Runs();
        runs.creative = Files.createDirectories(work.resolve("creative"));
        Files.writeString(runs.creative.resolve("creative_items.json"), "{\"items\": [\"a\", \"c\"]}");
        new AddonChecksStage(update.context()).creativeOrder(runs);
        new ExperimentReportStage(update.context()).experimentsReport(new BdsDistribution("1.26.31.1", false, "win", "u"), runs, null);
        String report = update.report().render();
        assertTrue(report.contains("Creative order probe inventory"), report);
        assertTrue(report.contains("Experiments"), report);
        assertTrue(update.changed());
        update.context().keep(work);
        assertTrue(Files.exists(root.resolve("data/bds/1.26.31.1/creative-probe/creative_items.json.gz")));
    }

    @Test
    void theBuildComparedWithMustBeABuildWithCaptures(@TempDir Path root) {
        assertThrows(IllegalArgumentException.class, () -> new Update(root, Map.of("against", "1.26.36.1\\..\\x")));
        Update update = new Update(root, Map.of("against", "1.26.36.1"));
        assertThrows(IllegalArgumentException.class, () -> update.begin(new BdsDistribution("1.26.31.1", false, "win", "u")));
    }

    @Test
    void aFailedSchemaStepIsASkippedRowAndNeverAFailure(@TempDir Path root, @TempDir Path cache, @TempDir Path docs) throws IOException {
        BdsDistribution build = new BdsDistribution("1.26.31.1", false, "win", "https://example.invalid/bedrock-server-1.26.31.1.zip");
        Update update = historyCheck(root);
        // BDS's documentation generator wrote nothing
        new ScriptApiStage(update.context()).schema(cache, build, null);
        // It wrote something the extract cannot read (no component schemas)
        Files.createDirectories(docs.resolve("json_schemas/server"));
        new ScriptApiStage(update.context()).schema(cache, build, docs);
        String report = update.report().render();
        assertTrue(report.contains("BDS's documentation generator wrote no docs"), report);
        assertTrue(report.contains("not extracted, the previous schema stays: java.io.IOException: No component schemas"), report);
        assertFalse(update.report().failed());
        assertTrue(update.report().repoChanges.isEmpty(), "no schema.json from a failed step");
    }

    @Test
    void aCaptureOnlyRunWritesItsCapturesIntoItsOwnDirectoryAndNothingElse(@TempDir Path root, @TempDir Path out, @TempDir Path work) throws IOException {
        // The build is committed already (a recapture): its directory is neither deleted nor an obstacle
        Files.createDirectories(root.resolve("data/bds/1.26.36.1/spec"));
        Files.writeString(root.resolve("data/bds/1.26.36.1/experiments.json"), "old");
        Update update = new Update(root, Map.of("channel", "release", "capture-only", out.toString()));
        update.begin(new BdsDistribution("1.26.36.1", false, "win", "https://example.invalid/bedrock-server-1.26.36.1.zip"));
        update.context().stage(root.resolve("data/bds/1.26.36.1/experiments.json"), "new".getBytes(StandardCharsets.UTF_8), "experiment record");
        update.context().stage(root.resolve("data/bds/1.26.36.1/creative-probe/creative_items.json.gz"), "x".getBytes(StandardCharsets.UTF_8), "creative order probe inventory");
        // Derived data is the gathering job's: dropped, never written into the checkout or the directory
        update.context().stage(root.resolve("paletter/src/main/resources/paletter/26.30.nbt"), new byte[1], "bundled data");
        update.context().stage(root.resolve("data/model/variant_names.txt"), new byte[1], "item variant names");
        Files.createDirectories(work.resolve("spec"));
        Files.writeString(work.resolve("spec/capture.json"), "{\"protocol\": 1001}");
        update.context().keep(work);
        assertEquals("new", Files.readString(out.resolve("experiments.json")));
        assertEquals("x", Files.readString(out.resolve("creative-probe/creative_items.json.gz")));
        assertTrue(Files.exists(out.resolve("spec/capture.json")));
        assertFalse(Files.exists(out.resolve("paletter")));
        assertFalse(Files.exists(out.resolve("data")));
        assertEquals("old", Files.readString(root.resolve("data/bds/1.26.36.1/experiments.json")), "the checkout is untouched");
        assertFalse(Files.exists(root.resolve("paletter")));
        assertTrue(update.report().repoChanges.isEmpty(), update.report().repoChanges.toString());
    }

    @Test
    void aCaptureOnlyRunNeedsADirectoryOutsideDataBds(@TempDir Path root) {
        assertThrows(IllegalArgumentException.class, () -> new Update(root, Map.of("channel", "release", "capture-only", root.resolve("data/bds/x").toString())));
        assertThrows(IllegalArgumentException.class, () -> new Update(root, Map.of("channel", "release", "capture-only", root.toString())));
        assertThrows(IllegalArgumentException.class, () -> new Update(root, Map.of("channel", "release", "capture-only", "out", "verify-only", "true")));
    }
}
