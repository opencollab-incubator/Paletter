package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonObject;
import com.palantir.javapoet.CodeBlock;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.Inputs;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.opencollab.paletter.tools.codegen.component.ComponentGeneratorTest.json;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the encoders report for a value they cannot send, and smaller generator guards. */
class WireReportsTest {
    private static final String ITEM_WIRE = "dev/opencollab/paletter/internal/component/ItemWire.java";

    private static Map<String, String> committed() throws IOException {
        Path root = Path.of(System.getProperty("paletter.root", ".."));
        return ComponentGenerator.generate(ComponentGenerator.read(root.resolve("data/model"), Main.version(root), Main.context(root)));
    }

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
    void anOrderedSetReportsAValueOutsideItsOrder() throws IOException {
        String wire = committed().get("dev/opencollab/paletter/internal/component/BlockWire.java");
        assertTrue(wire.contains("ctx.drop(RedstoneProducer.NAME, \"connected_faces = \" + x"), wire);
    }

    @Test
    void aFieldSentAsAConstantIsReportedOnlyForAnotherValue() throws IOException {
        String wire = committed().get(ITEM_WIRE);
        assertTrue(java.util.regex.Pattern.compile("if \\(!\"sword\"\\.equals\\([\\w.()]+\\)\\) \\{\n\\s+ctx\\.changed\\(Enchantable\\.NAME, \"slot is sent as a constant, whatever its value\"\\);")
            .matcher(wire).find(), wire);
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
