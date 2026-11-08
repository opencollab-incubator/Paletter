package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.bds.BdsDump;
import org.geysermc.paletter.tools.data.Importer;
import org.geysermc.paletter.tools.data.ShapeDiff;
import org.geysermc.paletter.tools.data.WireShapes;
import java.util.List;
import java.util.Map;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateTest {

    @Test
    void versionNames() {
        assertEquals("26.70", Update.versionName("1.26.70.23"));
        assertEquals("26.60", Update.versionName("1.26.63.1"));
        assertEquals("26.40", Update.versionName("1.26.45.1"));
    }

    @Test
    void canonicalDumpJsonIgnoresOrder() {
        String a = "{\"b\": {\"y\": [2, 1], \"x\": [\"b\", \"a\"]}, \"a\": 1}";
        String b = "{\"a\": 1, \"b\": {\"x\": [\"a\", \"b\"], \"y\": [1, 2]}}";
        assertEquals(BdsDump.canonicalJson(a), BdsDump.canonicalJson(b));
    }

    @Test
    void shapeChangesAreWireFormatChanges() {
        var before = shapes("value", "byte", "old", "int");
        var after = shapes("value", "short", "new", "string");
        ShapeDiff diff = ShapeDiff.of(before, after);
        assertEquals(3, diff.wireChanges.size(), diff.wireChanges.toString());
        assertTrue(diff.wireChanges.stream().anyMatch(c -> c.contains("value: type byte -> short")));
        assertTrue(diff.wireChanges.stream().anyMatch(c -> c.contains("old: field removed")));
        assertTrue(diff.wireChanges.stream().anyMatch(c -> c.contains("new: new field")));
        assertTrue(ShapeDiff.of(before, before).identical());
    }

    @Test
    void componentsComeAndGo() {
        var before = shapes("value", "byte");
        var after = Map.of("item:minecraft:other", Map.of("", "compound"));
        ShapeDiff diff = ShapeDiff.of(before, after);
        assertEquals(List.of("item:minecraft:other"), diff.newComponents);
        assertEquals(List.of("item:minecraft:test: no longer sent"), diff.wireChanges);
    }

    @Test
    void committedCapturesAreCoveredByTheMappings() throws Exception {
        Path root = Path.of(System.getProperty("paletter.root", ".."));
        var mappings = JsonParser.parseString(Files.readString(root.resolve("data/model/mappings.json"))).getAsJsonObject();
        var shapes = WireShapes.ofCapture(root.resolve("data/bds/1.26.63.1/addon"));
        assertFalse(shapes.components().isEmpty());
        List<String> uncovered = shapes.uncovered(mappings);
        var gaps = WireShapes.knownGaps(root.resolve("data/model"));
        assertTrue(gaps.containsAll(uncovered), "not covered and not a known gap: " + uncovered.stream().filter(u -> !gaps.contains(u)).toList());
        assertTrue(uncovered.containsAll(gaps), "known gaps that are covered now: " + gaps.stream().filter(g -> !uncovered.contains(g)).toList());
        var invented = new WireShapes();
        assertTrue(invented.uncovered(mappings).isEmpty());
    }

    @Test
    void previewFlagInIndex() throws Exception {
        Path directory = Files.createTempDirectory("paletter-index");
        Files.writeString(directory.resolve("versions.txt"), "# comment\n26.70 2250 preview\n26.60 2225\n");
        var entries = Importer.readIndex(directory);
        assertEquals("26.60", entries.get(0).name());
        assertFalse(entries.get(0).preview());
        assertTrue(entries.get(1).preview());
    }

    private static Map<String, Map<String, String>> shapes(String... pathsAndTypes) {
        Map<String, String> paths = new java.util.TreeMap<>();
        paths.put("", "compound");
        for (int i = 0; i < pathsAndTypes.length; i += 2) {
            paths.put(pathsAndTypes[i], pathsAndTypes[i + 1]);
        }
        return Map.of("item:minecraft:test", paths);
    }
}
