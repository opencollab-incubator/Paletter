package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.Context;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.Inputs;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.Output;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static dev.opencollab.paletter.tools.codegen.component.ComponentGeneratorTest.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code api-history.json} ({@link ApiHistory}): recorded names are reused, a newcomer takes a collision's prefix, a renamed
 * JSON key keeps its old accessor and setter as deprecated delegates, and no entry is ever dropped.
 */
class ApiHistoryTest {
    private static final String WIDGET = "dev/opencollab/paletter/item/component/Widget.java";

    private static Inputs inputs(String history) {
        return ComponentGeneratorTest.inputs().withHistory(json(history));
    }

    @Test
    void recordedNamesAreReusedNotRecomputed() {
        Map<String, String> files = ComponentGenerator.generate(inputs("""
            {"types": {"item:test:widget:nested": "OldNested"},
             "fields": {"item:test:widget:count": {"name": "amount", "wire": ["test:widget.count"]}},
             "enums": {"item:test:widget:mode": "Speed"},
             "constants": {"item:test:widget:mode=fast": "QUICK"}}"""));
        String widget = files.get(WIDGET);
        assertTrue(widget.contains("public static final class OldNested"), widget);
        assertFalse(widget.contains("class NestedThing"), widget);
        assertTrue(widget.contains("public int amount()"), widget);
        assertTrue(widget.contains("public static final class Speed"), widget);
        assertTrue(widget.contains("public static final Speed QUICK = new Speed(\"fast\")"), widget);
    }

    @Test
    void aNewcomerTakesThePrefixOfANewCollision() {
        // The item widget is recorded unprefixed; a block widget arrives: only the block widget is prefixed
        Inputs in = ComponentNamingTest.withBlockWidget(Context.NONE).withHistory(json("{\"classes\": {\"item:test:widget\": \"Widget\"}}"));
        Output out = ComponentGenerator.generateAll(in);
        assertTrue(out.sources().containsKey(WIDGET), out.sources().keySet().toString());
        assertTrue(out.sources().containsKey("dev/opencollab/paletter/block/component/BlockWidget.java"));
        assertFalse(out.sources().containsKey("dev/opencollab/paletter/item/component/ItemWidget.java"));
        JsonObject history = JsonParser.parseString(out.history()).getAsJsonObject();
        assertEquals("BlockWidget", history.getAsJsonObject("classes").get("block:test:widget").getAsString());
        // Without the record both get one, as before
        assertTrue(ComponentGenerator.generate(ComponentNamingTest.withBlockWidget(Context.NONE))
            .containsKey("dev/opencollab/paletter/item/component/ItemWidget.java"));
    }

    @Test
    void aRenamedJsonKeyKeepsItsOldAccessorAndSetterAsDeprecatedDelegates() {
        // "title" left the model, and the new "label" writes the same wire leaf
        Output out = ComponentGenerator.generateAll(inputs("""
            {"fields": {"item:test:widget:title": {"name": "title", "wire": ["test:widget.label"]}}}"""));
        String widget = out.sources().get(WIDGET);
        assertTrue(widget.contains("    @Deprecated\n    public @NonNull String title() {\n        return label();\n    }"), widget);
        assertTrue(widget.contains("        @Deprecated\n        public @NonNull Builder title(@NonNull String title) {\n            return label(title);\n        }"),
            widget);
        JsonObject title = JsonParser.parseString(out.history()).getAsJsonObject().getAsJsonObject("fields").getAsJsonObject("item:test:widget:title");
        assertEquals("item:test:widget:label", title.get("renamedTo").getAsString());
    }

    @Test
    void aFieldWithoutASuccessorIsKeptInTheHistoryWithoutAMember() {
        Output out = ComponentGenerator.generateAll(inputs("""
            {"fields": {"item:test:widget:gone": {"name": "gone", "wire": ["test:widget.gone"]}}}"""));
        assertFalse(out.sources().get(WIDGET).contains(" gone("));
        assertTrue(JsonParser.parseString(out.history()).getAsJsonObject().getAsJsonObject("fields").has("item:test:widget:gone"));
    }

    @Test
    void aHandNameThatDisagreesWithTheHistoryFails() {
        Inputs base = inputs("{\"fields\": {\"item:test:widget:count\": {\"name\": \"amount\", \"wire\": []}}}");
        if (!base.api().has("fields")) {
            base.api().add("fields", new JsonObject());
        }
        base.api().getAsJsonObject("fields").addProperty("item:test:widget:count", "total");
        GenerationException e = assertThrows(GenerationException.class, () -> ComponentGenerator.generate(base));
        assertTrue(e.getMessage().contains("api.json names it total") && e.getMessage().contains("amount"), e.getMessage());
    }

    @Test
    void theHistoryOnlyGrows() {
        String first = ComponentGenerator.generateAll(ComponentGeneratorTest.inputs()).history();
        JsonObject recorded = json(first);
        recorded.getAsJsonObject("classes").addProperty("item:test:removed", "Removed");
        String second = ComponentGenerator.generateAll(ComponentGeneratorTest.inputs().withHistory(recorded)).history();
        assertTrue(json(second).getAsJsonObject("classes").has("item:test:removed"), "a component that left the model keeps its entry");
        assertEquals(second, ComponentGenerator.generateAll(ComponentGeneratorTest.inputs().withHistory(json(second))).history(), "stable");
    }
}
