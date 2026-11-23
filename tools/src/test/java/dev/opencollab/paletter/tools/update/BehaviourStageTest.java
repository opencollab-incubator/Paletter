package dev.opencollab.paletter.tools.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.bds.BdsDistribution;
import dev.opencollab.paletter.tools.behaviour.BehaviourDump;
import dev.opencollab.paletter.tools.behaviour.BehaviourProbe;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link BehaviourStage} compares and stages, without BDS: on the committed captures of the repository, in a verify-only
 * run (which writes nothing).
 */
class BehaviourStageTest {
    private static final Path ROOT = Path.of("..");
    /** A build with committed education, behaviour dump and probe captures. */
    private static final String BUILD = "1.26.63.1";

    private static Update update(String build) {
        Update update = new Update(ROOT, UpdateOptions.release().withVerifyOnly(true));
        update.begin(new BdsDistribution(build, false, "win", "https://example.invalid/bedrock-server-" + build + ".zip"));
        return update;
    }

    private static String education(String build) throws IOException {
        return Files.readString(ROOT.resolve("data/bds/" + build + "/education.json"));
    }

    @Test
    void aCaptureLikeTheCommittedOneIsNotStaged() throws IOException {
        Update update = update(BUILD);
        BehaviourStage stage = new BehaviourStage(update.context());
        String json = education(BUILD);
        // The build and protocol they were taken on are no difference
        String renamed = json.replace(BUILD, "9.9.9.9");
        assertFalse(stage.compareAndStage(BUILD, "Education capture", BehaviourStage.EDUCATION, "x",
            committed -> EducationProbe.sameContent(Files.readString(committed), renamed), renamed.getBytes(StandardCharsets.UTF_8)));
        assertEquals(java.util.List.of(), update.report().repoChanges);
        assertFalse(update.report().differs());
    }

    @Test
    void aChangedCaptureIsStagedAndReported() throws IOException {
        Update update = update(BUILD);
        BehaviourStage stage = new BehaviourStage(update.context());
        String changed = education(BUILD).replace("\"items\": [", "\"items\": [\n    \"minecraft:new_thing\",");
        assertTrue(stage.compareAndStage(BUILD, "Education capture", BehaviourStage.EDUCATION, "x",
            committed -> EducationProbe.sameContent(Files.readString(committed), changed), changed.getBytes(StandardCharsets.UTF_8)));
        assertEquals(java.util.List.of("would update `data/bds/" + BUILD + "/education.json` (education capture " + BUILD + ")"),
            update.report().repoChanges);
    }

    @Test
    void aBuildWithoutACaptureIsAddedWhateverTheEarlierOnesSay() throws IOException {
        Update update = update("1.26.99.1");
        BehaviourStage stage = new BehaviourStage(update.context());
        String json = education(BUILD);
        // Compared with the newest earlier build: the same identifiers, but this build has no file of its own yet
        assertTrue(stage.compareAndStage("1.26.99.1", "Education capture", BehaviourStage.EDUCATION, "x",
            committed -> EducationProbe.sameContent(Files.readString(committed), json), json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(java.util.List.of("would add `data/bds/1.26.99.1/education.json` (education capture 1.26.99.1)"), update.report().repoChanges);
    }

    @Test
    void aFailedOptionalProbeIsASkippedRowWithATodoNotAFailure() {
        Update update = update(BUILD);
        BehaviourStage stage = new BehaviourStage(update.context());
        stage.step("Behaviour probe", () -> {
            throw new IOException("BDS exited without PROBE-DONE");
        });
        assertFalse(update.report().failed());
        assertTrue(update.report().hasDecisions());
    }

    @Test
    void aFailedEssentialStepIsAFailure() {
        for (String name : java.util.List.of("Behaviour dump", "Education capture")) {
            Update update = update(BUILD);
            new BehaviourStage(update.context()).step(name, () -> {
                throw new IOException("BDS exited");
            });
            assertTrue(update.report().failed(), name);
        }
    }

    @Test
    void aProbeCaptureIsComparedWithoutItsBuild() throws IOException {
        String capture = Files.readString(ROOT.resolve("data/bds/" + BUILD + "/behaviour-probe/capture.json"));
        assertTrue(BehaviourProbe.sameCapture(capture, capture.replace("\"build\":\"", "\"build\":\"9")));
        assertFalse(BehaviourProbe.sameCapture(capture, capture.replace("\"friction\"", "\"friction_x\"")));
    }

    @Test
    void theBuildsAddOnsCaptureIsComparedWithoutItsBuild() throws IOException {
        JsonObject dump = BehaviourDump.read(ROOT.resolve("data/bds/" + BUILD + "/" + BehaviourDump.FILE));
        JsonObject other = dump.deepCopy();
        other.addProperty("bdsVersion", "9.9.9.9");
        other.addProperty("_generated", "elsewhere");
        assertTrue(BehaviourDump.sameContent(dump, other));
        other.add("unread", JsonParser.parseString("[\"X.y\"]"));
        assertFalse(BehaviourDump.sameContent(dump, other));
    }

    @Test
    void aPreviewBuildIsTheBetaItReports() throws IOException {
        BehaviourStage.requireBuild("1.26.70.24", true, "1.26.70-beta24");
        BehaviourStage.requireBuild("1.26.63.1", false, "1.26.63.1");
        IOException wrong = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
            () -> BehaviourStage.requireBuild("1.26.70.24", true, "1.26.70-beta23"));
        assertTrue(wrong.getMessage().contains("1.26.70-beta23"), wrong.getMessage());
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> BehaviourStage.requireBuild("1.26.63.1", false, "1.26.63.2"));
    }
}
