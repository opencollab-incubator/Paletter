package org.geysermc.paletter.tools.model.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The rule model against the committed mappings, and the evaluation the encoders' checks rely on. */
class RuleTest {
    private static final Path MAPPINGS = Path.of(System.getProperty("paletter.root", "..")).resolve("data/model/mappings.json");

    @Test
    void everyCommittedRuleReadsAndWritesBackUnchanged() throws IOException {
        List<JsonObject> rules = new ArrayList<>();
        collect(JsonParser.parseString(Files.readString(MAPPINGS, StandardCharsets.UTF_8)), rules);
        for (JsonObject json : rules) {
            assertEquals(json.toString(), Rule.of(json).toJson().toString());
        }
    }

    private static void collect(JsonElement e, List<JsonObject> rules) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("rules") && o.get("rules").isJsonArray()) {
                o.getAsJsonArray("rules").forEach(r -> rules.add(r.getAsJsonObject()));
            }
            o.entrySet().forEach(entry -> collect(entry.getValue(), rules));
        } else if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(element -> collect(element, rules));
        }
    }

    private static Rule rule(String json) {
        return Rule.of(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void evaluatesTheWayTheEncoderWrites() {
        JsonElement half = JsonParser.parseString("1.5");
        assertEquals("30 (int)", rule("{\"kind\": \"linear\", \"scale\": 20.0, \"offset\": 0.0, \"nbt\": \"int\"}").evaluate(half));
        assertEquals("1 (byte)", rule("{\"kind\": \"identity\", \"nbt\": \"byte\"}").evaluate(JsonParser.parseString("true")));
        assertEquals("2 (int)", rule("{\"kind\": \"table\", \"table\": {\"drink\": \"2\"}, \"nbt\": \"int\"}").evaluate(JsonParser.parseString("\"drink\"")));
        assertEquals("minecraft:pig<> (string)", rule("{\"kind\": \"affix\", \"prefix\": \"\", \"suffix\": \"<>\", \"nbt\": \"string\"}")
            .evaluate(JsonParser.parseString("\"pig\"")));
        assertEquals("6 (int)", rule("{\"kind\": \"enum-table\", \"table\": {\"spear\": \"6\"}, \"nbt\": \"int\"}").evaluate(JsonParser.parseString("\"spear\"")));
        assertNull(rule("{\"kind\": \"enum-table\", \"table\": {\"spear\": \"6\"}, \"nbt\": \"int\"}").evaluate(JsonParser.parseString("\"brush\"")));
        // no NBT type, an element of a list, a constant: nothing to evaluate
        assertNull(rule("{\"kind\": \"identity\"}").evaluate(half));
        assertNull(rule("{\"kind\": \"identity\", \"element\": 0, \"nbt\": \"float\"}").evaluate(half));
        assertNull(rule("{\"kind\": \"constant\", \"value\": \"1\", \"nbt\": \"int\"}").evaluate(half));
    }

    @Test
    void anEnumTableHasACaseForEveryValueAndNoFallback() {
        RecordingEmitter e = new RecordingEmitter();
        rule("{\"kind\": \"enum-table\", \"table\": {\"eat\": \"1\", \"none\": \"0\", \"hide\": \"<not sent>\"}, \"nbt\": \"int\"}").emit(e);
        assertEquals("""
            switch (key) {
              case "eat" -> write("1 (int)");
              case "hide" -> write("<not sent>");
              case "none" -> write("0 (int)");
              default -> throw new java.lang.IllegalStateException("path is not a value of its enum: " + key);
            }""", e.written());
    }
}
