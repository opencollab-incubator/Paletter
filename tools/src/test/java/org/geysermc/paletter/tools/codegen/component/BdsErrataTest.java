package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BdsErrataTest {
    static final String MODEL = """
        {"components": [{"id": "block:minecraft:geo", "fields": [
          {"path": "rotation", "types": [{"type": "string"}]},
          {"path": "items", "types": [{"type": "array", "items": {"type": "object"}}, {"type": "object"}]}
        ]}]}""";
    static final JsonObject FEEDBACK = JsonParser.parseString("""
        {"block:minecraft:geo": {"rotation": ["object"], "items[]": ["object"]}}""").getAsJsonObject();

    @Test
    void theAddonKeepsTheDocsTypeSoBdsIsAskedAgain() {
        JsonObject model = JsonParser.parseString(MODEL).getAsJsonObject();
        BdsErrata.apply(model, FEEDBACK, new JsonObject(), false);
        assertEquals("[{\"type\":\"object\"},{\"type\":\"string\"}]", types(model, 0));
        assertEquals("[{\"type\":\"object\"},{\"type\":\"array\",\"items\":{\"type\":\"object\"}}]", types(model, 1));
    }

    @Test
    void theApiOffersOnlyTheObjectWhereTheDocsHadNone() {
        JsonObject model = JsonParser.parseString(MODEL).getAsJsonObject();
        BdsErrata.apply(model, FEEDBACK, new JsonObject(), true);
        assertEquals("[{\"type\":\"object\"}]", types(model, 0));
        assertEquals("[{\"type\":\"object\"},{\"type\":\"array\",\"items\":{\"type\":\"object\"}}]", types(model, 1), "the list stays, second");
    }

    private static String types(JsonObject model, int field) {
        return model.getAsJsonArray("components").get(0).getAsJsonObject().getAsJsonArray("fields").get(field).getAsJsonObject()
            .getAsJsonArray("types").toString();
    }
}
