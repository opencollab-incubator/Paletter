package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BdsErrataTest {
    static final String MODEL = """
        {"components": [{"id": "block:minecraft:geo", "fields": [
          {"path": "rotation", "types": [{"type": "string"}]},
          {"path": "items", "types": [{"type": "array", "items": {"type": "object"}}, {"type": "object"}]},
          {"path": "faces", "types": [{"type": "string"}]},
          {"path": "allowed", "types": [{"type": "object"}], "defaultValue": ["up", "down"]},
          {"path": "stops", "types": [{"type": "object"}], "defaultValue": []}
        ]}]}""";
    static final JsonObject FEEDBACK = JsonParser.parseString("""
        {"block:minecraft:geo": {"rotation": ["object"], "items[]": ["object"], "faces": ["array"], "allowed": ["array"], "stops": ["array"]}}""").getAsJsonObject();

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

    @Test
    void expectedAnArrayPutsAListOfTheDocumentedElementsFirst() {
        JsonObject model = JsonParser.parseString(MODEL).getAsJsonObject();
        BdsErrata.apply(model, FEEDBACK, new JsonObject(), false);
        assertEquals("[{\"type\":\"array\",\"items\":{\"type\":\"string\"}},{\"type\":\"string\"}]", types(model, 2), "the docs' scalar");
        assertEquals("[{\"type\":\"array\",\"items\":{\"type\":\"string\"}},{\"type\":\"object\"}]", types(model, 3), "the default's elements");
        assertEquals("[{\"type\":\"array\",\"items\":{\"type\":\"any\"}},{\"type\":\"object\"}]", types(model, 4), "nothing says which");
    }

    private static String types(JsonObject model, int field) {
        return model.getAsJsonArray("components").get(0).getAsJsonObject().getAsJsonArray("fields").get(field).getAsJsonObject()
            .getAsJsonArray("types").toString();
    }
}
