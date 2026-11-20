package dev.opencollab.paletter.tools.data;

import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.DataVersion;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.data.VerifiedBuild;
import dev.opencollab.paletter.tools.behaviour.BehaviourSources;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paletter's compiled palette of each bundled release against the CloudburstMC/Data files of the commit that
 * {@code data/behaviour/REPORT.md} lists for it ({@code test-data/}, see its {@code SOURCE.md}), with the comparison of
 * {@code verify --against}: runtime ids and hashes of block states (also cb/data's own {@code network_id} and
 * {@code name_hash}), positions, state order within blocks, item runtime ids, versions and components, data driven blocks,
 * and what either side lacks, by name. A mismatch is a finding about the data, not a reason to loosen the test. The
 * report of each version goes to {@code build/cbdata-report-<version>.md}.
 */
class CloudburstDataTest {
    private static final String CREATIVE = "Creative layout";
    /** A row of the table in {@code test-data/SOURCE.md}: {@code | 26.60 | 1.26.63.1 | `CloudburstMC/Data@<commit>` ...}. */
    private static final Pattern ROW = Pattern.compile("^\\| (\\d+\\.\\d+) \\| (\\d+(?:\\.\\d+){3}) \\| `CloudburstMC/Data@([0-9a-f]{40})`", Pattern.MULTILINE);

    /** What SOURCE.md says about one version's test data. */
    private record Snapshot(String build, String commit) {
    }

    private static Map<String, Snapshot> snapshots() throws Exception {
        Path folder = Path.of(CloudburstDataTest.class.getResource("/test-data/SOURCE.md").toURI()).getParent();
        Map<String, Snapshot> result = new TreeMap<>();
        Matcher row = ROW.matcher(Files.readString(folder.resolve("SOURCE.md")));
        while (row.find()) {
            result.put(row.group(1), new Snapshot(row.group(2), row.group(3)));
        }
        return result;
    }

    /**
     * The versions to compare: the {@code test-data/<version>/} folders that are also bundled ({@code versions.txt}). A retired
     * version keeps its folder for nobody and is skipped, a bundled version without test data is not compared.
     */
    private static List<String> versions() throws Exception {
        Path folder = Path.of(CloudburstDataTest.class.getResource("/test-data/SOURCE.md").toURI()).getParent();
        Set<String> bundled = Paletter.bundled().versions().stream().map(DataVersion::name).collect(Collectors.toSet());
        try (Stream<Path> folders = Files.list(folder)) {
            return folders.filter(Files::isDirectory).map(path -> path.getFileName().toString()).filter(bundled::contains).sorted().toList();
        }
    }

    /**
     * What the comparison found and nobody has resolved, per version: the sections that differ. Any other section differing, or
     * one of these no longer differing, fails the test. Nothing is known at the moment: with every experiment on (as cb/data
     * dumps) blocks, hashes, items and components agree for all four versions; only the creative layout differs.
     */
    private static final Map<String, Set<String>> KNOWN_FINDINGS = Map.of();

    @TempDir
    Path directory;

    @TestFactory
    Stream<DynamicTest> paletteMatchesCloudburstData() throws Exception {
        List<String> versions = versions();
        assertFalse(versions.isEmpty(), "no test-data folder of a bundled version");
        return versions.stream().map(version -> DynamicTest.dynamicTest(version, () -> compare(version)));
    }

    /** The test data is a snapshot of the data the bundled version was imported from: another build means it is stale. */
    @TestFactory
    Stream<DynamicTest> theTestDataIsOfTheBuildTheBundledDataIsFrom() throws Exception {
        Map<String, Snapshot> snapshots = snapshots();
        Paletter paletter = Paletter.bundled();
        return versions().stream().map(version -> DynamicTest.dynamicTest(version, () -> {
            Snapshot snapshot = snapshots.get(version);
            assertTrue(snapshot != null, "test-data/SOURCE.md has no row for " + version + " (| version | BDS build | commit |)");
            List<String> builds = paletter.verifiedBuilds(paletter.version(version)).stream().map(VerifiedBuild::bdsVersion).toList();
            assertTrue(builds.contains(snapshot.build()), "The bundled data of " + version + " now comes from BDS " + builds + ", but test-data/" + version
                + " is from BDS " + snapshot.build() + ". The test data must be refreshed too: `git show <commit>:<file>` from a clone of CloudburstMC/Data "
                + "for each file of test-data/" + version + " (see test-data/SOURCE.md), then update the row of " + version + ".");
        }));
    }

    /**
     * The newest snapshot is the commit {@code data/behaviour/sources.json} pins: the pin moves with {@code behaviour.nbt}, and the
     * snapshot has to move with it (docs/updating.md, Pins).
     */
    @Test
    void theNewestSnapshotIsThePinnedCloudburstDataCommit() throws Exception {
        Map<String, Snapshot> snapshots = snapshots();
        // Versions sort by name: 26.60 after 26.50
        Snapshot newest = snapshots.get(versions().getLast());
        String pinned = BehaviourSources.read(Path.of(System.getProperty("paletter.root", ".."))).get(BehaviourSources.CLOUDBURST_DATA).commit();
        assertEquals(pinned, newest.commit(), "data/behaviour/sources.json pins CloudburstMC/Data@" + pinned + ", the newest test-data is of "
            + newest.commit() + ": refresh test-data from the pinned commit (see test-data/SOURCE.md), or the pin moved without it");
    }

    private void compare(String version) throws Exception {
        Path source = Path.of(getClass().getResource("/test-data/" + version + "/block_palette.nbt").toURI()).getParent();
        // CloudburstMC/Data dumps BDS with every experiment on
        Paletter paletter = Paletter.builder().allExperiments().build();
        Palette palette = paletter.vanilla(paletter.version(version));
        Path export = Files.createDirectory(directory.resolve("export-" + version));
        Export.write(palette, export);

        Diff diff = Diff.against(source, version, export);
        Path report = Path.of("build", "cbdata-report-" + version + ".md").toAbsolutePath();
        Files.createDirectories(report.getParent());
        Files.writeString(report, diff.markdown(1_000_000));

        // TODO: the creative layout is reported, not asserted. The compiled palette gives the stacks of items that place a
        // block (doors, seeds, crops, ...) a block state that cb/data's creative_items.json lacks. Which of the two BDS sends is
        // not known: check it against the BDS creative capture (data/bds/*/creative), then assert this section too.
        Set<String> expected = KNOWN_FINDINGS.getOrDefault(version, Set.of());
        diff.sections.values().stream().filter(section -> !section.title.equals(CREATIVE)).forEach(section -> {
            if (expected.contains(section.title)) {
                assertFalse(section.differences.isEmpty(), version + ": " + section.title + " no longer differs: remove it from KNOWN_FINDINGS");
            } else {
                assertTrue(section.differences.isEmpty(), version + ": " + section.title + " (see " + report + ")\n" + diff.markdown(40));
            }
        });
    }
}
