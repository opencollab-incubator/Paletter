package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.Context;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.Inputs;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.Output;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** The delegates of a renamed key, checked in each generation's sources and history. */
    private static void assertRenameDelegates(Output out) {
        String widget = out.sources().get(WIDGET);
        assertTrue(widget.contains("    @Deprecated\n    public @NonNull String title() {\n        return label();\n    }"), widget);
        assertTrue(widget.contains("        @Deprecated\n        public @NonNull Builder title(@NonNull String title) {\n            return label(title);\n        }"),
            widget);
        JsonObject title = fields(out).getAsJsonObject("item:test:widget:title");
        assertEquals("item:test:widget:label", title.get("renamedTo").getAsString());
        assertFalse(title.has("removed"), title.toString());
    }

    private static JsonObject fields(Output out) {
        return JsonParser.parseString(out.history()).getAsJsonObject().getAsJsonObject("fields");
    }

    /** A source with JavaPoet's annotation layout ({@code @Deprecated(\n forRemoval = true\n)}) on one line. */
    private static String flat(String source) {
        return source == null ? null : source.replaceAll("@Deprecated\\(\\s*forRemoval = true\\s*\\)", "@Deprecated(forRemoval = true)");
    }

    @Test
    void aRenamedJsonKeyKeepsItsDelegatesWhenGeneratedAgain() {
        // The second run finds "label" in the history: "title" resolves through its renamedTo, not by wire leaves
        Output first = ComponentGenerator.generateAll(inputs("""
            {"fields": {"item:test:widget:title": {"name": "title", "wire": ["test:widget.label"]}}}"""));
        Output second = ComponentGenerator.generateAll(ComponentGeneratorTest.inputs().withHistory(json(first.history())));
        assertRenameDelegates(first);
        assertRenameDelegates(second);
        assertEquals(first.sources().get(WIDGET), second.sources().get(WIDGET));
    }

    @Test
    void aRenamedJsonKeyKeepsItsDelegatesOverThreeGenerations() {
        Output out = ComponentGenerator.generateAll(inputs("""
            {"fields": {"item:test:widget:title": {"name": "title", "wire": ["test:widget.label"]}}}"""));
        String firstHistory = null;
        for (int generation = 1; generation <= 3; generation++) {
            assertRenameDelegates(out);
            if (generation == 2) {
                firstHistory = out.history();
            }
            if (generation == 3) {
                assertEquals(firstHistory, out.history(), "the history is stable once the rename is recorded");
            }
            out = ComponentGenerator.generateAll(ComponentGeneratorTest.inputs().withHistory(json(out.history())));
        }
    }

    @Test
    void aRemovedFieldKeepsADeprecatedStandInThatReturnsItsDefaultAndIgnoresItsSetter() {
        String history = """
            {"fields": {"item:test:widget:gone": {"name": "gone", "wire": ["test:widget.gone"], "type": "int", "default": 5},
                        "item:test:widget:gear": {"name": "gear", "wire": [], "type": "enum:Mode", "default": "slow"},
                        "item:test:widget:hint": {"name": "hint", "wire": [], "type": "list<string>", "nullable": true}}}""";
        Output out = ComponentGenerator.generateAll(inputs(history));
        String widget = flat(out.sources().get(WIDGET));
        assertTrue(widget.contains("    @Deprecated(forRemoval = true)\n    public int gone() {\n        return 5;\n    }"), widget);
        assertTrue(widget.contains("    @Deprecated(forRemoval = true)\n    public @NonNull Mode gear() {\n        return Mode.SLOW;\n    }"), widget);
        assertTrue(widget.contains("    @Deprecated(forRemoval = true)\n    public @Nullable List<String> hint() {\n        return null;\n    }"), widget);
        assertTrue(widget.contains("        @Deprecated(forRemoval = true)\n        public @NonNull Builder gone(int gone) {\n            return this;\n        }"),
            widget);
        // Not stored: equals and toString do not know it
        assertFalse(widget.contains("other.gone"), widget);
        JsonObject gone = fields(out).getAsJsonObject("item:test:widget:gone");
        assertTrue(gone.get("removed").getAsBoolean(), gone.toString());
        assertEquals(out.history(), ComponentGenerator.generateAll(ComponentGeneratorTest.inputs().withHistory(json(out.history()))).history(),
            "stable");
        // A live field records what its stand-in would need
        JsonObject count = fields(out).getAsJsonObject("item:test:widget:count");
        assertEquals("int", count.get("type").getAsString());
        assertEquals(1, count.get("default").getAsInt());
    }

    @Test
    void aRemovedFieldWithoutARecordedTypeFails() {
        GenerationException e = assertThrows(GenerationException.class, () -> ComponentGenerator.generateAll(inputs("""
            {"fields": {"item:test:widget:gone": {"name": "gone", "wire": ["test:widget.gone"]}}}""")));
        assertTrue(e.getMessage().contains("item:test:widget:gone") && e.getMessage().contains("no type"), e.getMessage());
    }

    @Test
    void aRemovedComponentKeepsADeprecatedStandInThatTheEncoderLeavesOut() {
        String history = """
            {"classes": {"item:test:gizmo": "Gizmo"},
             "types": {"item:test:gizmo:part": "Part"},
             "enums": {"item:test:gizmo:mode": "GizmoMode"},
             "constants": {"item:test:gizmo:mode=off": "OFF", "item:test:gizmo:mode=on": "ON"},
             "fields": {"item:test:gizmo:power": {"name": "power", "wire": [], "type": "float", "default": 1.5},
                        "item:test:gizmo:mode": {"name": "mode", "wire": [], "type": "enum:GizmoMode", "default": "on"},
                        "item:test:gizmo:part": {"name": "part", "wire": [], "type": "object:Part", "nullable": true},
                        "item:test:gizmo:part.size": {"name": "size", "wire": [], "type": "int", "default": 2}}}""";
        Output out = ComponentGenerator.generateAll(inputs(history));
        String gizmo = flat(out.sources().get("dev/opencollab/paletter/item/component/Gizmo.java"));
        assertTrue(gizmo != null && gizmo.contains("@Deprecated(forRemoval = true)\npublic final class Gizmo implements ItemComponent {"),
            String.valueOf(gizmo));
        assertTrue(gizmo.contains("public static final String NAME = \"test:gizmo\";"), gizmo);
        assertTrue(gizmo.contains("    public float power() {\n        return 1.5f;\n    }"), gizmo);
        assertTrue(gizmo.contains("    public @NonNull GizmoMode mode() {\n        return GizmoMode.ON;\n    }"), gizmo);
        assertTrue(gizmo.contains("    public @Nullable Part part() {\n        return null;\n    }"), gizmo);
        assertTrue(gizmo.contains("public static final class Part {") && gizmo.contains("    public int size() {\n            return 2;\n        }"), gizmo);
        assertTrue(gizmo.contains("public static final GizmoMode ON = new GizmoMode(\"on\");"), gizmo);
        assertTrue(gizmo.contains("return o instanceof Gizmo;"), "no stored fields: " + gizmo);
        String iface = out.sources().get("dev/opencollab/paletter/item/component/ItemComponent.java");
        assertTrue(iface.contains("Gizmo"), iface);
        String wire = out.sources().get("dev/opencollab/paletter/internal/component/ItemWire.java");
        assertTrue(wire.contains("case Gizmo c -> ctx.drop(Gizmo.NAME, \"was removed from Bedrock (a deprecated stand-in)\");"), wire);
        JsonObject recorded = JsonParser.parseString(out.history()).getAsJsonObject();
        assertEquals("{\"name\":\"Gizmo\",\"removed\":true}", recorded.getAsJsonObject("classes").get("item:test:gizmo").toString());
        assertTrue(fields(out).getAsJsonObject("item:test:gizmo:power").get("removed").getAsBoolean());
        Output again = ComponentGenerator.generateAll(ComponentGeneratorTest.inputs().withHistory(json(out.history())));
        assertEquals(out.history(), again.history(), "stable");
        assertEquals(gizmo, flat(again.sources().get("dev/opencollab/paletter/item/component/Gizmo.java")));
        // Deleting the entries deliberately (a major release) removes the stand-in
        assertFalse(ComponentGenerator.generate(ComponentGeneratorTest.inputs()).containsKey("dev/opencollab/paletter/item/component/Gizmo.java"));
    }

    /** Stand-ins of the committed model compile against Paletter: a removed component and a removed field of a live one. */
    @Test
    void standInsOfTheCommittedModelCompile(@org.junit.jupiter.api.io.TempDir Path temp) throws IOException {
        Path root = Path.of(System.getProperty("paletter.root", ".."));
        Inputs in = ComponentGenerator.read(root.resolve("data/model"), Main.version(root), Main.context(root));
        JsonObject history = in.history().deepCopy();
        history.getAsJsonObject("classes").addProperty("item:minecraft:test_gizmo", "TestGizmo");
        history.getAsJsonObject("enums").addProperty("item:minecraft:test_gizmo:mode", "GizmoMode");
        history.getAsJsonObject("constants").addProperty("item:minecraft:test_gizmo:mode=on", "ON");
        history.getAsJsonObject("fields").add("item:minecraft:test_gizmo:mode",
            json("{\"name\": \"mode\", \"wire\": [], \"type\": \"enum:GizmoMode\", \"default\": \"on\"}"));
        history.getAsJsonObject("fields").add("item:minecraft:test_gizmo:sizes",
            json("{\"name\": \"sizes\", \"wire\": [], \"type\": \"list<int>\", \"nullable\": true}"));
        history.getAsJsonObject("fields").add("item:minecraft:cooldown:test_gone",
            json("{\"name\": \"testGone\", \"wire\": [], \"type\": \"string\"}"));
        Output out = ComponentGenerator.generateAll(in.withHistory(history));
        assertTrue(out.sources().containsKey("dev/opencollab/paletter/item/component/TestGizmo.java"));
        assertTrue(out.sources().get("dev/opencollab/paletter/item/component/Cooldown.java").contains(" testGone("));
        List<String> sources = new ArrayList<>();
        for (Map.Entry<String, String> file : out.sources().entrySet()) {
            Path path = temp.resolve("src").resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
            sources.add(path.toString());
        }
        List<String> args = new ArrayList<>(List.of("-d", temp.resolve("classes").toString(), "-classpath", System.getProperty("java.class.path"),
            "-proc:none", "-Xlint:-options", "-nowarn"));
        args.addAll(sources);
        java.io.ByteArrayOutputStream errors = new java.io.ByteArrayOutputStream();
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, errors, errors, args.toArray(String[]::new)),
            "the generated sources do not compile: " + errors);
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

    /** The committed history loses no entry when the committed model is generated: every recorded identity is still there. */
    @Test
    void theCommittedHistoryLosesNoEntry() throws IOException {
        Path root = Path.of(System.getProperty("paletter.root", ".."));
        JsonObject committed = json(Files.readString(root.resolve("data/model/" + ApiHistory.FILE), StandardCharsets.UTF_8));
        Inputs in = ComponentGenerator.read(root.resolve("data/model"), Main.version(root), Main.context(root));
        JsonObject generated = json(ComponentGenerator.generateAll(in).history());
        for (String section : ApiHistory.SECTIONS) {
            Set<String> before = committed.getAsJsonObject(section).keySet();
            Set<String> after = generated.getAsJsonObject(section).keySet();
            assertTrue(after.containsAll(before), section + " lost " + before.stream().filter(k -> !after.contains(k)).toList());
        }
    }
}
