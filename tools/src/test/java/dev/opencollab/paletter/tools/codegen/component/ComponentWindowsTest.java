package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.Inputs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol windows of the generated encoders on the synthetic model: a window in which BDS does not send the component
 * leaves it out with a report entry, a window list may repeat a mapping (A, B, A), neighbouring windows that encode the
 * same are one, the protocols the captures cover are in the index, and the envelope is written per window.
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

    /** The gadget with the windows {@code gadget} adds, oldest first. */
    private static Inputs inputs(Consumer<JsonArray> gadget) {
        JsonObject mappings = ComponentGeneratorTest.mappings();
        JsonObject components = mappings.getAsJsonObject("components");
        JsonArray windows = new JsonArray();
        gadget.accept(windows);
        JsonObject g = new JsonObject();
        g.add("windows", windows);
        components.add("item:test:gadget", g);
        // these windows use protocols of their own, not the ones of the captures
        mappings.getAsJsonArray("captures").forEach(capture -> capture.getAsJsonObject().remove("windowProtocol"));
        Inputs base = ComponentGeneratorTest.inputs();
        // Optional by hand: these windows show nothing of what BDS sends without them, which would make them required
        base.api().getAsJsonArray("optional").add("item:test:gadget:value");
        base.api().getAsJsonArray("optional").add("item:test:gadget:level");
        return new Inputs(base.components(), base.fixtures(), base.feedback(), mappings, base.transforms(), base.api(), base.since());
    }

    private static String itemWire(Inputs in) {
        Map<String, String> files = ComponentGenerator.generate(in);
        return files.get("dev/opencollab/paletter/internal/component/ItemWire.java");
    }

    @Test
    void aWindowThatDoesNotSendTheComponentDropsItWithAReportEntry() {
        Inputs in = inputs(g -> {
            JsonObject old = window("on", 100);
            old.addProperty("status", "server-only");
            old.add("wire", new JsonArray());
            old.add("fields", new JsonObject());
            g.add(old);
            g.add(window("on", 200));
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
    void aFieldWithoutAResultInTheNewestWindowFollowsTheOlderWindowAndIsReported() {
        // value has a result at 100, none at 200 (that capture lacks its variants)
        Inputs in = inputs(g -> {
            g.add(window("on", 100));
            JsonObject newest = window("on", 200);
            newest.getAsJsonObject("fields").add("value", ComponentGeneratorTest.json("{\"status\": \"not-generated\"}"));
            newest.remove("forms");
            g.add(newest);
        });
        ApiModel.Comp gadget = ComponentGenerator.model(in).component("item:test:gadget");
        assertEquals(gadget.windows.get(0), gadget.evidence("value"), "the API facts of value come from the window with a result");
        assertEquals(gadget.windows.get(1), gadget.evidence("level"), "a field without any result follows the newest window");
        List<String> gaps = Windows.gaps(gadget);
        assertEquals(1, gaps.size(), gaps.toString());
        assertTrue(gaps.getFirst().startsWith("item:test:gadget value has no result in the newest capture window (from protocol 200)"), gaps.getFirst());
        // The history records the leaves of the window with a result, and newer protocols drop the field with a report entry
        assertEquals(java.util.Set.of("test:gadget.on"), ApiHistory.wire(gadget,
            gadget.root.fields.stream().filter(f -> "value".equals(f.path)).findFirst().orElseThrow()));
        assertTrue(itemWire(in).contains("value has no known wire mapping"));
    }

    @Test
    void aComponentNoWindowSendsIsGeneratedAndEncodesNothing() {
        Inputs in = inputs(g -> {
            JsonObject w = window("on", 100);
            w.addProperty("status", "rejected");
            g.add(w);
            g.add(window("on", 200));
            g.get(1).getAsJsonObject().addProperty("status", "server-only");
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
            g.add(window("on", 100));
            JsonObject rejected = window("on", 200);
            rejected.addProperty("status", "rejected");
            rejected.addProperty("note", "test:gadget: gone");
            g.add(rejected);
        });
        ApiModel.Comp gadget = ComponentGenerator.model(in).component("item:test:gadget");
        assertEquals(200, gadget.rejected.since());
        Map<String, String> files = ComponentGenerator.generate(in);
        String source = files.get("dev/opencollab/paletter/item/component/Gadget.java");
        assertTrue(source.contains("@Deprecated"), source);
        // no data version for the capture here: the protocol alone (public Javadoc names no BDS build)
        assertTrue(source.contains("rejected by BDS since protocol 200: test:gadget: gone"), source);
        assertTrue(source.contains("@deprecated replaced by {@link Widget}"), source);
        assertTrue(files.get("dev/opencollab/paletter/internal/component/ItemWire.java")
            .contains("ctx.drop(Gadget.NAME, \"is not sent to clients (BDS: rejected)\")"));
        assertFalse(source.contains("@BedrockUntil"), "no data version names the window: " + source);

        // With the captures' windows and data versions, the first version that rejects it is @BedrockUntil
        in.mappings().getAsJsonArray("captures").forEach(capture -> {
            JsonObject c = capture.getAsJsonObject();
            int protocol = c.get("protocol").getAsInt();
            c.addProperty("windowProtocol", protocol);
            c.addProperty("dataVersion", protocol == 100 ? "8.80" : "9.90");
        });
        String named = ComponentGenerator.generate(in).get("dev/opencollab/paletter/item/component/Gadget.java");
        assertTrue(named.contains("@BedrockUntil(\"9.90\")\n"), named);
        assertTrue(named.contains("rejected by BDS since Bedrock 9.90 (protocol 200): test:gadget: gone"), named);
    }

    @Test
    void aNeverSentComponentWhoseShapeTheDocsLeaveOpenStaysOmitted() {
        Inputs in = inputs(g -> {
            JsonObject w = window("on", 100);
            w.addProperty("status", "rejected");
            g.add(w);
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
            g.add(window("on", 100, 150));
            g.add(window("active", 200));
            g.add(window("on", 300));
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
    void theLastWindowIsAPlainElseSoTheDispatchIsExhaustive() {
        Inputs in = inputs(g -> {
            g.add(window("on", 100));
            g.add(window("active", 200));
            g.add(window("on", 300));
        });
        String source = itemWire(in);
        assertTrue(source.contains("if (protocol < 200) {") && source.contains("} else if (protocol < 300) {") && source.contains("} else {"), source);
        assertTrue(!source.contains("protocol >="), source);
    }

    @Test
    void aFieldThatWritesNothingInAWindowGetsNeitherAVariableNorAnEmptyIf() {
        Inputs in = inputs(g -> {
            g.add(window("on", 100));
            JsonObject second = window("on", 200);
            second.getAsJsonObject("fields").add("value", ComponentGeneratorTest.json("{\"status\": \"rejected\"}"));
            second.getAsJsonObject("forms").remove("boolean");
            g.add(second);
        });
        String source = itemWire(in);
        assertTrue(!source.matches("(?s).*if \\(v\\d+ != null\\) \\{\\s*}.*"), source);
    }

    @Test
    void aCaptureWithoutTheComponentFailsInsteadOfBeingAbsorbedByItsNeighbourWindow() {
        Inputs in = inputs(g -> g.add(window("on", 100)));
        int[] windows = {100, 200};
        in.mappings().getAsJsonArray("captures").forEach(capture -> capture.getAsJsonObject().addProperty("windowProtocol", windows[capture.getAsJsonObject().get("protocol").getAsInt() / 100 - 1]));
        ComponentGenerator.GenerationException e = org.junit.jupiter.api.Assertions.assertThrows(ComponentGenerator.GenerationException.class,
            () -> ComponentGenerator.generate(in));
        assertTrue(e.getMessage().contains("no result for the captures of protocols [200]"), e.getMessage());
    }

    /** The gadget absent in the build of protocol 100 (its schema lacks it) and sent from 200, with the captures' windows and data versions. */
    private static Inputs absentThenSent(boolean previewSends) {
        Inputs in = inputs(g -> {
            JsonObject absent = new JsonObject();
            absent.addProperty("status", "absent");
            absent.addProperty("note", "not in the schema of BDS 8.8.8, no variant in its add-on");
            JsonArray protocols = new JsonArray();
            protocols.add(100);
            absent.add("protocols", protocols);
            g.add(absent);
            g.add(window("on", 200));
        });
        in.mappings().getAsJsonArray("captures").forEach(capture -> {
            JsonObject c = capture.getAsJsonObject();
            int protocol = c.get("protocol").getAsInt();
            c.addProperty("windowProtocol", protocol);
            c.addProperty("dataVersion", protocol == 100 ? "8.80" : "9.90");
            if (previewSends && protocol == 200) {
                c.addProperty("preview", "9.90");
            }
        });
        return in;
    }

    @Test
    void aBuildWithoutTheComponentIsAWindowThatDropsItAndTheApiNamesTheFirstVersionWithIt() {
        Inputs in = absentThenSent(false);
        ApiModel.Comp gadget = ComponentGenerator.model(in).component("item:test:gadget");
        assertEquals(List.of("absent", "sent"), gadget.windows.stream().map(ApiModel.Window::status).toList());
        assertEquals("9.90", gadget.bedrockSince);
        assertEquals(null, gadget.sincePreview);
        Map<String, String> files = ComponentGenerator.generate(in);
        String api = files.get("dev/opencollab/paletter/item/component/Gadget.java");
        assertTrue(api.contains("@BedrockSince(\"9.90\")\n"), api);
        assertTrue(api.contains("Bedrock 9.90 is the first bundled version with it"), api);
        String wire = files.get("dev/opencollab/paletter/internal/component/ItemWire.java");
        // Not sent to protocols before 200: the existing per-window drop, with a report entry
        assertTrue(wire.matches("(?s).*if \\(protocol < 200\\) \\{\\s*gadgetUntil200\\(c, ctx, out\\);.*"), wire);
        assertTrue(wire.contains("ctx.drop(Gadget.NAME, \"does not exist in this Bedrock version\")"), wire);
        assertTrue(wire.contains("out.put(\"test:gadget.on\""), wire);
    }

    @Test
    void aComponentOnlyThePreviewHasIsSincePreviewNotBedrockSince() {
        Inputs in = absentThenSent(true);
        ApiModel.Comp gadget = ComponentGenerator.model(in).component("item:test:gadget");
        assertEquals("9.90", gadget.sincePreview, "an absent release does not have it");
        assertEquals(null, gadget.bedrockSince);
        String api = ComponentGenerator.generate(in).get("dev/opencollab/paletter/item/component/Gadget.java");
        assertTrue(api.contains("@SincePreview(\"9.90\")") && !api.contains("@BedrockSince"), api);
    }

    @Test
    void windowsThatEncodeTheSameAreOne() {
        // Two windows whose mappings differ only in what the encoder ignores
        Inputs in = inputs(g -> {
            g.add(window("on", 100));
            JsonObject second = window("on", 200);
            second.addProperty("note", "another message of another build");
            g.add(second);
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
            c.addProperty("dataVersion", "0." + p);
            c.addProperty("protocol", p);
            c.addProperty("windowProtocol", p - 1);
            captures.add(c);
        }
        mappings.add("captures", captures);
        // (every component has a result for each of these captures; their windows are not what is tested here)
        mappings.getAsJsonObject("components").entrySet().forEach(e -> {
            JsonArray all = new JsonArray();
            for (int window : new int[]{99, 149, 199}) {
                all.add(window);
            }
            dev.opencollab.paletter.tools.model.MappingWindows.oldest(e.getValue().getAsJsonObject()).add("protocols", all);
        });
        Inputs in = new Inputs(base.components(), base.fixtures(), base.feedback(), mappings, base.transforms(), base.api(), base.since());
        String index = ComponentGenerator.generate(in).get("dev/opencollab/paletter/internal/component/ComponentIndex.java");
        assertTrue(index.contains("int OLDEST_CAPTURED_PROTOCOL = 100;"), index);
        assertTrue(index.contains("int NEWEST_CAPTURED_PROTOCOL = 200;"), index);
        // the window of each capture, for the protocol checks of the hand transforms, named by its data version
        assertTrue(index.contains("int V0_150 = 149;"), index);
        assertFalse(index.contains("BDS_1_150"), index);
    }

    @Test
    void theEnvelopeIsWrittenPerWindowNewestFirst() {
        Inputs base = ComponentGeneratorTest.inputs();
        JsonObject mappings = ComponentGeneratorTest.mappings();
        mappings.add("envelope", ComponentGeneratorTest.json("""
            {"item": [{"protocols": [100], "leaves": {"item_properties.size": "1 (int)"}},
                      {"protocols": [150, 200], "leaves": {"item_properties.size": "2 (int)", "item_tags": "[] (list<void>)"}}],
             "block": []}"""));
        Inputs in = new Inputs(base.components(), base.fixtures(), base.feedback(), mappings, base.transforms(), base.api(), base.since());
        Map<String, String> files = ComponentGenerator.generate(in);
        String item = files.get("dev/opencollab/paletter/internal/component/ItemWire.java");
        assertTrue(item.contains("""
                public static void envelope(@NonNull WireContext ctx, @NonNull WireNode out) {
                    int protocol = ctx.protocol();
                    if (protocol >= 150) {
                        out.put("item_properties.size", 2);
                        out.put("item_tags", Wire.EMPTY_LIST);
                    } else {
                        out.put("item_properties.size", 1);
                    }
                }
            """), item);
        String block = files.get("dev/opencollab/paletter/internal/component/BlockWire.java");
        assertTrue(block.contains("public static void envelope(@NonNull WireContext ctx, @NonNull WireNode out) {\n    }"), block);
    }

    @Test
    void aStandaloneTypeGetsAPublicEncoderThatWritesTheAbsentValuesForNull() {
        Inputs base = ComponentGeneratorTest.inputs();
        JsonObject components = ComponentGeneratorTest.components();
        components.getAsJsonArray("components").add(ComponentGeneratorTest.json("""
            {"id": "item:description", "kind": "item", "category": "description", "name": "description", "status": "active",
             "doc": "The description.", "page": "", "experiments": [], "forms": [{"type": "object"}], "notes": [],
             "fields": [{"path": "menu", "types": [{"type": "object"}], "required": false, "enumValues": [], "examples": [], "doc": ""},
                        {"path": "menu.tab", "types": [{"type": "string"}], "required": false, "enumValues": ["blocks", "none"],
                         "examples": [], "doc": ""}]}"""));
        JsonObject mappings = ComponentGeneratorTest.mappings();
        mappings.getAsJsonObject("components").add("item:description", ComponentGeneratorTest.json("""
            {"windows": [{"status": "sent", "wire": ["props.tab"], "protocols": [100, 200], "fields": {
              "menu": {"status": "inferred", "rules": [], "whenAbsent": {"props.tab": "9 (int)"}},
              "menu.tab": {"status": "inferred", "rules": [{"kind": "table", "table": {"blocks": "1", "none": "9"}, "nbt": "int", "tags": [],
                           "wire": "props.tab"}], "whenAbsent": {"props.tab": "9 (int)"}}}}]}"""));
        JsonObject api = base.api().deepCopy();
        api.add("standalone", ComponentGeneratorTest.json("{\"item:description\": \"Menu\"}"));
        api.add("standalonePaths", ComponentGeneratorTest.json("{\"item:description\": \"menu\"}"));
        Inputs in = new Inputs(components, base.fixtures(), base.feedback(), mappings, base.transforms(), api, base.since());
        String item = itemWire(in);
        assertTrue(item.contains("""
                public static void menu(@Nullable Menu c, @NonNull WireContext ctx, @NonNull WireNode out) {
                    if (c == null) {
                        out.put("props.tab", 9);
                        return;
                    }
            """), item);
        assertTrue(item.contains("case \"blocks\" -> out.put(\"props.tab\", 1);"), item);
        // A report entry names the standalone type by its NAME, the key in the description
        assertTrue(item.contains("default -> ctx.drop(Menu.NAME, "), item);
    }
}
