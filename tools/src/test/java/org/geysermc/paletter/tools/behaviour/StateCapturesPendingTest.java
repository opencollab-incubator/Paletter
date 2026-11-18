package org.geysermc.paletter.tools.behaviour;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which builds do not have the captures of the per-state probes yet. They are written by the recapture, which has not run since the
 * probes were added (nothing here ran BDS), so the builds are listed. A build that has the capture and is still listed fails, and so
 * does one that lacks it and is not listed: the list is emptied by the recapture, not by hand. A missing capture is not a failure of
 * the data: the values stay with the other sources ("unverified").
 */
class StateCapturesPendingTest {
    private static final Path BDS = Path.of(System.getProperty("paletter.root", "..")).resolve("data/bds");

    /**
     * Builds whose {@code behaviour-probe/capture.json} (of the probe before the per-state sections) has none of
     * {@code light_states}, {@code friction_states} and {@code around_recipes}. The recapture runs the probe again.
     */
    private static final Set<String> PENDING_STATES = Set.of("1.26.63.1");

    /** Builds without {@code food-probe/capture.json}: the probe is new. */
    private static final Set<String> PENDING_FOOD = Set.of("1.26.36.1", "1.26.45.1", "1.26.52.3", "1.26.63.1", "1.26.70.24");

    private static Set<String> builds() throws IOException {
        try (Stream<Path> builds = Files.list(BDS)) {
            return builds.filter(Files::isDirectory).map(build -> build.getFileName().toString()).collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        }
    }

    @Test
    void theBuildsWhoseProbeCaptureLacksThePerStateSectionsArePending() throws IOException {
        Set<String> without = new TreeSet<>();
        for (String build : builds()) {
            Path capture = BDS.resolve(build).resolve(BehaviourProbe.DIRECTORY).resolve(BehaviourProbe.CAPTURE);
            if (Files.isRegularFile(capture)) {
                JsonObject json = JsonParser.parseString(Files.readString(capture)).getAsJsonObject();
                if (!json.has(StateProbe.LIGHT) && !json.has(StateProbe.FRICTION) && !json.has(StateProbe.AROUND)) {
                    without.add(build);
                }
            }
        }
        assertEquals(PENDING_STATES, without, "the builds with a probe capture without the per-state sections must be PENDING_STATES exactly");
    }

    @Test
    void theBuildsWithoutAFoodProbeCaptureArePending() throws IOException {
        Set<String> without = new TreeSet<>();
        for (String build : builds()) {
            if (!Files.isRegularFile(BDS.resolve(build).resolve(FoodProbe.DIRECTORY).resolve(FoodProbe.CAPTURE))) {
                without.add(build);
            }
        }
        assertEquals(PENDING_FOOD, without, "the builds without food-probe/capture.json must be PENDING_FOOD exactly");
    }
}
