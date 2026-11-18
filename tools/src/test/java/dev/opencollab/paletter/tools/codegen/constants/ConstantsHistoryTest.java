package dev.opencollab.paletter.tools.codegen.constants;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The history file: one line per constant, and the checks of what it holds. */
class ConstantsHistoryTest {
    @Test
    void everyConstantIsOneLine() {
        JsonObject history = JsonParser.parseString("{\"blocks\": {\"A\": {\"identifier\": \"minecraft:a\"}, \"B\": {\"identifier\": \"minecraft:b\"}}}")
            .getAsJsonObject();

        String json = ConstantsHistory.toJson(history);

        assertTrue(json.startsWith("{\n  \"_generated\": "), json);
        assertTrue(json.endsWith("  \"blocks\": {\n    \"A\": {\"identifier\":\"minecraft:a\"},\n    \"B\": {\"identifier\":\"minecraft:b\"}\n  }\n}\n"), json);
    }

    @Test
    void missingOrMalformedEntriesNameTheirPlace() {
        JsonObject history = JsonParser.parseString("{\"states\": {\"X\": 1}, \"enums\": []}").getAsJsonObject();

        assertTrue(ConstantsHistory.object(history, "absent").isEmpty());
        assertThrows(IllegalStateException.class, () -> ConstantsHistory.object(history, "enums"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> ConstantsHistory.entry(history.getAsJsonObject("states").entrySet().iterator().next(), "states"));
        assertTrue(e.getMessage().contains("states.X is not an object"), e.getMessage());
        e = assertThrows(IllegalStateException.class, () -> ConstantsHistory.required(new JsonObject(), "since", "blocks.A"));
        assertEquals(ConstantsGenerator.HISTORY + ": blocks.A lacks \"since\"", e.getMessage());
    }
}
