package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import org.geysermc.paletter.tools.codegen.component.ComponentGenerator.Inputs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol windows of the generated encoders on the synthetic model: a window in which BDS does not send the component
 * leaves it out with a report entry, a window list may repeat a mapping (A, B, A), hand transforms have windows of their
 * own, neighbouring windows that encode the same are one, and the protocols the captures cover are in the index.
 */
class ComponentWindowsTest {
    private static final String WIDGET_SIMPLE = "{\"status\": \"sent\", \"wire\": [\"test:gadget\"], "
        + "\"forms\": {\"boolean\": {\"status\": \"inferred\", \"rules\": [{\"kind\": \"identity\", \"nbt\": \"byte\", \"tags\": [], \"wire\": \"test:gadget.%1$s\"}]}}, \"fields\": {"
        + "\"value\": {\"status\": \"inferred\", \"rules\": [{\"kind\": \"identity\", \"nbt\": \"byte\", \"tags\": [], \"wire\": \"test:gadget.%1$s\"}]}}}";

    private static JsonObject window(String leaf, int... protocols) {
        JsonObject w = ComponentGeneratorTest.json(WIDGET_SIMPLE.formatted(leaf));
        JsonArray array = new JsonArray();
        for (int p : protocols) {
            array.add(p);
        }
        w.add("protocols", array);
        return w;
    }

    private static Inputs inputs(Consumer<JsonObject> gadget, JsonObject transforms) {
        JsonObject mappings = ComponentGeneratorTest.mappings();
        JsonObject components = mappings.getAsJsonObject("components");
        JsonObject g = new JsonObject();
        gadget.accept(g);
        components.add("item:test:gadget", g);
        Inputs base = ComponentGeneratorTest.inputs();
        return new Inputs(base.components(), base.fixtures(), base.feedback(), mappings, transforms, base.api(), Set.of("test_map", "map_a", "map_b"),
            base.since());
    }

    private static Inputs inputs(Consumer<JsonObject> gadget) {
        return inputs(gadget, ComponentGeneratorTest.inputs().transforms());
    }

    private static String itemWire(Inputs in) {
        Map<String, String> files = ComponentGenerator.generate(in);
        return files.get("org/geysermc/paletter/internal/component/ItemWire.java");
    }

    @Test
    void aWindowThatDoesNotSendTheComponentDropsItWithAReportEntry() {
        Inputs in = inputs(g -> {
            JsonObject old = window("on", 100);
            old.addProperty("status", "server-only");
            old.add("wire", new JsonArray());
            old.add("fields", new JsonObject());
            g.asMap().putAll(old.asMap());
            g.add("protocol200", window("on", 200));
        });
        ApiModel model = ComponentGenerator.model(in);
        ApiModel.Comp gadget = model.components.stream().filter(c -> c.id.equals("item:test:gadget")).findFirst().orElseThrow();
        assertEquals(List.of("server-only", "sent"), gadget.windows.stream().map(ApiModel.Window::status).toList());
        assertEquals(gadget.windows.get(1), gadget.latest(), "the API follows the newest window that sends the component");
        String source = itemWire(in);
        assertTrue(source.contains("gadgetUntil200(c, ctx, out)"), source);
        assertTrue(source.contains("ctx.drop(Gadget.NAME, \"is not sent to clients (BDS: server-only)\")"), source);
        assertTrue(source.contains("out.put(\"test:gadget.on\""), source);
    }

    @Test
    void aComponentNoWindowSendsIsLeftOut() {
        ApiModel model = ComponentGenerator.model(inputs(g -> {
            JsonObject w = window("on", 100);
            w.addProperty("status", "rejected");
            g.asMap().putAll(w.asMap());
            g.add("protocol200", window("on", 200));
            g.getAsJsonObject("protocol200").addProperty("status", "server-only");
        }));
        assertTrue(model.components.stream().noneMatch(c -> c.id.equals("item:test:gadget")));
        assertTrue(model.omitted.get("item:test:gadget").startsWith("server only"), model.omitted.toString());
    }

    @Test
    void aMappingMayComeBackInALaterWindow() {
        Inputs in = inputs(g -> {
            g.asMap().putAll(window("on", 100, 150).asMap());
            g.add("protocol200", window("active", 200));
            g.add("protocol300", window("on", 300));
        });
        ApiModel.Comp gadget = ComponentGenerator.model(in).components.stream().filter(c -> c.id.equals("item:test:gadget")).findFirst().orElseThrow();
        assertEquals(List.of(Integer.MIN_VALUE, 200, 300), gadget.windows.stream().map(ApiModel.Window::since).toList());
        assertEquals(List.of(200, 300, Integer.MAX_VALUE), gadget.windows.stream().map(ApiModel.Window::until).toList());
        String source = itemWire(in);
        assertTrue(source.contains("gadgetUntil200"), source);
        assertTrue(source.contains("gadgetFrom200Until300"), source);
        assertTrue(source.contains("gadgetFrom300"), source);
    }

    @Test
    void windowsThatEncodeTheSameAreOne() {
        // Two windows whose mappings differ only in what the encoder ignores
        Inputs in = inputs(g -> {
            g.asMap().putAll(window("on", 100).asMap());
            JsonObject second = window("on", 200);
            second.addProperty("note", "another message of another build");
            g.add("protocol200", second);
        });
        String source = itemWire(in);
        assertTrue(source.contains("private static void gadget(Gadget c, WireContext ctx, WireNode out) {\n        Boolean"), source);
        assertTrue(!source.contains("gadgetUntil200") && !source.contains("gadgetFrom200"), source);
    }

    @Test
    void theIndexKnowsTheCapturedProtocols() {
        Inputs base = ComponentGeneratorTest.inputs();
        JsonObject mappings = ComponentGeneratorTest.mappings();
        JsonArray captures = new JsonArray();
        for (int p : new int[]{200, 100, 150}) {
            JsonObject c = new JsonObject();
            c.addProperty("bdsVersion", "1." + p);
            c.addProperty("protocol", p);
            captures.add(c);
        }
        mappings.add("captures", captures);
        Inputs in = new Inputs(base.components(), base.fixtures(), base.feedback(), mappings, base.transforms(), base.api(), base.knownTransforms(),
            base.since());
        String index = ComponentGenerator.generate(in).get("org/geysermc/paletter/internal/component/ComponentIndex.java");
        assertTrue(index.contains("int OLDEST_CAPTURED_PROTOCOL = 100;"), index);
        assertTrue(index.contains("int NEWEST_CAPTURED_PROTOCOL = 200;"), index);
    }

    // ---- Transform windows -----------------------------------------------------------------------------------------

    private static JsonObject transforms(String windows) {
        return ComponentGeneratorTest.json("{\"item:test:widget:colors\": {\"transform\": \"test_map\", \"why\": \"a map\", \"windows\": " + windows + "}}");
    }

    @Test
    void aHandTransformDispatchesOnTheProtocol() {
        String source = itemWire(inputs(g -> g.asMap().putAll(window("on", 100).asMap()),
            transforms("[{\"until\": 150, \"transform\": \"map_a\"}, {\"since\": 150, \"until\": 200, \"transform\": \"map_b\"}, {\"since\": 200, \"transform\": \"test_map\"}]")));
        assertTrue(source.contains("int transformProtocol = ctx.protocol();"), source);
        assertTrue(source.contains("if (transformProtocol < 150) {\n"), source);
        assertTrue(source.contains("Transforms.mapA("), source);
        assertTrue(source.contains("else if (transformProtocol >= 150 && transformProtocol < 200) {"), source);
        assertTrue(source.contains("Transforms.mapB("), source);
        assertTrue(source.contains("} else {\n            Transforms.testMap("), source);
    }

    @Test
    void transformWindowsMustCoverEveryProtocolOnce() {
        Consumer<JsonObject> gadget = g -> g.asMap().putAll(window("on", 100).asMap());
        // a gap
        assertThrows(GenerationException.class, () -> ComponentGenerator.generate(inputs(gadget,
            transforms("[{\"until\": 150, \"transform\": \"map_a\"}, {\"since\": 160, \"transform\": \"test_map\"}]"))));
        // an overlap
        assertThrows(GenerationException.class, () -> ComponentGenerator.generate(inputs(gadget,
            transforms("[{\"until\": 170, \"transform\": \"map_a\"}, {\"since\": 160, \"transform\": \"test_map\"}]"))));
        // not ending open
        assertThrows(GenerationException.class, () -> ComponentGenerator.generate(inputs(gadget,
            transforms("[{\"until\": 150, \"transform\": \"map_a\"}, {\"since\": 150, \"until\": 300, \"transform\": \"test_map\"}]"))));
        // the last one is not the entry's transform
        assertThrows(GenerationException.class, () -> ComponentGenerator.generate(inputs(gadget,
            transforms("[{\"until\": 150, \"transform\": \"test_map\"}, {\"since\": 150, \"transform\": \"map_a\"}]"))));
        // a transform the runtime does not have
        assertThrows(GenerationException.class, () -> ComponentGenerator.generate(inputs(gadget,
            transforms("[{\"until\": 150, \"transform\": \"nope\"}, {\"since\": 150, \"transform\": \"test_map\"}]"))));
        // one window is no list
        assertThrows(GenerationException.class, () -> ComponentGenerator.generate(inputs(gadget,
            transforms("[{\"transform\": \"test_map\"}]"))));
    }
}
