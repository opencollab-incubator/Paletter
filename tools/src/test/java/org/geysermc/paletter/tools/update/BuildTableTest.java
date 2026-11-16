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
        new Importer.IndexEntry("26.40", 2168, false), new Importer.IndexEntry("26.70", 2259, true));

    @Test
    void buildsBelongToTheDataVersionOfTheirProtocol() {
        Map<String, BuildHistory.Entry> record = new TreeMap<>(Versions.ORDER);
        record.put("1.26.20.1", new BuildHistory.Entry(900, null));
        record.put("1.26.30.5", new BuildHistory.Entry(1001, "1.26.36.1"));
        record.put("1.26.40.8", new BuildHistory.Entry(2168, "1.26.45.1"));
        record.put("1.26.45.1", new BuildHistory.Entry(2169, null));
        // A kept build the record lacks: captures that update committed
        Map<String, Integer> captured = Map.of("1.26.36.1", 1001, "1.26.45.1", 2169);
        List<BuildTable.Row> rows = BuildTable.rows(record, captured, INDEX);
        assertEquals(List.of("1.26.20.1", "1.26.30.5", "1.26.36.1", "1.26.40.8", "1.26.45.1"), rows.stream().map(BuildTable.Row::build).toList());
        assertEquals(BuildTable.Status.NOT_SERVED, rows.get(0).status());
        assertEquals(BuildTable.Status.IDENTICAL, rows.get(1).status());
        assertEquals(BuildTable.Status.KEPT, rows.get(2).status());
        assertEquals("26.40", rows.get(3).dataVersion());
        assertEquals("# <data version> <BDS build> <protocol>", BuildTable.resource(rows).lines().findFirst().orElseThrow().substring(0, 39));
        assertEquals(List.of("26.30 1.26.30.5 1001", "26.30 1.26.36.1 1001", "26.40 1.26.40.8 2168", "26.40 1.26.45.1 2169"),
            BuildTable.resource(rows).lines().filter(line -> !line.startsWith("#")).toList());
        String table = BuildTable.table(rows, INDEX);
        assertTrue(table.contains("| 1.26.40.8 | 2168 | 26.40 | identical to 1.26.45.1 | BDS-Versions |"), table);
        assertTrue(table.contains("| 26.40 | 2168 | 2168, 2169 |"), table);
        assertFalse(table.contains("| 26.70 |"), "previews have no release builds");
    }

    @Test
    void aSecondKeptBuildOfAWindowIsNotVerified() {
        Map<String, BuildHistory.Entry> record = new TreeMap<>(Versions.ORDER);
        record.put("1.26.40.8", new BuildHistory.Entry(2168, null));
        record.put("1.26.42.1", new BuildHistory.Entry(2168, "1.26.40.8"));
        record.put("1.26.45.1", new BuildHistory.Entry(2169, null));
        List<BuildTable.Row> rows = BuildTable.rows(record, Map.of(), INDEX);
        assertEquals(List.of(BuildTable.Status.OTHER, BuildTable.Status.OTHER, BuildTable.Status.KEPT), rows.stream().map(BuildTable.Row::status).toList());
        assertTrue(rows.getFirst().detail().contains("needs a data version of its own"), rows.getFirst().detail());
        assertEquals(List.of("26.40 1.26.45.1 2169"), BuildTable.resource(rows).lines().filter(line -> !line.startsWith("#")).toList());
        String table = BuildTable.table(rows, INDEX);
        assertTrue(table.contains("| 26.40 | 2168 | 2169 (no verified build has the first protocol) |"), table);
    }

    @Test
    void theCommittedListMatchesTheRecord() throws IOException {
        Path resources = ROOT.resolve("paletter/src/main/resources/paletter");
        List<Importer.IndexEntry> index = Importer.readIndex(resources);
        List<BuildTable.Row> rows = BuildTable.rows(BuildHistory.read(ROOT), BuildHistory.capturedReleases(ROOT), index);
        assertEquals(BuildTable.resource(rows), Files.readString(resources.resolve(BuildTable.RESOURCE), StandardCharsets.UTF_8).replace("\r\n", "\n"));
        // Every bundled release data version has at least the build it stands for
        Set<String> verified = BuildTable.resource(rows).lines().filter(line -> !line.startsWith("#"))
            .map(line -> line.split(" ")[0]).collect(Collectors.toSet());
        for (Importer.IndexEntry entry : index) {
            if (!entry.preview()) {
                assertTrue(verified.contains(entry.name()), entry.name() + " has no verified build");
            }
        }
    }
}
