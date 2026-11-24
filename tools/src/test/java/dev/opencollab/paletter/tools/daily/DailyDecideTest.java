package dev.opencollab.paletter.tools.daily;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.BuildNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rows of the daily decision (docs/data-pipeline.md, "The daily check") and their order. */
class DailyDecideTest {
    private static final String COMMIT = "a".repeat(40);
    private static final String OTHER_COMMIT = "b".repeat(40);
    private static final String TREE = "c".repeat(64);
    private static final String OTHER_TREE = "d".repeat(64);
    private static final String RELEASE = "1.26.66.1";
    private static final String PREVIEW = "1.26.70.25";
    private static final String LINKS = "{\"result\": {\"links\": ["
        + "{\"downloadType\": \"serverBedrockWindows\", \"downloadUrl\": \"https://www.minecraft.net/bedrockdedicatedserver/bin-win/bedrock-server-" + RELEASE + ".zip\"},"
        + "{\"downloadType\": \"serverBedrockLinux\", \"downloadUrl\": \"https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-" + RELEASE + ".zip\"},"
        + "{\"downloadType\": \"serverBedrockPreviewWindows\", \"downloadUrl\": \"https://www.minecraft.net/bedrockdedicatedserver/bin-win-preview/bedrock-server-" + PREVIEW + ".zip\"}"
        + "]}}";

    private static DailyDecide.Issue ownIssue(String channel, String body) {
        return new DailyDecide.Issue(7, DailyDecide.issueTitle(channel), "app/github-actions", body);
    }

    private static DailyDecide.Pull dataPull(String channel, String body) {
        return new DailyDecide.Pull(9, "bds-data/" + channel, false, body);
    }

    private static DailyDecide.Decision release(Path root, boolean force, List<DailyDecide.Issue> issues, List<DailyDecide.Pull> pulls) throws IOException {
        return DailyDecide.decide(root, "release", RELEASE, force, COMMIT, TREE, issues, pulls);
    }

    private static void record(Path root, String build) throws IOException {
        Files.createDirectories(root.resolve("data/bds"));
        Files.writeString(root.resolve("data/bds/builds.json"), "{\"builds\": {\"" + build + "\": {\"protocol\": 2225, \"identicalTo\": \"1.26.63.1\"}}}");
    }

    @Test
    void row5ANewBuildIsCapturedAsTheFirstAttempt(@TempDir Path root) throws IOException {
        DailyDecide.Decision decision = release(root, false, List.of(), List.of());
        assertTrue(decision.capture());
        assertEquals(1, decision.attempt());
    }

    @Test
    void row2ARecordedBuildOrOneWithCapturesIsKnown(@TempDir Path root) throws IOException {
        record(root, RELEASE);
        assertFalse(release(root, false, List.of(), List.of()).capture(), "recorded, here as identical to another build");
        Files.delete(root.resolve("data/bds/builds.json"));
        Files.createDirectories(root.resolve("data/bds").resolve(RELEASE).resolve("spec"));
        DailyDecide.Decision decision = release(root, false, List.of(), List.of());
        assertFalse(decision.capture());
        assertTrue(decision.reason().contains("data/bds/" + RELEASE), decision.reason());
    }

    @Test
    void row3ABuildWaitingInTheDataPullRequestIsSkipped(@TempDir Path root) throws IOException {
        DailyDecide.Decision decision = release(root, false, List.of(), List.of(dataPull("release", "report\n<!-- bds-check: " + RELEASE + " " + TREE + " -->\nmore")));
        assertFalse(decision.capture());
        assertEquals("waiting in #9", decision.reason());
        // The pull request of the other channel, or of another build, decides nothing
        assertTrue(release(root, false, List.of(), List.of(dataPull("preview", "<!-- bds-check: " + RELEASE + " " + TREE + " -->"))).capture());
        assertTrue(release(root, false, List.of(), List.of(dataPull("release", "<!-- bds-check: 1.26.63.1 " + TREE + " -->"))).capture());
    }

    @Test
    void row3APullRequestBuiltOnOtherDataIsRefreshed(@TempDir Path root) throws IOException {
        // The other channel's pull request was merged since, or the marker predates the data tree
        for (String marker : List.of("<!-- bds-check: " + RELEASE + " " + OTHER_TREE + " -->", "<!-- bds-check: " + RELEASE + " -->")) {
            DailyDecide.Decision decision = release(root, false, List.of(), List.of(dataPull("release", marker)));
            assertTrue(decision.capture(), marker);
            assertEquals(1, decision.attempt());
            assertTrue(decision.reason().startsWith("waiting in #9, built on other data"), decision.reason());
        }
        // A refresh that failed twice at this commit waits like any other capture
        List<DailyDecide.Issue> twice = List.of(ownIssue("release", "<!-- bds-check: " + RELEASE + " " + COMMIT + " 2 -->"));
        assertFalse(release(root, false, twice, List.of(dataPull("release", "<!-- bds-check: " + RELEASE + " " + OTHER_TREE + " -->"))).capture());
    }

    @Test
    void row4TwoFailuresAtThisCommitAreSkippedUntilTheCommitChanges(@TempDir Path root) throws IOException {
        List<DailyDecide.Issue> twice = List.of(ownIssue("release", "<!-- bds-check: " + RELEASE + " " + COMMIT + " 2 -->\nreport"));
        DailyDecide.Decision skipped = release(root, false, twice, List.of());
        assertFalse(skipped.capture());
        assertTrue(skipped.reason().startsWith("failed 2 times"), skipped.reason());
        // A fix (a new commit) or a new build is tried again
        assertTrue(DailyDecide.decide(root, "release", RELEASE, false, OTHER_COMMIT, TREE, twice, List.of()).capture());
        assertTrue(DailyDecide.decide(root, "release", "1.26.66.2", false, COMMIT, TREE, twice, List.of()).capture());
    }

    @Test
    void row5AfterOneFailureAtThisCommitIsTheRetry(@TempDir Path root) throws IOException {
        DailyDecide.Decision retry = release(root, false, List.of(ownIssue("release", "<!-- bds-check: " + RELEASE + " " + COMMIT + " 1 -->")), List.of());
        assertTrue(retry.capture());
        assertEquals(2, retry.attempt());
        DailyDecide.Decision otherCommit = release(root, false, List.of(ownIssue("release", "<!-- bds-check: " + RELEASE + " " + OTHER_COMMIT + " 1 -->")), List.of());
        assertEquals(1, otherCommit.attempt());
    }

    @Test
    void row1ForceCapturesWhateverIsKnown(@TempDir Path root) throws IOException {
        record(root, RELEASE);
        List<DailyDecide.Issue> twice = List.of(ownIssue("release", "<!-- bds-check: " + RELEASE + " " + COMMIT + " 2 -->"));
        DailyDecide.Decision forced = release(root, true, twice, List.of(dataPull("release", "<!-- bds-check: " + RELEASE + " " + TREE + " -->")));
        assertTrue(forced.capture());
        assertEquals(2, forced.attempt(), "never beyond the second attempt");
    }

    @Test
    void theRowsApplyInOrder(@TempDir Path root) throws IOException {
        List<DailyDecide.Issue> twice = List.of(ownIssue("release", "<!-- bds-check: " + RELEASE + " " + COMMIT + " 2 -->"));
        List<DailyDecide.Pull> waiting = List.of(dataPull("release", "<!-- bds-check: " + RELEASE + " " + TREE + " -->"));
        assertEquals("waiting in #9", release(root, false, twice, waiting).reason(), "row 3 before row 4");
        record(root, RELEASE);
        assertTrue(release(root, false, twice, waiting).reason().startsWith("known"), "row 2 before row 3");
    }

    @Test
    void textOfOthersDecidesNothing(@TempDir Path root) throws IOException {
        String twice = "<!-- bds-check: " + RELEASE + " " + COMMIT + " 2 -->";
        // An issue by a person with the same title, an issue of another title, a fork's pull request
        assertTrue(release(root, false, List.of(new DailyDecide.Issue(1, DailyDecide.issueTitle("release"), "someone", twice)), List.of()).capture());
        assertTrue(release(root, false, List.of(new DailyDecide.Issue(1, "BDS data: release", "app/github-actions", twice)), List.of()).capture());
        assertTrue(release(root, false, List.of(), List.of(new DailyDecide.Pull(3, "bds-data/release", true, "<!-- bds-check: " + RELEASE + " " + TREE + " -->"))).capture());
        // Malformed markers, and markers that are not a line of their own (markdown quoting one)
        for (String body : List.of("<!-- bds-check: " + RELEASE + " " + COMMIT + " 3 -->", "<!-- bds-check: " + RELEASE + " abc 2 -->",
            "<!--bds-check: " + RELEASE + " " + COMMIT + " 2-->", "`" + twice + "`", "> " + twice, "text " + twice)) {
            DailyDecide.Decision decision = release(root, false, List.of(ownIssue("release", body)), List.of());
            assertTrue(decision.capture() && decision.attempt() == 1, body);
        }
        for (String body : List.of("`<!-- bds-check: " + RELEASE + " " + TREE + " -->`", "<!-- bds-check: " + RELEASE + " " + TREE + " --> and more", "<!-- bds-check: 1.26.66 -->")) {
            assertTrue(release(root, false, List.of(), List.of(dataPull("release", body))).capture(), body);
        }
    }

    @Test
    void oneLinksAnswerServesBothChannels(@TempDir Path root) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        List<DailyDecide.Decision> decisions = DailyDecide.decide(root, DailyDecide.CHANNELS, null, false, COMMIT, TREE, List.of(), List.of(), () -> {
            requests.incrementAndGet();
            return LINKS;
        });
        assertEquals(1, requests.get());
        assertEquals(List.of(RELEASE, PREVIEW), decisions.stream().map(DailyDecide.Decision::build).toList());
        assertEquals(List.of("release", "preview"), decisions.stream().map(DailyDecide.Decision::channel).toList());
    }

    @Test
    void anExplicitVersionMakesNoRequest(@TempDir Path root) throws Exception {
        DailyDecide.Links none = () -> {
            throw new AssertionError("no request with an explicit version");
        };
        assertEquals("1.26.63.1", DailyDecide.decide(root, List.of("release"), "1.26.63.1", false, COMMIT, TREE, List.of(), List.of(), none).get(0).build());
        assertThrows(IllegalArgumentException.class, () -> DailyDecide.decide(root, DailyDecide.CHANNELS, "1.26.63.1", false, COMMIT, TREE, List.of(), List.of(), none));
        assertThrows(IllegalArgumentException.class, () -> DailyDecide.decide(root, List.of("release"), "1.26.63.1; rm", false, COMMIT, TREE, List.of(), List.of(), none));
        assertThrows(IllegalArgumentException.class, () -> DailyDecide.decide(root, List.of("release"), "1.26.63.1", false, "HEAD", TREE, List.of(), List.of(), none));
        assertThrows(IllegalArgumentException.class, () -> DailyDecide.decide(root, List.of("release"), "1.26.63.1", false, COMMIT, "abc", List.of(), List.of(), none));
    }

    @Test
    void theMatrixHoldsOnlyBuildsChannelsAndAttempts(@TempDir Path root) throws Exception {
        record(root, RELEASE);
        List<DailyDecide.Decision> decisions = DailyDecide.decide(root, DailyDecide.CHANNELS, null, false, COMMIT, TREE,
            List.of(ownIssue("preview", "<!-- bds-check: " + PREVIEW + " " + COMMIT + " 1 -->")), List.of(), () -> LINKS);
        JsonObject matrix = JsonParser.parseString(DailyDecide.matrix(decisions)).getAsJsonObject();
        assertEquals(Set.of("include"), matrix.keySet());
        assertEquals(1, matrix.getAsJsonArray("include").size(), "the known release is no leg");
        for (JsonElement leg : matrix.getAsJsonArray("include")) {
            JsonObject entry = leg.getAsJsonObject();
            assertEquals(Set.of("channel", "build", "attempt"), entry.keySet());
            assertTrue(DailyDecide.CHANNELS.contains(entry.get("channel").getAsString()));
            assertTrue(BuildNumber.isBuild(entry.get("build").getAsString()));
            assertTrue(Set.of(1, 2).contains(entry.get("attempt").getAsInt()));
        }
        assertEquals("{\"include\":[{\"channel\":\"preview\",\"build\":\"" + PREVIEW + "\",\"attempt\":2}]}", DailyDecide.matrix(decisions));
        assertEquals("{\"include\":[]}", DailyDecide.matrix(List.of()));
        assertTrue(DailyDecide.summary(decisions).contains("| release | " + RELEASE + " | skip: known"), DailyDecide.summary(decisions));
    }

    @Test
    void theGhListsAreRead() {
        List<DailyDecide.Issue> issues = DailyDecide.issues("[{\"number\": 4, \"title\": \"t\", \"author\": {\"login\": \"app/github-actions\", "
            + "\"is_bot\": true}, \"body\": \"b\"}, {\"number\": 5, \"title\": \"u\", \"author\": null, \"body\": null}]");
        assertEquals(List.of(new DailyDecide.Issue(4, "t", "app/github-actions", "b"), new DailyDecide.Issue(5, "u", "", "")), issues);
        List<DailyDecide.Pull> pulls = DailyDecide.pulls("[{\"number\": 9, \"headRefName\": \"bds-data/release\", \"isCrossRepository\": false, "
            + "\"body\": \"x\"}, {\"number\": 10, \"headRefName\": \"bds-data/release\", \"body\": \"y\"}]");
        assertEquals(List.of(new DailyDecide.Pull(9, "bds-data/release", false, "x"), new DailyDecide.Pull(10, "bds-data/release", true, "y")), pulls,
            "a pull request that does not say where it comes from counts as a fork's");
    }
}
