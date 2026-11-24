package dev.opencollab.paletter.tools.update;

import com.google.gson.JsonParser;
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

/** Which builds a full recapture captures: the kept releases and the newest preview of the checkout, as a matrix. */
class RecapturePlanTest {
    private static void capture(Path root, String build, boolean preview) throws IOException {
        Path spec = root.resolve("data/bds").resolve(build).resolve("spec");
        Files.createDirectories(spec);
        Files.writeString(spec.resolve("capture.json"), "{\"protocol\": 2225" + (preview ? ", \"preview\": true" : "") + "}");
    }

    @Test
    void keptReleasesOldestFirstThenTheNewestPreview(@TempDir Path root) throws IOException {
        capture(root, "1.26.63.1", false);
        capture(root, "1.26.36.1", false);
        capture(root, "1.26.70.9", true);
        capture(root, "1.26.70.24", true);
        // Not kept: identical to a kept build, so its captures are not in data/bds
        Files.writeString(root.resolve("data/bds/builds.json"), """
            {"builds": {
              "1.26.36.1": {"protocol": 1001, "keptBy": "update"},
              "1.26.40.8": {"protocol": 1001, "identicalTo": "1.26.36.1"},
              "1.26.63.1": {"protocol": 2225, "keptBy": "history check"}
            }}
            """);
        List<RecapturePlan.Leg> legs = RecapturePlan.of(root);
        assertEquals(List.of(new RecapturePlan.Leg("1.26.36.1", false), new RecapturePlan.Leg("1.26.63.1", false),
            new RecapturePlan.Leg("1.26.70.24", true)), legs);
    }

    /** A build kept by a history check is a leg like any other; the leg finds out by itself that it is captured as one. */
    @Test
    void aBuildKeptByAHistoryCheckIsCapturedAsOne(@TempDir Path root) throws IOException {
        capture(root, "1.26.36.1", false);
        capture(root, "1.26.38.2", false);
        Files.writeString(root.resolve("data/bds/builds.json"), """
            {"builds": {
              "1.26.36.1": {"protocol": 1001, "keptBy": "update"},
              "1.26.38.2": {"protocol": 1001, "keptBy": "history check"}
            }}
            """);
        assertEquals(List.of(new RecapturePlan.Leg("1.26.36.1", false), new RecapturePlan.Leg("1.26.38.2", false)), RecapturePlan.of(root));
        assertTrue(RecapturePlan.historyCheck(root, "1.26.38.2"));
        assertFalse(RecapturePlan.historyCheck(root, "1.26.36.1"));
        assertFalse(RecapturePlan.historyCheck(root, "1.26.70.24"), "a preview is not in the record");
    }

    /** Only committed captures are planned: an identical build, or a build nobody checked, has no leg (and no request is made). */
    @Test
    void noLegForABuildWithoutCommittedCaptures(@TempDir Path root) throws IOException {
        capture(root, "1.26.36.1", false);
        capture(root, "1.26.63.1", false);
        Files.writeString(root.resolve("data/bds/builds.json"), """
            {"builds": {
              "1.26.36.1": {"protocol": 1001, "keptBy": "update"},
              "1.26.44.2": {"protocol": 1001, "identicalTo": "1.26.36.1"},
              "1.26.63.1": {"protocol": 2225, "keptBy": "update"}
            }}
            """);
        assertEquals(List.of(new RecapturePlan.Leg("1.26.36.1", false), new RecapturePlan.Leg("1.26.63.1", false)), RecapturePlan.of(root),
            "1.26.44.2 is identical to a kept build; 1.26.66.1 (unrecorded, no captures) is not looked for");
    }

    @Test
    void namedBuildsKeepThePlanOrder(@TempDir Path root) throws IOException {
        capture(root, "1.26.36.1", false);
        capture(root, "1.26.63.1", false);
        capture(root, "1.26.70.24", true);
        List<RecapturePlan.Leg> legs = RecapturePlan.of(root);
        assertEquals(List.of(new RecapturePlan.Leg("1.26.36.1", false), new RecapturePlan.Leg("1.26.70.24", true)),
            RecapturePlan.only(legs, List.of("1.26.70.24", "1.26.36.1")));
        assertEquals(legs, RecapturePlan.only(legs, List.of()));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RecapturePlan.only(legs, List.of("1.26.66.1")));
        assertTrue(e.getMessage().contains("1.26.66.1"), e.getMessage());
    }

    /** A frozen build's zip is gone: the plan leaves it out and says so. */
    @Test
    void aFrozenBuildIsSkippedWithAReportLine(@TempDir Path root) throws IOException {
        capture(root, "1.26.36.1", false);
        capture(root, "1.26.63.1", false);
        Files.writeString(root.resolve("data/bds/builds.json"), """
            {"builds": {
              "1.26.36.1": {"protocol": 1001, "keptBy": "update", "frozen": "minecraft.net answers 404 since 2027-01-01"},
              "1.26.63.1": {"protocol": 2225, "keptBy": "update"}
            }}
            """);
        assertEquals(List.of(new RecapturePlan.Leg("1.26.63.1", false)), RecapturePlan.of(root));
        assertEquals(List.of("1.26.36.1 is frozen (minecraft.net answers 404 since 2027-01-01): not recaptured, its captures stay those of "
            + "the add-on they were made with"), RecapturePlan.frozen(root));
    }

    @Test
    void theMatrixIsAnIncludeListWithOnlyBuildAndChannel(@TempDir Path root) throws IOException {
        capture(root, "1.26.36.1", false);
        capture(root, "1.26.70.24", true);
        String text = RecapturePlan.matrix(RecapturePlan.of(root));
        var matrix = JsonParser.parseString(text).getAsJsonObject();
        assertEquals(1, matrix.size());
        var include = matrix.getAsJsonArray("include");
        assertEquals(2, include.size());
        assertEquals("{\"build\":\"1.26.36.1\",\"channel\":\"release\"}", include.get(0).toString());
        assertEquals("{\"build\":\"1.26.70.24\",\"channel\":\"preview\"}", include.get(1).toString());
        assertEquals(RecapturePlan.of(root), RecapturePlan.read(text));
    }

    @Test
    void aPlanReadBackHoldsBuildsAndChannelsOnly() {
        for (String bad : List.of("{\"include\":[{\"build\":\"1.26.36.1\",\"channel\":\"nightly\"}]}",
            "{\"include\":[{\"build\":\"../x\",\"channel\":\"release\"}]}",
            "{\"include\":[{\"build\":\"1.26.36.1\",\"channel\":\"release\",\"against\":\"1.26.36.1\"}]}",
            "{\"include\":[{\"build\":\"1.26.36.1\",\"channel\":\"release\"},{\"build\":\"1.26.36.1\",\"channel\":\"release\"}]}",
            "{\"include\":[], \"extra\": 1}", "[]", "not json")) {
            assertThrows(IllegalArgumentException.class, () -> RecapturePlan.read(bad), bad);
        }
        assertEquals(List.of(), RecapturePlan.read("{\"include\":[]}"));
    }

    @Test
    void aLegNamesABuildNumberAndNothingElse() {
        assertThrows(IllegalArgumentException.class, () -> new RecapturePlan.Leg("1.26.36.1\"; rm -rf /", false));
        assertThrows(IllegalArgumentException.class, () -> new RecapturePlan.Leg("../x", true));
    }
}
