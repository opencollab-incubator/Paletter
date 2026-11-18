package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.codegen.component.ComponentGenerator.Inputs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A rule of every element of a list of objects (an affine rule over {@code origin} and {@code size} of each box) is written in the loop over the elements. */
class ListElementRuleTest {
    private static JsonObject field(String path) {
        return ComponentGeneratorTest.json("{\"path\": \"" + path + "\", \"types\": [{\"type\": \"number\"}], \"defaultValue\": 0, \"required\": false, "
            + "\"enumValues\": [], \"examples\": [], \"doc\": \"\"}");
    }

    private static Inputs inputs() {
        JsonObject components = ComponentGeneratorTest.components();
        for (var component : components.getAsJsonArray("components")) {
            if (component.getAsJsonObject().get("id").getAsString().equals("item:test:widget")) {
                JsonArray fields = component.getAsJsonObject().getAsJsonArray("fields");
                fields.add(field("entries[].origin"));
                fields.add(field("entries[].size"));
            }
        }
        JsonObject mappings = ComponentGeneratorTest.mappings();
        JsonObject fields = mappings.getAsJsonObject("components").getAsJsonObject("item:test:widget").getAsJsonObject("fields");
        fields.getAsJsonObject("entries").getAsJsonArray("rules").add(ComponentGeneratorTest.json(
            "{\"kind\": \"affine\", \"terms\": {\"origin\": -1, \"size\": -1}, \"constant\": 8.0, \"elements\": true, \"nbt\": \"float\", "
                + "\"tags\": [], \"wire\": \"test:widget.entries[].min\"}"));
        for (String child : new String[]{"entries[].origin", "entries[].size"}) {
            fields.add(child, ComponentGeneratorTest.json("{\"status\": \"dropped\"}"));
        }
        Inputs base = ComponentGeneratorTest.inputs();
        return new Inputs(components, base.fixtures(), base.feedback(), mappings, base.transforms(), base.api(), base.since());
    }

    @Test
    void anAffineRuleOfEveryElementIsWrittenFromEachElementInTheLoop() {
        String wire = ComponentGenerator.generate(inputs()).get("org/geysermc/paletter/internal/component/ItemWire.java");
        String widget = wire.substring(wire.indexOf("private static void widget("));
        widget = widget.substring(0, widget.indexOf("\n    }\n") + 1);
        int loop = widget.indexOf("for (Widget.Entry e9 : v8)");
        int rule = widget.indexOf("n10.put(\"min\", Wire.toFloat((8.0 - (double) e9.origin() - (double) e9.size())))");
        assertTrue(loop > 0, widget);
        assertTrue(rule > loop, widget);
    }
}
