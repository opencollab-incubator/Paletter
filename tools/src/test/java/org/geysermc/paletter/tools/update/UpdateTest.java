package org.geysermc.paletter.tools.update;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.tools.bds.BdsDump;
import org.geysermc.paletter.tools.data.Importer;
import org.geysermc.paletter.tools.data.Diff;
import org.geysermc.paletter.tools.data.WireShapes;
import org.geysermc.paletter.tools.model.infer.MappingInference;
import java.util.List;
import java.util.Map;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        Diff diff = Diff.shapes(before, after);
        assertEquals(3, diff.lines(Diff.WIRE_CHANGES).size(), diff.lines(Diff.WIRE_CHANGES).toString());
        assertTrue(diff.lines(Diff.WIRE_CHANGES).stream().anyMatch(c -> c.contains("value: type byte -> short")));
        assertTrue(diff.lines(Diff.WIRE_CHANGES).stream().anyMatch(c -> c.contains("old: field removed")));
        assertTrue(diff.lines(Diff.WIRE_CHANGES).stream().anyMatch(c -> c.contains("new: new field")));
        assertTrue(Diff.shapes(before, before).identical());
    }

    @Test
    void componentsComeAndGo() {
        var before = shapes("value", "byte");
        var after = Map.of("item:minecraft:other", Map.of("", "compound"));
        Diff diff = Diff.shapes(before, after);
        assertEquals(List.of("item:minecraft:other"), diff.lines(Diff.NEW_COMPONENTS));
        assertEquals(List.of("item:minecraft:test: no longer sent"), diff.lines(Diff.WIRE_CHANGES));
    }

    @Test
    void committedCapturesAreCoveredByTheMappings() throws Exception {
        Path root = Path.of(System.getProperty("paletter.root", ".."));
        var mappings = JsonParser.parseString(Files.readString(root.resolve("data/model/mappings.json"))).getAsJsonObject();
        var shapes = WireShapes.ofCapture(root.resolve("data/bds/1.26.63.1/addon"));
        assertFalse(shapes.components().isEmpty());
        List<String> uncovered = shapes.uncovered(mappings);
        var gaps = WireShapes.knownGaps(root.resolve("data/model")).stream().map(WireShapes.Gap::path).toList();
        assertTrue(gaps.containsAll(uncovered), "not covered and not a known gap: " + uncovered.stream().filter(u -> !gaps.contains(u)).toList());
        assertTrue(uncovered.containsAll(gaps), "known gaps that are covered now: " + gaps.stream().filter(g -> !uncovered.contains(g)).toList());
        var invented = new WireShapes();
        assertTrue(invented.uncovered(mappings).isEmpty());
    }

    @Test
    void aWireGapNeedsReasonBuildAndClosingCondition() throws Exception {
        Path model = Files.createTempDirectory("paletter-gaps");
        Files.writeString(model.resolve("wire-gaps.txt"), "# c\nitem:minecraft:a x | why | since 1.26.63.1 | closes with a capture\n");
        var gap = WireShapes.knownGaps(model).getFirst();
        assertEquals(new WireShapes.Gap("item:minecraft:a x", "why", "1.26.63.1", "a capture"), gap);
        for (String bad : List.of("item:minecraft:a x", "item:minecraft:a x | | since 1.26.63.1 | closes with y",
            "item:minecraft:a x | why | 1.26.63.1 | closes with y", "item:minecraft:a x | why | since 1.26.63.1 | y",
            "item:minecraft:a x | why | since 1.26.63.1 | closes with y | z")) {
            Files.writeString(model.resolve("wire-gaps.txt"), bad + "\n");
            assertThrows(IllegalArgumentException.class, () -> WireShapes.knownGaps(model), bad);
        }
    }

    @Test
    void everyFindingAndConstantHasItsEvidence() throws Exception {
        Path root = Path.of(System.getProperty("paletter.root", ".."));
        var problems = new MappingInference(root).gates();
        assertTrue(problems.isEmpty(), "findings or constants without evidence (data/model/evidence.json):\n  " + String.join("\n  ", problems));
    }

    @Test
    void valueChangesBehindTheSameShapeAreSeen() {
        var before = new WireShapes();
        before.addItem(NbtMap.builder().putCompound("minecraft:x", NbtMap.builder().putByte("a", (byte) 1).putInt("b", 5).build()).build());
        var after = new WireShapes();
        after.addItem(NbtMap.builder().putCompound("minecraft:x", NbtMap.builder().putByte("a", (byte) 1).putInt("b", 6).build()).build());
        assertTrue(Diff.shapes(before.components(), after.components()).identical());
        Diff diff = Diff.values(before.values(), after.values());
        assertEquals(1, diff.lines(Diff.VALUE_CHANGES).size());
        assertTrue(diff.lines(Diff.VALUE_CHANGES).getFirst().contains("item:minecraft:x b: new values [6]; values gone [5]"), diff.lines(Diff.VALUE_CHANGES).toString());
        assertTrue(Diff.values(before.values(), before.values()).identical());
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
