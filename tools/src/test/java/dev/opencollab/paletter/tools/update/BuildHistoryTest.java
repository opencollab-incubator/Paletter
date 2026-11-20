package dev.opencollab.paletter.tools.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.Versions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The build list, the record of checked builds, how builds are checked and the comparison of captures of {@code builds}. */
class BuildHistoryTest {
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String VERSIONS = """
        {
          "cdn_root": "https://www.minecraft.net/bedrockdedicatedserver",
          "linux": {"stable": "1.26.63.1", "versions": ["1.26.20.4", "1.26.30.5", "1.26.63.1"]},
          "windows": {"stable": "1.26.100.2", "preview": "1.26.70.24",
            "versions": ["1.26.23.1", "1.26.30.5", "1.26.31.1", "1.26.36.1", "1.26.40.8", "1.26.63.1", "1.26.100.2"],
            "preview_versions": ["1.26.70.24"]}
        }
        """;

    private static BuildHistory.BuildList list() {
        return BuildHistory.parse(VERSIONS, COMMIT, "2026-11-15T10:00:00Z");
    }

    @Test
    void listsWindowsReleasesOfTheRangeOldestFirst() {
        BuildHistory.BuildList list = list();
        assertEquals(List.of("1.26.30.5", "1.26.31.1", "1.26.36.1", "1.26.40.8", "1.26.63.1", "1.26.100.2"), list.range("1.26.30", null));
        // A line as the upper bound takes its hotfixes; previews are never listed
        assertEquals(List.of("1.26.30.5", "1.26.31.1", "1.26.36.1"), list.range("1.26.30", "1.26.36"));
        assertEquals(List.of("1.26.23.1", "1.26.30.5"), list.range("1.26.0", "1.26.30.5"));
        assertEquals("1.26.100.2", list.newest());
        assertEquals(COMMIT, list.commit());
    }

    @Test
    void aListedBuildThatIsNoBuildNumberIsRefused() {
        // Build numbers name directories that are deleted: a separator or a dot dot never gets that far
        for (String bad : new String[] {"1.26.30.5\\\\..\\\\..\\\\x", "../../x", "1.26.30", "1.26.30.5 "}) {
            String json = "{\"windows\": {\"versions\": [\"1.26.31.1\", \"" + bad + "\"]}}";
            assertThrows(IllegalArgumentException.class, () -> BuildHistory.parse(json, COMMIT, "d"), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> list().range("1.26.30/..", null));
        assertThrows(IllegalArgumentException.class, () -> list().range("1.26.30", "x"));
    }

    @Test
    void aListBehindMinecraftNetIsWarnedAbout() {
        assertNull(BuildHistory.staleness(list(), "1.26.100.2"));
        String warning = BuildHistory.staleness(list(), "1.26.101.1");
        assertTrue(warning.startsWith("WARNING") && warning.contains("1.26.100.2") && warning.contains("1.26.101.1") && warning.contains(COMMIT), warning);
    }

    @Test
    void theCurrentReleaseOfTheLinksApiJoinsAListThatLacksIt() {
        BuildHistory.BuildList list = list().withCurrentRelease("1.26.101.1");
        assertEquals("1.26.101.1", list.newest());
        assertEquals(List.of("1.26.101.1"), list.fromLinksApi());
        assertEquals(BuildHistory.FROM_LINKS_API, list.source("1.26.101.1"));
        assertEquals(BuildHistory.FROM_LIST, list.source("1.26.100.2"));
        // Covered this way, the difference is a note, not a warning
        String note = BuildHistory.staleness(list, "1.26.101.1");
        assertTrue(note.startsWith("NOTE") && note.contains("newest listed 1.26.100.2"), note);
        // Listed already, older than the list, or unknown: the list stays as it is
        assertEquals(list(), list().withCurrentRelease("1.26.100.2"));
        assertEquals(list(), list().withCurrentRelease("1.26.63.1"));
        assertEquals(list(), list().withCurrentRelease(null));
        assertThrows(IllegalArgumentException.class, () -> list().withCurrentRelease("1.26.101.1\\..\\x"));
    }

    @Test
    void theRecordSaysWhereEachBuildNumberCameFrom(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("data/bds"));
        Map<String, BuildHistory.Entry> builds = new TreeMap<>(Versions.ORDER);
        builds.put("1.26.63.1", new BuildHistory.Entry(2225, null));
        builds.put("1.26.65.1", new BuildHistory.Entry(2225, "1.26.63.1", BuildHistory.FROM_LINKS_API, null, List.of()));
        BuildHistory.write(root, builds, list());
        JsonObject entries = JsonParser.parseString(Files.readString(root.resolve("data/bds/builds.json"))).getAsJsonObject().getAsJsonObject("builds");
        assertEquals("BDS-Versions", entries.getAsJsonObject("1.26.63.1").get("source").getAsString());
        assertEquals("links API", entries.getAsJsonObject("1.26.65.1").get("source").getAsString());
        assertEquals(builds, BuildHistory.read(root));
    }

    @Test
    void recordRoundTripsWithItsSourceAndOtherProtocols(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("data/bds"));
        Map<String, BuildHistory.Entry> builds = new TreeMap<>(Versions.ORDER);
        builds.put("1.26.31.1", new BuildHistory.Entry(1001, "1.26.36.1"));
        builds.put("1.26.36.1", new BuildHistory.Entry(1001, null));
        builds.put("1.26.40.8", new BuildHistory.Entry(2168, "1.26.45.1", BuildHistory.FROM_LIST, null,
            List.of("BDS inventory: 1.26.45.1 has no inventory.json")));
        builds.put("1.26.45.1", new BuildHistory.Entry(2169, null));
        builds.put("1.26.51.1", new BuildHistory.Entry(2193, null, BuildHistory.FROM_LIST, BuildHistory.KEPT_BY_HISTORY_CHECK, List.of()));
        BuildHistory.write(root, builds, list());
        String text = Files.readString(root.resolve("data/bds/builds.json"));
        assertTrue(text.startsWith("{\n  \"_generated\": \"Generated by"), text);
        assertTrue(text.indexOf("1.26.31.1") < text.indexOf("1.26.36.1"), "oldest first");
        JsonObject json = JsonParser.parseString(text).getAsJsonObject();
        assertEquals(COMMIT, json.get("listCommit").getAsString());
        assertEquals("2026-11-15T10:00:00Z", json.get("listDate").getAsString());
        JsonObject entries = json.getAsJsonObject("builds");
        // An identical build under another protocol says so
        assertEquals(2169, entries.getAsJsonObject("1.26.40.8").get("identicalToProtocol").getAsInt());
        assertFalse(entries.getAsJsonObject("1.26.31.1").has("identicalToProtocol"));
        // How a build was kept, and what its run could not compare
        assertEquals("update", entries.getAsJsonObject("1.26.45.1").get("keptBy").getAsString());
        assertEquals("history check", entries.getAsJsonObject("1.26.51.1").get("keptBy").getAsString());
        assertEquals("BDS inventory: 1.26.45.1 has no inventory.json", entries.getAsJsonObject("1.26.40.8").getAsJsonArray("notCompared").get(0).getAsString());
        assertEquals(builds, BuildHistory.read(root));
        // Written through a temporary file that does not stay
        try (var files = Files.list(root.resolve("data/bds"))) {
            assertEquals(List.of("builds.json"), files.map(file -> file.getFileName().toString()).toList());
        }
    }

    /** A kept build records its zips per platform (unknown without one) and whether it is frozen; both round-trip. */
    @Test
    void theRecordKeepsZipChecksumsAndTheFrozenState(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("data/bds"));
        Map<String, BuildHistory.Entry> builds = new TreeMap<>(Versions.ORDER);
        builds.put("1.26.36.1", new BuildHistory.Entry(1001, null, BuildHistory.FROM_CAPTURES, BuildHistory.KEPT_BY_UPDATE, List.of(),
            Map.of("win", new BuildHistory.Zip("ab".repeat(32), 83725211), "linux", new BuildHistory.Zip(null, 0)), "gone from minecraft.net"));
        builds.put("1.26.40.8", new BuildHistory.Entry(1001, "1.26.36.1"));
        BuildHistory.write(root, builds, null);
        JsonObject entry = JsonParser.parseString(Files.readString(root.resolve("data/bds/builds.json"))).getAsJsonObject()
            .getAsJsonObject("builds").getAsJsonObject("1.26.36.1");
        assertEquals("ab".repeat(32), entry.getAsJsonObject("zips").getAsJsonObject("win").get("sha256").getAsString());
        assertEquals("unknown", entry.getAsJsonObject("zips").getAsJsonObject("linux").get("sha256").getAsString());
        assertEquals("gone from minecraft.net", entry.get("frozen").getAsString());
        assertEquals(builds, BuildHistory.read(root));
        assertEquals("ab".repeat(32), dev.opencollab.paletter.tools.bds.ZipChecksums.recorded(root, "win", "1.26.36.1"));
        assertEquals(null, dev.opencollab.paletter.tools.bds.ZipChecksums.recorded(root, "linux", "1.26.36.1"), "unknown is no checksum");
    }

    /** Checksums come from the cached zips only: a missing zip stays unknown, and a cached zip that disagrees with the record fails. */
    @Test
    void zipChecksumsComeFromTheCacheOnly(@TempDir Path cache) throws Exception {
        Path win = Files.createDirectories(cache.resolve("zips/win")).resolve("bedrock-server-1.26.36.1.zip");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(win))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("bedrock_server.exe"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
        }
        Map<String, BuildHistory.Zip> zips = BuildHistory.zips(cache, "1.26.36.1", Map.of());
        assertEquals(dev.opencollab.paletter.tools.bds.ZipChecksums.sha256(win), zips.get("win").sha256());
        assertEquals(Files.size(win), zips.get("win").size());
        assertFalse(zips.get("linux").known(), "no Linux zip in the cache: unknown, never guessed");
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> BuildHistory.zips(cache, "1.26.36.1", Map.of("win", new BuildHistory.Zip("00".repeat(32), 1))));
        assertTrue(e.getMessage().contains("00".repeat(32)), e.getMessage());
    }

    @Test
    void theRecordNamesOnlyBuilds(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("data/bds"));
        Files.writeString(root.resolve("data/bds/builds.json"), "{\"builds\": {\"1.26.31.1\": {\"protocol\": 1, \"identicalTo\": \"../x\"}}}");
        assertThrows(IllegalArgumentException.class, () -> BuildHistory.read(root));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // How builds are checked

    @Test
    void plansCompareWithTheNearestKeptBuild() {
        Map<String, BuildHistory.Entry> record = new TreeMap<>(Versions.ORDER);
        Map<String, Integer> captured = new TreeMap<>(Versions.ORDER);
        captured.put("1.26.36.1", 1001);
        captured.put("1.26.45.1", 2169);
        // Nothing older is kept: the next kept build
        assertEquals(new BuildHistory.Step("1.26.30.5", BuildHistory.Kind.CHECK, "1.26.36.1"), BuildHistory.plan(record, captured, "1.26.30.5"));
        // A build with captures and nothing kept before it is kept as it is
        assertEquals(new BuildHistory.Step("1.26.36.1", BuildHistory.Kind.FIRST, null), BuildHistory.plan(record, captured, "1.26.36.1"));
        record.put("1.26.30.5", new BuildHistory.Entry(1001, "1.26.36.1"));
        record.put("1.26.36.1", new BuildHistory.Entry(1001, null));
        assertEquals(new BuildHistory.Step("1.26.30.5", BuildHistory.Kind.RECORDED, null), BuildHistory.plan(record, captured, "1.26.30.5"));
        assertEquals(new BuildHistory.Step("1.26.40.8", BuildHistory.Kind.CHECK, "1.26.36.1"), BuildHistory.plan(record, captured, "1.26.40.8"));
        record.put("1.26.40.8", new BuildHistory.Entry(2168, null));
        // A build with captures the record lacks is verified against the previous kept build
        assertEquals(new BuildHistory.Step("1.26.45.1", BuildHistory.Kind.VERIFY, "1.26.40.8"), BuildHistory.plan(record, captured, "1.26.45.1"));
        assertThrows(IllegalStateException.class, () -> BuildHistory.plan(new TreeMap<>(), new TreeMap<>(), "1.26.30.5"));
    }

    @Test
    void aCommittedBuildReplacesTheIdenticalBuildAHistoryCheckKept() {
        Map<String, BuildHistory.Entry> record = new TreeMap<>(Versions.ORDER);
        record.put("1.26.36.1", new BuildHistory.Entry(1001, null));
        record.put("1.26.40.8", new BuildHistory.Entry(2168, null, BuildHistory.FROM_LIST, BuildHistory.KEPT_BY_HISTORY_CHECK, List.of()));
        record.put("1.26.42.1", new BuildHistory.Entry(2168, "1.26.40.8"));
        Map<String, Integer> captured = new TreeMap<>(Map.of("1.26.36.1", 1001, "1.26.40.8", 2168, "1.26.45.1", 2169));
        BuildHistory.Step step = new BuildHistory.Step("1.26.45.1", BuildHistory.Kind.VERIFY, "1.26.40.8");
        List<String> notCompared = List.of("Add-on content log: 1.26.40.8 has none");

        // The record says update kept the older one: both stay
        Map<String, BuildHistory.Entry> both = new TreeMap<>(record);
        both.put("1.26.40.8", new BuildHistory.Entry(2168, null));
        assertNull(BuildHistory.apply(both, captured, step, new BuildHistory.Outcome(2169, false, false), BuildHistory.FROM_LIST));
        assertTrue(both.get("1.26.40.8").kept() && both.get("1.26.45.1").kept());

        // Different data: both stay
        Map<String, BuildHistory.Entry> different = new TreeMap<>(record);
        assertNull(BuildHistory.apply(different, captured, step, new BuildHistory.Outcome(2169, false, true), BuildHistory.FROM_LIST));
        assertTrue(different.get("1.26.40.8").kept());

        // The same data, and the record says a history check kept the older one: one capture for the stretch, the newer
        assertEquals("1.26.40.8", BuildHistory.apply(record, captured, step, new BuildHistory.Outcome(2169, false, false, notCompared),
            BuildHistory.FROM_LIST));
        assertEquals(new BuildHistory.Entry(2168, "1.26.45.1", BuildHistory.FROM_LIST, null, notCompared), record.get("1.26.40.8"));
        assertEquals(new BuildHistory.Entry(2168, "1.26.45.1"), record.get("1.26.42.1"));
        assertEquals(new BuildHistory.Entry(2169, null, BuildHistory.FROM_LIST, BuildHistory.KEPT_BY_UPDATE, notCompared), record.get("1.26.45.1"));
        assertEquals(new BuildHistory.Entry(1001, null), record.get("1.26.36.1"));
    }

    @Test
    void aBuildAHistoryCheckKeptIsRecordedAsSuchWithWhatWasNotCompared() {
        Map<String, BuildHistory.Entry> record = new TreeMap<>(Versions.ORDER);
        BuildHistory.Step step = new BuildHistory.Step("1.26.65.1", BuildHistory.Kind.CHECK, "1.26.63.1");
        List<String> notCompared = List.of("Block numbering: 3 states");
        assertNull(BuildHistory.apply(record, Map.of(), step, new BuildHistory.Outcome(2226, false, true, notCompared), BuildHistory.FROM_LINKS_API));
        assertEquals(new BuildHistory.Entry(2226, null, BuildHistory.FROM_LINKS_API, BuildHistory.KEPT_BY_HISTORY_CHECK, notCompared),
            record.get("1.26.65.1"));
        assertTrue(record.get("1.26.65.1").keptByHistoryCheck());
    }

    /** A report as update writes it ({@link Report#render}) for {@code build}. */
    private static void report(Path directory, String build, String against) throws IOException {
        Report report = new Report("Paletter build check: BDS " + build + (against == null ? "" : " against " + against));
        report.against = against;
        report.generatedBy = "./gradlew :tools:run --args=\"update --channel release --version " + build
            + (against == null ? "" : " --against " + against) + "\"";
        report.check(Report.Status.DIFF, "Script API vs baseline", "3 differences");
        report.write(directory.resolve(Update.REPORT));
    }

    @Test
    void capturesAHistoryCheckKeptThatTheRecordLacksStopTheCheck(@TempDir Path root, @TempDir Path cache) throws Exception {
        committed(root, "1.26.36.1", 1001);
        committed(root, "1.26.40.8", 2168);
        report(root.resolve("data/bds/1.26.40.8"), "1.26.40.8", "1.26.36.1");
        committed(root, "1.26.70.1", 2300);
        report(root.resolve("data/bds/1.26.70.1"), "1.26.70.1", null);
        assertTrue(BuildHistory.reportOfHistoryCheck(root.resolve("data/bds/1.26.40.8")));
        assertFalse(BuildHistory.reportOfHistoryCheck(root.resolve("data/bds/1.26.70.1")), "an update's report");
        assertFalse(BuildHistory.reportOfHistoryCheck(root.resolve("data/bds/1.26.36.1")), "no report");
        List<String> problems = BuildHistory.problems(root, BuildHistory.read(root), BuildHistory.capturedReleases(root));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().startsWith("data/bds/1.26.40.8 holds captures a history check kept"), problems.getFirst());
        assertEquals(1, BuildHistory.check(root, list(), List.of("1.26.42.1"), cache,
            (build, against, verifyOnly) -> { throw new AssertionError("no run"); }, Integer.MAX_VALUE, 0));
    }

    @Test
    void aRecordNamingMissingCapturesIsAProblem(@TempDir Path root) throws IOException {
        committed(root, "1.26.36.1", 1001);
        Map<String, BuildHistory.Entry> record = new TreeMap<>(Versions.ORDER);
        record.put("1.26.36.1", new BuildHistory.Entry(1001, null));
        record.put("1.26.40.8", new BuildHistory.Entry(2168, null, BuildHistory.FROM_LIST, BuildHistory.KEPT_BY_HISTORY_CHECK, List.of()));
        record.put("1.26.42.1", new BuildHistory.Entry(2168, "1.26.31.1"));
        List<String> problems = BuildHistory.problems(root, record, BuildHistory.capturedReleases(root));
        assertEquals(List.of("1.26.40.8 is recorded as kept but has no captures in data/bds/1.26.40.8",
            "1.26.42.1 is recorded as identical to 1.26.31.1, which is not a kept build"), problems);
    }

    @Test
    void checkRecordsEveryBuildAndKeepsOneCapturePerStretch(@TempDir Path root, @TempDir Path cache) throws Exception {
        committed(root, "1.26.36.1", 1001);
        committed(root, "1.26.45.1", 2169);
        BuildHistory.BuildList list = list();
        List<String> builds = List.of("1.26.30.5", "1.26.31.1", "1.26.36.1", "1.26.40.8", "1.26.42.1", "1.26.45.1");
        // The data: 30.5 to 36.1 alike, 40.8 to 45.1 alike under two protocols
        Map<String, Integer> protocols = Map.of("1.26.30.5", 1001, "1.26.31.1", 1001, "1.26.36.1", 1001, "1.26.40.8", 2168, "1.26.42.1", 2168, "1.26.45.1", 2169);
        Map<String, String> data = Map.of("1.26.30.5", "a", "1.26.31.1", "a", "1.26.36.1", "a", "1.26.40.8", "b", "1.26.42.1", "b", "1.26.45.1", "b");
        List<String> runs = new ArrayList<>();
        BuildHistory.Runner runner = (build, against, verifyOnly) -> {
            runs.add(build + (verifyOnly ? " verify " : " ") + against);
            boolean changed = !data.get(build).equals(data.get(against));
            if (changed && !verifyOnly) {
                // What update does with a build that differs: its captures and report go to data/bds
                Path directory = root.resolve("data/bds").resolve(build);
                Files.createDirectories(directory.resolve("spec"));
                Files.writeString(directory.resolve("spec/capture.json"), "{\"bdsVersion\": \"" + build + "\", \"protocol\": " + protocols.get(build) + "}");
                report(directory, build, against);
            }
            return new BuildHistory.Outcome(protocols.get(build), false, changed);
        };

        // --limit counts runs
        assertEquals(0, BuildHistory.check(root, list, builds, cache, runner, 1, 0));
        assertEquals(List.of("1.26.30.5 1.26.36.1"), runs);
        assertEquals(0, BuildHistory.check(root, list, builds, cache, runner, Integer.MAX_VALUE, 0));
        assertEquals(List.of("1.26.30.5 1.26.36.1", "1.26.31.1 1.26.36.1", "1.26.40.8 1.26.36.1", "1.26.42.1 1.26.40.8",
            "1.26.45.1 verify 1.26.40.8"), runs);

        Map<String, BuildHistory.Entry> record = BuildHistory.read(root);
        Map<String, BuildHistory.Entry> expected = new TreeMap<>(Versions.ORDER);
        expected.put("1.26.30.5", new BuildHistory.Entry(1001, "1.26.36.1"));
        expected.put("1.26.31.1", new BuildHistory.Entry(1001, "1.26.36.1"));
        // Kept builds record their zips; the runner downloaded none, so their checksums are unknown
        Map<String, BuildHistory.Zip> noZips = Map.of("win", new BuildHistory.Zip(null, 0), "linux", new BuildHistory.Zip(null, 0));
        expected.put("1.26.36.1", new BuildHistory.Entry(1001, null).withZips(noZips));
        expected.put("1.26.40.8", new BuildHistory.Entry(2168, "1.26.45.1"));
        expected.put("1.26.42.1", new BuildHistory.Entry(2168, "1.26.45.1"));
        expected.put("1.26.45.1", new BuildHistory.Entry(2169, null).withZips(noZips));
        assertEquals(expected, record);
        assertFalse(Files.exists(root.resolve("data/bds/1.26.40.8")), "the replaced captures are removed");
        assertTrue(Files.exists(root.resolve("data/bds/1.26.45.1/spec/capture.json")));

        // Every build is recorded: another run checks nothing
        runs.clear();
        assertEquals(0, BuildHistory.check(root, list, builds, cache, runner, Integer.MAX_VALUE, 0));
        assertTrue(runs.isEmpty(), runs.toString());
    }

    @Test
    void aFailedRunStopsTheCheckAndRecordsNothing(@TempDir Path root, @TempDir Path cache) throws Exception {
        committed(root, "1.26.36.1", 1001);
        assertEquals(1, BuildHistory.check(root, list(), List.of("1.26.30.5", "1.26.31.1"), cache,
            (build, against, verifyOnly) -> new BuildHistory.Outcome(null, true, false), Integer.MAX_VALUE, 0));
        assertTrue(BuildHistory.read(root).isEmpty());
    }

    private static void committed(Path root, String build, int protocol) throws IOException {
        Path spec = root.resolve("data/bds").resolve(build).resolve("spec");
        Files.createDirectories(spec);
        Files.writeString(spec.resolve("capture.json"), "{\"bdsVersion\": \"" + build + "\", \"protocol\": " + protocol + "}");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Comparing captures

    @Test
    void capturesCompareWithoutVersionStamps() throws IOException {
        byte[] a = """
            {"_generated": "x", "bdsVersion": "1.26.30.5", "protocol": 1000, "shapes": [1, 2]}
            """.getBytes(StandardCharsets.UTF_8);
        byte[] b = "{\r\n  \"bdsVersion\": \"1.26.31.1\",\r\n  \"protocol\": 1001,\r\n  \"shapes\": [1, 2]\r\n}\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] c = """
            {"bdsVersion": "1.26.31.1", "shapes": [2, 1]}
            """.getBytes(StandardCharsets.UTF_8);
        assertTrue(BuildHistory.sameData(a, b));
        assertTrue(BuildHistory.sameData(gzip(a), b), "gzip is unpacked");
        assertFalse(BuildHistory.sameData(a, c));
        // An embedded capture has its own stamps; the data beside them still counts
        assertTrue(BuildHistory.sameData("{\"packet\": {\"bdsVersion\": \"1.26.30.5\", \"n\": 1}}".getBytes(StandardCharsets.UTF_8),
            "{\"packet\": {\"bdsVersion\": \"1.26.31.1\", \"n\": 1}}".getBytes(StandardCharsets.UTF_8)));
        assertFalse(BuildHistory.sameData("{\"packet\": [{\"n\": 1}]}".getBytes(StandardCharsets.UTF_8),
            "{\"packet\": [{\"n\": 2}]}".getBytes(StandardCharsets.UTF_8)));
        assertTrue(BuildHistory.sameData("ERROR x\r\n".getBytes(StandardCharsets.UTF_8), "ERROR x\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void stampsAreLeftOutOnlyWhereCapturesPutThem() throws IOException {
        // A protocol or version deeper in the data is data: a component field, an entry of a list
        assertFalse(BuildHistory.sameData("{\"blocks\": {\"a\": {\"protocol\": 1}}}".getBytes(StandardCharsets.UTF_8),
            "{\"blocks\": {\"a\": {\"protocol\": 2}}}".getBytes(StandardCharsets.UTF_8)));
        assertFalse(BuildHistory.sameData("[{\"formatVersion\": \"1.26.30\"}]".getBytes(StandardCharsets.UTF_8),
            "[{\"formatVersion\": \"1.26.31\"}]".getBytes(StandardCharsets.UTF_8)));
        assertFalse(BuildHistory.sameData("{\"packet\": {\"blocks\": {\"bdsVersion\": \"1\"}}}".getBytes(StandardCharsets.UTF_8),
            "{\"packet\": {\"blocks\": {\"bdsVersion\": \"2\"}}}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theFormatVersionOfThePacksIsAStamp(@TempDir Path root) throws IOException {
        Path a = root.resolve("a");
        Path b = root.resolve("b");
        Files.createDirectories(a);
        Files.createDirectories(b);
        Files.writeString(a.resolve("capture.json"), "{\"bdsVersion\": \"1.26.30.5\", \"formatVersion\": \"1.26.30\"}");
        Files.writeString(b.resolve("capture.json"), "{\"bdsVersion\": \"1.26.31.1\", \"formatVersion\": \"1.26.31\"}");
        Files.writeString(a.resolve("files.json"), "{\"files\": {\"s.json\": {\"format_version\": \"1.26.30\", \"size\": 1}}}");
        Files.writeString(b.resolve("files.json"), "{\"files\": {\"s.json\": {\"format_version\": \"1.26.31\", \"size\": 1}}}");
        assertTrue(BuildHistory.sameData(a.resolve("capture.json"), b.resolve("capture.json")));
        assertFalse(BuildHistory.sameData(a.resolve("files.json"), b.resolve("files.json")));
        assertTrue(BuildHistory.sameData(a.resolve("files.json"), BuildHistory.formatVersion(a), b.resolve("files.json"), BuildHistory.formatVersion(b)));
        assertEquals("Final json version '<format version>' raised warnings",
            BuildHistory.withoutFormatVersion("Final json version '1.26.31' raised warnings", "1.26.31"));
        // Only the quoted version: a build number that starts with it stays
        assertEquals("\"1.26.31.1\"", BuildHistory.withoutFormatVersion("\"1.26.31.1\"", "1.26.31"));
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
        }
        return out.toByteArray();
    }

    @Test
    void recipesOfAnExperimentRecordOnlyCountWhenTheEarlierRecordHasThem() throws IOException {
        String without = "{\"bdsVersion\":\"1\",\"toggles\":{\"t\":{\"status\":\"current\",\"content\":{\"itemsAdded\":[]}}}}";
        String with = without.replace("\"itemsAdded\":[]", "\"itemsAdded\":[],\"recipes\":{\"recipes added\":[\"shaped x\"]}");
        byte[] earlier = without.replace("\"1\"", "\"0\"").getBytes(StandardCharsets.UTF_8);
        assertTrue(BuildHistory.sameExperimentRecord(with.getBytes(StandardCharsets.UTF_8), earlier));
        assertFalse(BuildHistory.sameExperimentRecord(with.getBytes(StandardCharsets.UTF_8), with.replace("shaped x", "shaped y").getBytes(StandardCharsets.UTF_8)));
        assertFalse(BuildHistory.sameExperimentRecord(without.replace("[]", "[\"a\"]").getBytes(StandardCharsets.UTF_8), earlier));
    }
}
