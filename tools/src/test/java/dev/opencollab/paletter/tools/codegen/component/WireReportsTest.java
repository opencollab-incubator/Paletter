package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonObject;
import com.palantir.javapoet.CodeBlock;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.Inputs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.opencollab.paletter.tools.codegen.component.ComponentGeneratorTest.json;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the encoders report for a value they cannot send, and smaller generator guards. */
class WireReportsTest {
    private static final String ITEM_WIRE = "dev/opencollab/paletter/internal/component/ItemWire.java";

    @Test
    void aFieldWithADefaultThatAWindowCannotSendReportsAnyOtherValue() {
        // count (default 1) is unknown to BDS in the widget's window: setting it to anything but 1 is reported
        Inputs in = ComponentGeneratorTest.inputs();
        JsonObject widget = ComponentGeneratorTest.window(in.mappings(), "item:test:widget");
        widget.getAsJsonObject("fields").add("count", json("{\"status\": \"rejected\", \"unknownToBds\": true}"));
        String wire = ComponentGenerator.generate(in).get(ITEM_WIRE);
        assertTrue(wire.contains("if (c.count() != 1) {\n            ctx.drop(Widget.NAME, \"count does not exist in this Bedrock version\");\n        }"), wire);
    }

    @Test
    void anOrderedSetReportsAValueOutsideItsOrder() {
        // BDS's own order of a closed set of values (connected_faces): a value outside it has no place and is reported
        Inputs in = ComponentGeneratorTest.inputs();
        ComponentGeneratorTest.window(in.mappings(), "item:test:widget").getAsJsonObject("fields").add("tags", json(
            "{\"status\": \"inferred\", \"rules\": [{\"kind\": \"ordered-set\", \"order\": [\"b\", \"a\"], \"nbt\": \"string\", \"tags\": [], \"wire\": \"test:widget.tags[]\"}]}"));
        String wire = ComponentGenerator.generate(in).get(ITEM_WIRE);
        assertTrue(java.util.regex.Pattern.compile("ctx\\.drop\\(Widget\\.NAME, \"tags = \" \\+ x\\d*").matcher(wire).find(), wire);
        assertTrue(wire.contains("List.of(\"b\", \"a\").stream().filter("), wire);
    }

    @Test
    void anEnumDefaultThatIsNoValueFailsNamingTheComponent() {
        // A stand-in of a removed field whose recorded default is no recorded value of its value class
        Inputs in = ComponentGeneratorTest.inputs().withHistory(json("""
            {"fields": {"item:test:widget:gear": {"name": "gear", "wire": [], "type": "enum:Mode", "default": "medium"}}}"""));
        com.google.gson.JsonArray removed = new com.google.gson.JsonArray();
        removed.add("item:test:widget:gear");
        in.api().add("removedFields", removed);
        GenerationException e = assertThrows(GenerationException.class, () -> ComponentGenerator.generate(in));
        assertTrue(e.getMessage().startsWith("item:test:widget: the default \"medium\" is no documented value of Mode"), e.getMessage());
    }

    @Test
    void aMapOfMoreThanTenPairsIsMapOfEntries() {
        List<CodeBlock> pairs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            pairs.add(CodeBlock.of("$S, $S", "k" + i, "v" + i));
        }
        assertTrue(IndexSource.mapOf(pairs).toString().startsWith("java.util.Map.of(\"k0\", \"v0\""), IndexSource.mapOf(pairs).toString());
        pairs.add(CodeBlock.of("$S, $S", "k10", "v10"));
        assertTrue(IndexSource.mapOf(pairs).toString().startsWith("java.util.Map.ofEntries(java.util.Map.entry(\"k0\", \"v0\"), "),
            IndexSource.mapOf(pairs).toString());
    }
}
