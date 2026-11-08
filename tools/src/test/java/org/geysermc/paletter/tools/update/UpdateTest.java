package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.bds.BdsDump;
import org.geysermc.paletter.tools.data.Importer;
import org.geysermc.paletter.tools.data.ShapeDiff;
import com.google.gson.JsonObject;
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
        JsonObject before = spec("{\"type\": \"compound\", \"fields\": {\"value\": {\"type\": \"byte\"}, \"old\": {\"type\": \"int\"}}}");
        JsonObject after = spec("{\"type\": \"compound\", \"fields\": {\"value\": {\"type\": \"short\"}, \"new\": {\"type\": \"string\", \"optional\": true}}}");
        ShapeDiff diff = ShapeDiff.of(before, after);
        assertEquals(3, diff.wireChanges.size(), diff.wireChanges.toString());
        assertTrue(diff.wireChanges.stream().anyMatch(c -> c.contains("value: type byte -> short")));
        assertTrue(diff.wireChanges.stream().anyMatch(c -> c.contains("old: field removed")));
        assertTrue(diff.wireChanges.stream().anyMatch(c -> c.contains("new: new field")));
        assertTrue(ShapeDiff.of(before, before).identical());
    }

    @Test
    void optionalityIsANoteNotAWireChange() {
        JsonObject before = spec("{\"type\": \"compound\", \"fields\": {\"value\": {\"type\": \"byte\"}}}");
        JsonObject after = spec("{\"type\": \"compound\", \"fields\": {\"value\": {\"type\": \"byte\", \"optional\": true}}}");
        ShapeDiff diff = ShapeDiff.of(before, after);
        assertTrue(diff.wireChanges.isEmpty());
        assertEquals(1, diff.notes.size());
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

    private static JsonObject spec(String shape) {
        return JsonParser.parseString("{\"components\": {\"item:minecraft:test\": {\"shape\": " + shape + "}}}").getAsJsonObject();
    }
}
