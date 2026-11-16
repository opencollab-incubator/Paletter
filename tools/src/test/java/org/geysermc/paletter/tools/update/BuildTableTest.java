package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.Versions;
import org.geysermc.paletter.tools.data.Importer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link BuildTable}: what the record of release builds says per bundled data version. */
class BuildTableTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));
    private static final List<Importer.IndexEntry> INDEX = List.of(new Importer.IndexEntry("26.30", 1001, false),
        new Importer.IndexEntry("26.40", 2168, false), new Importer.IndexEntry("26.60", 2225, false),
        new Importer.IndexEntry("26.70", 2259, true));

    private static List<String> verified(List<BuildTable.Row> rows) {
        return BuildTable.resource(rows).lines().filter(line -> !line.startsWith("#")).toList();
    }

    @Test
    void buildsBelongToTheDataVersionOfTheirProtocol() {
        Map<String, BuildHistory.Entry> record = new TreeMap<>(Versions.ORDER);
        record.put("1.26.20.1", new BuildHistory.Entry(900, null));
        record.put("1.26.30.5", new BuildHistory.Entry(1001, "1.26.36.1"));
        record.put("1.26.40.8", new BuildHistory.Entry(2168, "1.26.45.1", BuildHistory.FROM_LIST, null,
            List.of("BDS inventory: 1.26.45.1 has no inventory.json")));
        record.put("1.26.45.1", new BuildHistory.Entry(2169, null));
        // A kept build the record lacks: captures that update committed
        Map<String, Integer> captured = Map.of("1.26.36.1", 1001, "1.26.45.1", 2169);
        List<BuildTable.Row> rows = BuildTable.rows(record, captured, INDEX);
        assertEquals(List.of("1.26.20.1", "1.26.30.5", "1.26.36.1", "1.26.40.8", "1.26.45.1"), rows.stream().map(BuildTable.Row::build).toList());
        assertEquals(List.of(BuildTable.Status.NOT_SERVED, BuildTable.Status.IDENTICAL, BuildTable.Status.IMPORTED, BuildTable.Status.IDENTICAL,
            BuildTable.Status.IMPORTED), rows.stream().map(BuildTable.Row::status).toList());
        assertEquals(List.of("26.30 1.26.30.5 1001", "26.30 1.26.36.1 1001", "26.40 1.26.40.8 2168", "26.40 1.26.45.1 2169"), verified(rows));
        String table = BuildTable.table(rows, INDEX);
        // What a run could not compare stays with the build
        assertTrue(table.contains("| 1.26.40.8 | 2168 | 26.40 | identical to 1.26.45.1 | BDS inventory: 1.26.45.1 has no inventory.json | BDS-Versions |"), table);
        assertTrue(table.contains("| 26.40 | 2168 | 2168, 2169 |"), table);
        assertTrue(table.contains("## Gaps\n\nNone"), table);
        assertFalse(table.contains("| 26.70 |"), "previews have no release builds");
    }

    @Test
    void aBuildAHistoryCheckKeptIsAGapNeverTheDataVersionsBuild() {
        // 1.26.63.1 was imported; 1.26.65.1 is newer, kept by a history check because it differs, and 1.26.66.1 equals it
        Map<String, BuildHistory.Entry> record = new TreeMap<>(Versions.ORDER);
        record.put("1.26.63.1", new BuildHistory.Entry(2225, null));
        record.put("1.26.65.1", new BuildHistory.Entry(2226, null, BuildHistory.FROM_LINKS_API, BuildHistory.KEPT_BY_HISTORY_CHECK, List.of()));
        record.put("1.26.66.1", new BuildHistory.Entry(2226, "1.26.65.1"));
        List<BuildTable.Row> rows = BuildTable.rows(record, Map.of("1.26.63.1", 2225, "1.26.65.1", 2226), INDEX);
        assertEquals(List.of(BuildTable.Status.IMPORTED, BuildTable.Status.GAP, BuildTable.Status.GAP), rows.stream().map(BuildTable.Row::status).toList());
        assertEquals(List.of("26.60 1.26.63.1 2225"), verified(rows));
        List<String> gaps = BuildTable.gaps(rows);
        assertEquals(2, gaps.size());
        assertTrue(gaps.getFirst().startsWith("1.26.65.1 (protocol 2226, data version 26.60): kept, differs from 1.26.63.1"), gaps.getFirst());
        assertTrue(BuildTable.table(rows, INDEX).contains("each needs a data version of its own:\n\n- 1.26.65.1"));
    }

    @Test
    void theCommittedListMatchesTheRecord() throws IOException {
        Path resources = ROOT.resolve("paletter/src/main/resources/paletter");
        List<Importer.IndexEntry> index = Importer.readIndex(resources);
        List<BuildTable.Row> rows = BuildTable.rows(BuildHistory.read(ROOT), BuildHistory.capturedReleases(ROOT), index);
        assertEquals(BuildTable.resource(rows), Files.readString(resources.resolve(BuildTable.RESOURCE), StandardCharsets.UTF_8).replace("\r\n", "\n"));
        // Every bundled release data version has at least the build it was imported from
        Set<String> versions = verified(rows).stream().map(line -> line.split(" ")[0]).collect(Collectors.toSet());
        for (Importer.IndexEntry entry : index) {
            if (!entry.preview()) {
                assertTrue(versions.contains(entry.name()), entry.name() + " has no verified build");
            }
        }
        assertTrue(BuildHistory.problems(ROOT, BuildHistory.read(ROOT), BuildHistory.capturedReleases(ROOT)).isEmpty());
    }
}
