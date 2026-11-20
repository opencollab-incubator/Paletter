package dev.opencollab.paletter.tools.update;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals(List.of(new RecapturePlan.Leg("1.26.36.1", false, null), new RecapturePlan.Leg("1.26.63.1", false, "1.26.63.1"),
            new RecapturePlan.Leg("1.26.70.24", true, null)), legs);
    }

    @Test
    void theNewestUncheckedBuildOfEachKeptDataVersionJoins(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("paletter/src/main/resources/paletter"));
        Files.writeString(root.resolve("paletter/src/main/resources/paletter/versions.txt"), "26.30 1001\n26.40 2168\n26.60 2225\n");
        for (String[] build : new String[][] {{"1.26.36.1", "1001"}, {"1.26.45.1", "2169"}, {"1.26.63.1", "2225"}}) {
            Path spec = Files.createDirectories(root.resolve("data/bds").resolve(build[0]).resolve("spec"));
            Files.writeString(spec.resolve("capture.json"), "{\"protocol\": " + build[1] + "}");
        }
        Files.writeString(root.resolve("data/bds/builds.json"), """
            {"builds": {"1.26.44.2": {"protocol": 1001, "identicalTo": "1.26.36.1"}}}
            """);
        var list = new BuildHistory.BuildList("0".repeat(40), "2026-11-01", "1.26.63.1", List.of("1.26.36.1", "1.26.38.2", "1.26.38.3", "1.26.40.8",
            "1.26.44.2", "1.26.45.1", "1.26.47.1", "1.26.60.6", "1.26.63.1", "1.26.65.1", "1.26.80.4"), List.of());
        assertEquals(List.of(new RecapturePlan.Leg("1.26.36.1", false, null), new RecapturePlan.Leg("1.26.38.3", false, "1.26.36.1"),
            new RecapturePlan.Leg("1.26.45.1", false, null), new RecapturePlan.Leg("1.26.47.1", false, "1.26.45.1"),
            new RecapturePlan.Leg("1.26.63.1", false, null), new RecapturePlan.Leg("1.26.65.1", false, "1.26.63.1")), RecapturePlan.of(root, list),
            "the newest build of each line that has a kept build, newer than it, against it: 1.26.38.3 (not 38.2) of 26.30, 1.26.47.1 of 26.40, "
                + "1.26.65.1 of 26.60 (1.26.60.6 is older than the kept 1.26.63.1); 1.26.44.2 is recorded; 1.26.40.8 is older than 26.40's "
                + "kept build; 1.26.80.4 is of a line versions.txt lacks");
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
        assertEquals(List.of(new RecapturePlan.Leg("1.26.63.1", false, null)), RecapturePlan.of(root));
        assertEquals(List.of("1.26.36.1 is frozen (minecraft.net answers 404 since 2027-01-01): not recaptured, its captures stay those of "
            + "the add-on they were made with"), RecapturePlan.frozen(root));
    }

    @Test
    void theMatrixIsAnIncludeListWithOnlyBuildChannelAndAgainst(@TempDir Path root) throws IOException {
        capture(root, "1.26.36.1", false);
        capture(root, "1.26.70.24", true);
        var matrix = JsonParser.parseString(RecapturePlan.matrix(RecapturePlan.of(root))).getAsJsonObject();
        assertEquals(1, matrix.size());
        var include = matrix.getAsJsonArray("include");
        assertEquals(2, include.size());
        assertEquals("{\"build\":\"1.26.36.1\",\"channel\":\"release\",\"against\":\"\"}", include.get(0).toString());
        assertEquals("{\"build\":\"1.26.70.24\",\"channel\":\"preview\",\"against\":\"\"}", include.get(1).toString());
    }

    @Test
    void aLegNamesABuildNumberAndNothingElse() {
        assertThrows(IllegalArgumentException.class, () -> new RecapturePlan.Leg("1.26.36.1\"; rm -rf /", false, null));
        assertThrows(IllegalArgumentException.class, () -> new RecapturePlan.Leg("../x", true, null));
    }
}
