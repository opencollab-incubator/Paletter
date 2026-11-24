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
        assertEquals(List.of(new RecapturePlan.Leg("1.26.36.1", false, null), new RecapturePlan.Leg("1.26.63.1", false, "1.26.63.1"),
            new RecapturePlan.Leg("1.26.70.24", true, null)), legs);
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
        assertEquals(List.of(new RecapturePlan.Leg("1.26.36.1", false, null), new RecapturePlan.Leg("1.26.63.1", false, null)), RecapturePlan.of(root),
            "1.26.44.2 is identical to a kept build; 1.26.66.1 (unrecorded, no captures) is not looked for");
    }

    @Test
    void namedBuildsKeepThePlanOrder(@TempDir Path root) throws IOException {
        capture(root, "1.26.36.1", false);
        capture(root, "1.26.63.1", false);
        capture(root, "1.26.70.24", true);
        List<RecapturePlan.Leg> legs = RecapturePlan.of(root);
        assertEquals(List.of(new RecapturePlan.Leg("1.26.36.1", false, null), new RecapturePlan.Leg("1.26.70.24", true, null)),
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
        assertThrows(IllegalArgumentException.class, () -> new RecapturePlan.Leg("1.26.66.1", false, "1.26.63.1"));
    }
}
