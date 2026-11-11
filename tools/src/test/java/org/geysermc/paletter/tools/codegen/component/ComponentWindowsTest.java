package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.codegen.component.ComponentGenerator.Inputs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol windows of the generated encoders on the synthetic model: a window in which BDS does not send the component
 * leaves it out with a report entry, a window list may repeat a mapping (A, B, A), neighbouring windows that encode the
 * same are one, and the protocols the captures cover are in the index.
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

    private static Inputs inputs(Consumer<JsonObject> gadget) {
        JsonObject mappings = ComponentGeneratorTest.mappings();
        JsonObject components = mappings.getAsJsonObject("components");
        JsonObject g = new JsonObject();
        gadget.accept(g);
        components.add("item:test:gadget", g);
        Inputs base = ComponentGeneratorTest.inputs();
        return new Inputs(base.components(), base.fixtures(), base.feedback(), mappings, base.transforms(), base.api(), base.since());
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
    void aComponentNoWindowSendsIsGeneratedAndEncodesNothing() {
        Inputs in = inputs(g -> {
            JsonObject w = window("on", 100);
            w.addProperty("status", "rejected");
            g.asMap().putAll(w.asMap());
            g.add("protocol200", window("on", 200));
            g.getAsJsonObject("protocol200").addProperty("status", "server-only");
        });
        ApiModel model = ComponentGenerator.model(in);
        ApiModel.Comp gadget = model.component("item:test:gadget");
        assertTrue(gadget.neverSent());
        assertEquals(null, gadget.rejected, "the newest window does not reject it");
        assertTrue(gadget.serverOnly != null && gadget.serverOnly.since() == 200);
        assertTrue(model.omitted.isEmpty(), model.omitted.toString());
        // Server only from 200 only: the earlier rejection and the range keep the warning
        assertTrue(itemWire(in).contains("ctx.drop(Gadget.NAME, \"is not sent to clients (BDS: server-only)\")"));
    }

    @Test
    void aRejectedComponentStatesSinceWhenAndWarns() {
        Inputs in = inputs(g -> {
            g.asMap().putAll(window("on", 100).asMap());
            JsonObject rejected = window("on", 200);
            rejected.addProperty("status", "rejected");
            rejected.addProperty("note", "test:gadget: gone");
            g.add("protocol200", rejected);
        });
        ApiModel.Comp gadget = ComponentGenerator.model(in).component("item:test:gadget");
        assertEquals(200, gadget.rejected.since());
        Map<String, String> files = ComponentGenerator.generate(in);
        String source = files.get("org/geysermc/paletter/item/component/Gadget.java");
        assertTrue(source.contains("@Deprecated"), source);
        assertTrue(source.contains("rejected by BDS since 9.9.9 (protocol 200): test:gadget: gone"), source);
        assertTrue(source.contains("@deprecated replaced by {@link Widget}"), source);
        assertTrue(files.get("org/geysermc/paletter/internal/component/ItemWire.java")
            .contains("ctx.drop(Gadget.NAME, \"is not sent to clients (BDS: rejected)\")"));
    }

    @Test
    void aNeverSentComponentWhoseShapeTheDocsLeaveOpenStaysOmitted() {
        Inputs in = inputs(g -> {
            JsonObject w = window("on", 100);
            w.addProperty("status", "rejected");
            g.asMap().putAll(w.asMap());
        });
        JsonObject gadget = in.components().getAsJsonArray("components").asList().stream().map(e -> e.getAsJsonObject())
            .filter(c -> c.get("id").getAsString().equals("item:test:gadget")).findFirst().orElseThrow();
        // An object field the docs list no members of
        JsonObject field = ComponentGeneratorTest.json("{\"path\": \"hit\", \"types\": [{\"type\": \"object\"}], \"required\": false, \"enumValues\": [], \"doc\": \"\"}");
        gadget.getAsJsonArray("fields").add(field);
        ApiModel model = ComponentGenerator.model(in);
        assertEquals(null, model.component("item:test:gadget"));
        assertTrue(model.omitted.get("item:test:gadget").startsWith("not generated, the docs leave its shape open"), model.omitted.toString());
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
            c.addProperty("windowProtocol", p - 1);
            captures.add(c);
        }
        mappings.add("captures", captures);
        Inputs in = new Inputs(base.components(), base.fixtures(), base.feedback(), mappings, base.transforms(), base.api(), base.since());
        String index = ComponentGenerator.generate(in).get("org/geysermc/paletter/internal/component/ComponentIndex.java");
        assertTrue(index.contains("int OLDEST_CAPTURED_PROTOCOL = 100;"), index);
        assertTrue(index.contains("int NEWEST_CAPTURED_PROTOCOL = 200;"), index);
        // the window of each capture, for the protocol checks of the hand transforms
        assertTrue(index.contains("int BDS_1_150 = 149;"), index);
    }
}
