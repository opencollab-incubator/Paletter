package dev.opencollab.paletter.tools.data;

import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.Paletter;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

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
    private static final List<String> VERSIONS = List.of("26.30", "26.40", "26.50", "26.60");
    private static final String CREATIVE = "Creative layout";

    /**
     * What the comparison found and nobody has resolved, per version: the sections that differ. Any other section differing, or
     * one of these no longer differing, fails the test. Nothing is known at the moment: with every experiment on (as cb/data
     * dumps) blocks, hashes, items and components agree for all four versions; only the creative layout differs.
     */
    private static final Map<String, Set<String>> KNOWN_FINDINGS = Map.of();

    @TempDir
    Path directory;

    @TestFactory
    Stream<DynamicTest> paletteMatchesCloudburstData() {
        return VERSIONS.stream().map(version -> DynamicTest.dynamicTest(version, () -> compare(version)));
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
