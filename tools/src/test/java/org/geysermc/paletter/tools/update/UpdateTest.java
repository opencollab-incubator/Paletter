package org.geysermc.paletter.tools.update;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.tools.bds.BdsDump;
import org.geysermc.paletter.tools.data.Importer;
import org.geysermc.paletter.tools.data.Diff;
import org.geysermc.paletter.tools.data.WireShapes;
import org.geysermc.paletter.tools.model.Model;
import java.util.List;
import java.util.Map;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void aBundledBuildIsVerifiedAgainstItsOwnCapture() {
        List<String> committed = List.of("1.26.45.1", "1.26.52.3", "1.26.63.1");
        // Already bundled: its own committed capture, not the build before
        assertEquals("1.26.63.1", Update.baseline("1.26.63.1", committed));
        assertEquals("1.26.52.3", Update.baseline("1.26.52.3", committed));
        // Not bundled yet: the newest older build, what changed since
        assertEquals("1.26.63.1", Update.baseline("1.26.70.24", committed));
        assertEquals("1.26.52.3", Update.baseline("1.26.53.1", committed));
        assertEquals(null, Update.baseline("1.26.30.1", committed));
    }

    @Test
    void onlyBehaviourGettersOfTheInventoryNeedADecision() {
        assertEquals("BlockPermutation.getAllStates()", Update.behaviourGetter("+ Script API ScriptModuleMinecraft ScriptBlockPermutation getAllStates()"));
        assertEquals("BlockMapColorComponent.tintMethod", Update.behaviourGetter("- Script API ScriptModuleMinecraft ScriptBlockMapColorComponent get tintMethod"));
        assertEquals("BookItemComponent._getTitle", Update.behaviourGetter("+ Script API ScriptModuleMinecraft ScriptBookItemComponent get _getTitle"));
        assertEquals(null, Update.behaviourGetter("+ Script API ScriptModuleMinecraft ScriptBlockPermutation matches()"));
        assertEquals(null, Update.behaviourGetter("+ Script API ScriptModuleMinecraft ScriptBlockCustomComponentStepOnAfterEvent get mEntity"));
        assertEquals(null, Update.behaviourGetter("+ Script API ScriptModuleMinecraft ScriptWorld getDimension()"));
        assertEquals(null, Update.behaviourGetter("+ block component minecraft:geometry key culling"));
        // No value of the type or state: ids, runtime components, methods that take an argument, live objects
        assertEquals(null, Update.behaviourGetter("+ Script API ScriptModuleMinecraft ScriptBlockEntityStorageComponent get mUniqueId"));
        assertEquals(null, Update.behaviourGetter("+ Script API ScriptModuleMinecraft ScriptBlockRecipeProcessingComponent get getInputSlotCount"));
        assertEquals(null, Update.behaviourGetter("+ Script API ScriptModuleMinecraft ScriptBlockPermutation getState()"));
        assertEquals(null, Update.behaviourGetter("+ Script API ScriptModuleMinecraft ScriptBlock get getDimension"));
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
    void uncoveredWirePathsAreClassified() throws Exception {
        Path root = Path.of(System.getProperty("paletter.root", ".."));
        var mappings = JsonParser.parseString(Files.readString(root.resolve("data/model/mappings.json"))).getAsJsonObject();
        var model = Model.read(root.resolve("data/model/components.json"));
        var classified = WireShapes.classify(List.of("block:block_definition blockTags", "item:item_properties mining_speed",
            "block:minecraft:not_in_the_docs offset", "item:minecraft:food unknown_key"), mappings, model);
        assertEquals(4, classified.size(), classified.toString());
        assertEquals(List.of("item:minecraft:food unknown_key"), classified.get(WireShapes.UNCLASSIFIED));
        assertTrue(new WireShapes().uncovered(mappings).isEmpty());
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

    @Test
    void theExitCodeIsWrittenToTheWorkDirectory(@TempDir Path work) throws Exception {
        // Gradle's run task reports every non-zero exit as 1: the workflow reads the file to tell decisions from failures
        Report report = new Report("t");
        assertEquals(0, Update.exitCode(report, false, work));
        assertEquals("0\n", Files.readString(work.resolve(Update.EXIT_CODE)));

        report.check(Report.Status.DIFF, "Blocks", "1 new");
        report.action("decide it");
        assertEquals(Update.EXIT_TODOS, Update.exitCode(report, false, work));
        assertEquals(Update.EXIT_TODOS + "\n", Files.readString(work.resolve(Update.EXIT_CODE)));
        // A verification has no decisions, only differences
        assertEquals(1, Update.exitCode(report, true, work));
        assertEquals("1\n", Files.readString(work.resolve(Update.EXIT_CODE)));

        report.check(Report.Status.FAIL, "Network capture", "no answer");
        assertEquals(1, Update.exitCode(report, false, work));
        assertEquals("1\n", Files.readString(work.resolve(Update.EXIT_CODE)));
    }

    @Test
    void aCaptureThatRewritesTheSameBytesChangesNothing(@TempDir Path root) throws Exception {
        Path spec = root.resolve("data/bds/1.26.70.1/spec");
        Path mappings = root.resolve("data/model/mappings.json");
        Files.createDirectories(spec);
        Files.createDirectories(mappings.getParent());
        Files.writeString(spec.resolve("capture.json"), "{}\n");
        Files.writeString(mappings, "{\"a\": 1}\n");

        // What an update in update mode does: the capture and the inference write their files again, byte for byte the same
        Report report = new Report("t");
        Update.track(report, root, List.of(spec), "spec capture", () -> Files.writeString(spec.resolve("capture.json"), "{}\n"));
        Update.track(report, root, List.of(mappings), "inferred", () -> Files.writeString(mappings, "{\"a\": 1}\n"));
        report.check(Report.Status.INFO, "Spec capture", "`data/bds/1.26.70.1/spec`");
        assertEquals(List.of(), report.repoChanges);
        assertFalse(report.differs());
        assertEquals("OK: no changes", report.verdict());

        Update.track(report, root, List.of(spec), "spec capture", () -> {
            Files.writeString(spec.resolve("capture.json"), "{\"protocol\": 1}\n");
            Files.writeString(spec.resolve("variants.json"), "[]\n");
            return null;
        });
        Update.track(report, root, List.of(mappings), "inferred", () -> {
            Files.delete(mappings);
            return null;
        });
        assertEquals(List.of("update `data/bds/1.26.70.1/spec/capture.json` (spec capture)",
            "add `data/bds/1.26.70.1/spec/variants.json` (spec capture)", "remove `data/model/mappings.json` (inferred)"), report.repoChanges);
        assertEquals("Changed: 3 repository files changed", report.verdict());
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
