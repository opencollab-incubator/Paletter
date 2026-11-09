package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import org.geysermc.paletter.tools.codegen.component.ComponentGenerator.Inputs;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The component API generator on a small synthetic model, compared file by file with the committed expected sources in
 * {@code src/test/resources/component-golden}. The model has every field kind the generator handles: a scalar with a
 * default and a BDS range, a required string, an enum written through a value table, a default BDS contradicts (left
 * unset), a nested object, a list of scalars, a list of objects, a map compiled by a hand transform, a union with a
 * scalar shorthand, a map for the keys of a keyed set, a vector, a boolean value form, a component constant, a protocol split (a field only the newer window
 * sends is dropped with a report warning in the older one), and deprecated and
 * experiment-gated components; a server-only component is left out.
 * <p>
 * After an intended change of the output: {@code ./gradlew :tools:test -PupdateGolden=true}, then review the diff.
 */
class ComponentGeneratorTest {
    private static final Path GOLDEN = Path.of("src/test/resources/component-golden");
    private static final Set<String> TRANSFORMS = Set.of("test_map");

    // ---- Fixture -------------------------------------------------------------------------------------------------

    static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    static JsonObject components() {
        return json("""
            {"lists": {"item": "creator/Reference/Content/ItemReference/Examples/ItemComponentList.md",
                       "block": "creator/Reference/Content/BlockReference/Examples/BlockComponents/BlockComponentsList.md"},
             "components": [
              {"id": "item:test:widget", "kind": "item", "category": "component", "name": "test:widget", "status": "active",
               "doc": "A widget with `every` field kind.", "page": "creator/Reference/Content/ItemReference/Examples/ItemComponents/test_widget.md", "minFormatVersion": "1.20.0", "experiments": [],
               "forms": [{"type": "object"}],
               "fields": [
                 {"path": "count", "types": [{"type": "integer"}], "defaultValue": 1, "required": false, "enumValues": [], "examples": [], "doc": "How many."},
                 {"path": "label", "types": [{"type": "string"}], "required": true, "enumValues": [], "examples": [], "doc": "The label."},
                 {"path": "mode", "types": [{"type": "string", "ref": "Mode"}], "defaultValue": "fast", "required": false,
                  "enumValues": ["fast", "slow"], "examples": [], "doc": "The mode."},
                 {"path": "speed", "types": [{"type": "number"}], "defaultValue": 2, "required": false, "enumValues": [], "examples": [],
                  "doc": "The docs say 2; BDS sends 1.5 when omitted."},
                 {"path": "nested", "types": [{"type": "object", "ref": "Nested Thing"}], "required": false, "enumValues": [], "examples": [], "doc": "An object."},
                 {"path": "nested.x", "types": [{"type": "number"}], "defaultValue": 0, "required": false, "enumValues": [], "examples": [], "doc": ""},
                 {"path": "nested.flag", "types": [{"type": "boolean"}], "defaultValue": false, "required": false, "enumValues": [], "examples": [], "doc": ""},
                 {"path": "tags", "types": [{"type": "array", "items": {"type": "string"}}], "defaultValue": [], "required": false,
                  "enumValues": [], "examples": [], "doc": "Tags."},
                 {"path": "entries", "types": [{"type": "array", "items": {"type": "object", "ref": "Entry"}}], "required": false,
                  "enumValues": [], "examples": [], "doc": "Entries."},
                 {"path": "entries[].name", "types": [{"type": "string"}], "required": false, "enumValues": [], "examples": [], "doc": ""},
                 {"path": "colors", "types": [{"type": "map", "items": {"type": "string"}}], "required": false, "enumValues": [],
                  "examples": [], "doc": "Colors by part."},
                 {"path": "shape", "types": [{"type": "string"}, {"type": "object", "ref": "Shape"}], "required": false, "enumValues": [],
                  "examples": [], "doc": "A shape or its id."},
                 {"path": "shape.id", "types": [{"type": "string"}], "required": false, "enumValues": [], "examples": [], "doc": ""},
                 {"path": "shape.size", "types": [{"type": "integer"}], "defaultValue": 1, "required": false, "enumValues": [], "examples": [], "doc": ""},
                 {"path": "offset", "types": [{"type": "vec3"}], "defaultValue": [0, 1, 0], "required": false, "enumValues": [], "examples": [], "doc": ""}
               ], "notes": ["Renamed from `test:former` in 1.0."]},
              {"id": "item:test:gadget", "kind": "item", "category": "component", "name": "test:gadget", "status": "deprecated",
               "doc": "Old. Use test:widget instead.", "page": "", "experiments": ["Test Toggle"], "forms": [{"type": "object"}, {"type": "boolean"}],
               "fields": [{"path": "value", "types": [{"type": "boolean"}], "required": false, "enumValues": [], "examples": [], "doc": "On."},
                          {"path": "level", "types": [{"type": "integer"}], "required": false, "enumValues": [], "examples": [],
                           "doc": "Only sent from protocol 200 on."}],
               "notes": []},
              {"id": "block:test:hidden", "kind": "block", "category": "component", "name": "test:hidden", "status": "active",
               "doc": "Server side.", "page": "creator/Reference/Content/BlockReference/Examples/BlockComponents/minecraftBlock_hidden.md", "experiments": [], "forms": [{"type": "object"}], "fields": [], "notes": []},
              {"id": "block:test:glow", "kind": "block", "category": "component", "name": "test:glow", "status": "active",
               "doc": "Light.", "page": "creator/Reference/Content/BlockReference/Examples/BlockComponents/minecraftBlock_glow.md", "experiments": [], "forms": [{"type": "integer"}], "fields": [], "notes": []},
              {"id": "item:test:old", "kind": "item", "category": "component", "name": "test:old", "status": "deprecated",
               "doc": "Gone.", "page": "creator/Reference/Content/ItemReference/Examples/ItemComponents/test_old.md", "experiments": [],
               "forms": [{"type": "object"}], "fields": [], "notes": []},
              {"id": "item:test:former", "kind": "item", "category": "component", "name": "test:former", "status": "deprecated",
               "doc": "Gone.", "page": "creator/Reference/Content/ItemReference/Examples/ItemComponents/test_former.md", "experiments": [],
               "forms": [{"type": "object"}], "fields": [], "notes": []},
              {"id": "item:test:count", "kind": "item", "category": "component", "name": "test:count", "status": "deprecated",
               "doc": "Gone.", "page": "creator/Reference/Content/ItemReference/Examples/ItemComponents/test_count.md", "experiments": [],
               "forms": [{"type": "object"}], "fields": [], "notes": []},
              {"id": "item:test:quiet", "kind": "item", "category": "component", "name": "test:quiet", "status": "active",
               "doc": "Server side.", "page": "creator/Reference/Content/ItemReference/Examples/ItemComponents/test_quiet.md", "experiments": [],
               "forms": [{"type": "object"}], "fields": [], "notes": []},
              {"id": "item:test:ancient", "kind": "item", "category": "component", "name": "test:ancient", "status": "deprecated",
               "doc": "Gone.", "page": "not/below/creator.md", "experiments": [], "forms": [{"type": "object"}], "fields": [], "notes": []}
            ]}""");
    }

    static JsonObject mappings() {
        return json("""
            {"captures": [{"capture": "data/bds/8.8.8/spec", "bdsVersion": "8.8.8", "protocol": 100},
                         {"capture": "data/bds/9.9.9/spec", "bdsVersion": "9.9.9", "protocol": 200}], "components": {
              "item:test:widget": {"status": "sent", "wire": ["test:widget"], "constants": {"test:widget.version": "2 (int)"},
                "fields": {
                  "count": {"status": "inferred", "rules": [{"kind": "identity", "nbt": "int", "tags": [], "wire": "test:widget.count"}],
                            "whenAbsent": {"test:widget.count": "1 (int)"}},
                  "label": {"status": "inferred", "rules": [{"kind": "identity", "nbt": "string", "tags": [], "wire": "test:widget.label"}]},
                  "mode": {"status": "inferred", "rules": [{"kind": "table", "table": {"fast": "0", "slow": "1"}, "nbt": "byte", "tags": [],
                           "wire": "test:widget.mode"}], "whenAbsent": {"test:widget.mode": "0 (byte)"}},
                  "speed": {"status": "inferred", "rules": [{"kind": "linear", "scale": 20.0, "offset": 0.0, "nbt": "int", "tags": [],
                            "wire": "test:widget.ticks"}], "whenAbsent": {"test:widget.ticks": "30 (int)"}},
                  "nested": {"status": "inferred", "rules": [], "whenAbsent": {"test:widget.nested.x": "omitted", "test:widget.nested.flag": "omitted"}},
                  "nested.x": {"status": "inferred", "rules": [{"kind": "identity", "nbt": "float", "tags": [], "wire": "test:widget.nested.x"}]},
                  "nested.flag": {"status": "inferred", "rules": [{"kind": "identity", "nbt": "byte", "tags": [], "wire": "test:widget.nested.flag"}]},
                  "tags": {"status": "inferred", "rules": [{"kind": "identity", "element": 0, "nbt": "string", "tags": [], "wire": "test:widget.tags[]"}]},
                  "entries": {"status": "inferred", "rules": [{"kind": "structure", "tags": [], "wire": "test:widget.entries[].name"}]},
                  "entries[].name": {"status": "inferred", "rules": [{"kind": "identity", "nbt": "string", "tags": [], "wire": "test:widget.entries[].name"}]},
                  "colors": {"status": "hand-rule"},
                  "shape": {"status": "inferred", "rules": [{"kind": "identity", "nbt": "string", "tags": [], "wire": "test:widget.shape.id"}]},
                  "shape.id": {"status": "inferred", "rules": [{"kind": "identity", "nbt": "string", "tags": [], "wire": "test:widget.shape.id"}]},
                  "shape.size": {"status": "inferred", "rules": [{"kind": "identity", "nbt": "short", "tags": [], "wire": "test:widget.shape.size"}]},
                  "offset": {"status": "inferred", "rules": [{"kind": "identity", "element": 0, "nbt": "float", "tags": [], "wire": "test:widget.ox"},
                             {"kind": "identity", "element": 1, "nbt": "float", "tags": [], "wire": "test:widget.oy"},
                             {"kind": "identity", "element": 2, "nbt": "float", "tags": [], "wire": "test:widget.oz"}]}
                }, "protocols": [100]},
              "item:test:gadget": {"status": "sent", "wire": ["test:gadget"],
                "forms": {"boolean": {"status": "inferred", "rules": [{"kind": "identity", "nbt": "byte", "tags": [], "wire": "test:gadget.value"}]}},
                "fields": {"value": {"status": "inferred", "rules": [{"kind": "identity", "nbt": "byte", "tags": [], "wire": "test:gadget.value"}]}},
                "protocols": [100],
                "protocol200": {"status": "sent", "wire": ["test:gadget"],
                  "fields": {"value": {"status": "inferred", "rules": [{"kind": "identity", "nbt": "byte", "tags": [], "wire": "test:gadget.on"}]},
                             "level": {"status": "inferred", "rules": [{"kind": "identity", "nbt": "int", "tags": [], "wire": "test:gadget.level"}]}}}},
              "block:test:hidden": {"status": "server-only", "fields": {}},
              "block:test:glow": {"status": "server-only", "fields": {}, "protocols": [100],
                "protocol200": {"status": "sent", "wire": ["test:glow"],
                  "forms": {"integer": {"status": "inferred", "rules": [{"kind": "identity", "nbt": "byte", "tags": [], "wire": "test:glow.emission"}]}},
                  "fields": {}}},
              "item:test:old": {"status": "rejected", "note": "item:test:old has been deprecated. Use test:widget instead", "fields": {}, "protocols": [100]},
              "item:test:former": {"status": "rejected", "fields": {}, "protocols": [100]},
              "item:test:count": {"status": "rejected", "fields": {}, "protocols": [100]},
              "item:test:quiet": {"status": "server-only", "fields": {}, "protocols": [100]},
              "item:test:ancient": {"status": "rejected", "fields": {}, "protocols": [100]}
            }}""");
    }

    static Inputs inputs() {
        return new Inputs(components(), json("{\"errata\": [], \"replacedBy\": {\"item:test:ancient\": {\"by\": [\"item:test:widget\"], \"evidence\": \"hand evidence\"}}}"),
            json("{\"item:test:widget\": {\"count\": [\"range 1 64\"]}}"), mappings(),
            json("{\"item:test:widget:colors\": {\"transform\": \"test_map\", \"why\": \"a map\"}}"),
            json("{\"since\": \"1.0.0\", \"keyedExtras\": {\"item:test:widget\": "
                + "{\"from\": \"nested\", \"field\": \"moreNested\", \"doc\": \"Nested things under any other key.\"}}}"), TRANSFORMS, "9.0.0");
    }

    // ---- Golden files --------------------------------------------------------------------------------------------

    @Test
    void generatesTheGoldenSources() throws IOException {
        Map<String, String> files = ComponentGenerator.generate(inputs());
        if (Boolean.getBoolean("paletter.updateGolden")) {
            if (Files.isDirectory(GOLDEN)) {
                try (Stream<Path> walk = Files.walk(GOLDEN)) {
                    for (Path p : walk.sorted(Collections.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }
            }
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path p = GOLDEN.resolve(file.getKey());
                Files.createDirectories(p.getParent());
                Files.writeString(p, file.getValue(), StandardCharsets.UTF_8);
            }
        }
        Set<String> golden = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(GOLDEN)) {
            walk.filter(Files::isRegularFile).forEach(p -> golden.add(GOLDEN.relativize(p).toString().replace('\\', '/')));
        }
        assertEquals(golden, files.keySet(), "generated file set");
        for (Map.Entry<String, String> file : files.entrySet()) {
            String expected = Files.readString(GOLDEN.resolve(file.getKey()), StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertEquals(expected, file.getValue(), file.getKey());
        }
    }

    @Test
    void theModelIsWhatTheGoldenSourcesShow() {
        ApiModel model = ComponentGenerator.model(inputs());
        assertEquals(List.of("item:test:gadget", "block:test:glow", "item:test:widget"),
            model.components.stream().map(c -> c.id).sorted((a, b) -> a.substring(a.indexOf(':')).compareTo(b.substring(b.indexOf(':')))).toList());
        assertTrue(model.omitted.get("block:test:hidden").startsWith("server only"), model.omitted.toString());
        assertEquals(Set.of("item:test:old", "item:test:former", "item:test:count", "item:test:ancient", "item:test:quiet"),
            model.omitted.keySet().stream().filter(id -> id.startsWith("item:test:") && !id.equals("item:test:widget")).collect(java.util.stream.Collectors.toSet()));
        ApiModel.Comp widget = model.components.stream().filter(c -> c.id.equals("item:test:widget")).findFirst().orElseThrow();
        ApiModel.Field speed = widget.root.fields.stream().filter(f -> f.json.equals("speed")).findFirst().orElseThrow();
        assertEquals(null, speed.defaultValue, "BDS contradicts the docs default: left unset");
        ApiModel.Field count = widget.root.fields.stream().filter(f -> f.json.equals("count")).findFirst().orElseThrow();
        assertEquals(1.0, count.min);
        assertEquals(64.0, count.max);
        ApiModel.Comp gadget = model.components.stream().filter(c -> c.id.equals("item:test:gadget")).findFirst().orElseThrow();
        assertEquals(2, gadget.windows.size());
        assertEquals(Integer.MIN_VALUE, gadget.windows.get(0).since());
        assertEquals(200, gadget.windows.get(0).until());
        assertEquals(200, gadget.windows.get(1).since());
    }

    // ---- Links and annotations -----------------------------------------------------------------------------------

    @Test
    void learnLinksFollowTheDocsPaths() {
        assertEquals("https://learn.microsoft.com/en-us/minecraft/creator/reference/content/itemreference/examples/itemcomponents/minecraft_render_offsets?view=minecraft-bedrock-stable",
            Docs.learnUrl("creator/Reference/Content/ItemReference/Examples/ItemComponents/minecraft_render_offsets.md"));
        assertEquals("https://learn.microsoft.com/en-us/minecraft/creator/reference/content/blockreference/examples/blockcomponents/minecraftblock_light_emission?view=minecraft-bedrock-stable",
            Docs.learnUrl("creator/Reference/Content/BlockReference/Examples/BlockComponents/minecraftBlock_light_emission.md"));
        assertEquals(null, Docs.learnUrl(""));
        assertEquals(null, Docs.learnUrl(null));
        assertEquals(null, Docs.learnUrl("not/below/creator.md"));
        assertEquals(null, Docs.learnUrl("creator/Reference/Content/Image.png"));
    }

    @Test
    void aComponentThatIsServerOnlyInOneWindowCarriesTheRange() {
        ApiModel model = ComponentGenerator.model(inputs());
        ApiModel.Comp glow = model.component("block:test:glow");
        assertEquals(Integer.MIN_VALUE, glow.serverOnly.since());
        assertEquals(200, glow.serverOnly.until());
        assertEquals(null, model.component("item:test:widget").serverOnly);
        assertEquals(null, model.component("item:test:gadget").serverOnly);
        Map<String, String> files = ComponentGenerator.generate(inputs());
        assertTrue(files.get("org/geysermc/paletter/block/component/Glow.java").contains("@ServerOnly(\n        until = 200\n)"));
        assertTrue(!files.get("org/geysermc/paletter/item/component/Widget.java").contains("@ServerOnly"));
    }

    @Test
    void twoServerOnlyWindowsFail() {
        fails("block:test:glow is server only in 2 protocol windows; @ServerOnly states one range",
            in -> in.mappings().getAsJsonObject("components").getAsJsonObject("block:test:glow")
                .add("protocol300", json("{\"status\": \"server-only\", \"fields\": {}}")));
    }

    @Test
    void replacementsComeFromTheDataAndTheHandEntries() {
        ApiModel model = ComponentGenerator.model(inputs());
        assertEquals(List.of("item:test:widget"), model.replacements.get("item:test:gadget").by());
        assertEquals(List.of("item:test:widget"), model.replacements.get("item:test:old").by());
        assertEquals(List.of("item:test:widget"), model.replacements.get("item:test:former").by());
        assertEquals(List.of("item:test:widget"), model.replacements.get("item:test:count").by());
        assertTrue(model.replacements.get("item:test:ancient").hand());
        assertTrue(model.replacements.values().stream().filter(r -> !r.hand()).allMatch(r -> !r.evidence().isBlank()));
        assertEquals(null, model.replacements.get("item:test:widget"));
        assertEquals(List.of("item:test:widget"), model.component("item:test:gadget").replacement.by());
        String gadget = ComponentGenerator.generate(inputs()).get("org/geysermc/paletter/item/component/Gadget.java");
        assertTrue(gadget.contains("@Deprecated\n@ReplacedBy(Widget.class)"), gadget);
    }

    @Test
    void handReplacementsNeedEvidenceAndKnownComponents() {
        fails("fixtures.json replacedBy item:test:ancient needs an evidence",
            in -> in.fixtures().getAsJsonObject("replacedBy").getAsJsonObject("item:test:ancient").addProperty("evidence", " "));
        fails("fixtures.json replacedBy: unknown component item:test:nothing", in -> in.fixtures().getAsJsonObject("replacedBy")
            .add("item:test:nothing", json("{\"by\": [\"item:test:widget\"], \"evidence\": \"x\"}")));
        fails("fixtures.json replacedBy item:test:ancient: unknown successor item:test:nothing", in -> in.fixtures().getAsJsonObject("replacedBy")
            .getAsJsonObject("item:test:ancient").add("by", json("{\"a\": [\"item:test:nothing\"]}").getAsJsonArray("a")));
        fails("fixtures.json replacedBy item:test:old is derived from the data already (BDS message (data/bds/9.9.9/spec): \"Use test:widget instead\"); remove it",
            in -> in.fixtures().getAsJsonObject("replacedBy")
                .add("item:test:old", json("{\"by\": [\"item:test:widget\"], \"evidence\": \"x\"}")));
    }

    // ---- Determinism ---------------------------------------------------------------------------------------------

    @Test
    void generatingTwiceAndFromShuffledInputGivesTheSameBytes() {
        Map<String, String> first = ComponentGenerator.generate(inputs());
        assertEquals(first, ComponentGenerator.generate(inputs()));
        for (long seed = 1; seed <= 5; seed++) {
            Random random = new Random(seed);
            Inputs in = inputs();
            JsonArray list = in.components().getAsJsonArray("components");
            List<JsonElement> shuffled = new ArrayList<>(list.asList());
            Collections.shuffle(shuffled, random);
            JsonArray reordered = new JsonArray();
            shuffled.forEach(reordered::add);
            in.components().add("components", reordered);
            Inputs shuffledInputs = new Inputs(in.components(), in.fixtures(), shuffleKeys(in.feedback(), random),
                shuffleKeys(in.mappings(), random), shuffleKeys(in.transforms(), random), shuffleKeys(in.api(), random), in.knownTransforms(), in.since());
            assertEquals(first, ComponentGenerator.generate(shuffledInputs), "seed " + seed);
        }
    }

    /** The same object with the keys of every object in it in a random order (arrays keep their order). */
    private static JsonObject shuffleKeys(JsonObject o, Random random) {
        List<String> keys = new ArrayList<>(o.keySet());
        Collections.shuffle(keys, random);
        JsonObject out = new JsonObject();
        for (String key : keys) {
            JsonElement value = o.get(key);
            out.add(key, value.isJsonObject() ? shuffleKeys(value.getAsJsonObject(), random) : value);
        }
        return out;
    }

    // ---- Failures ------------------------------------------------------------------------------------------------

    private static void fails(String message, Consumer<Inputs> change) {
        Inputs in = inputs();
        change.accept(in);
        GenerationException e = assertThrows(GenerationException.class, () -> ComponentGenerator.generate(in));
        assertEquals(message, e.getMessage());
    }

    @Test
    void anUnknownTransformFails() {
        fails("transforms.json: item:test:widget:colors names the transform 'nope', which the runtime does not implement",
            in -> in.transforms().getAsJsonObject("item:test:widget:colors").addProperty("transform", "nope"));
    }

    @Test
    void conflictingWindowsFail() {
        fails("conflicting windows: item:test:gadget has two mappings for protocol 100",
            in -> in.mappings().getAsJsonObject("components").getAsJsonObject("item:test:gadget")
                .add("protocol100", json("{\"status\": \"sent\", \"fields\": {}}")));
    }

    @Test
    void nameCollisionsFail() {
        fails("name collision: item:test:gadget and item:test:widget are both org.geysermc.paletter.item.component.Widget; set one in api.json classes",
            in -> in.api().add("classes", json("{\"item:test:gadget\": \"Widget\"}")));
        fails("name collision: item:test:widget would be Record, which shadows java.lang.Record; set a class name in api.json classes",
            in -> in.api().add("classes", json("{\"item:test:widget\": \"Record\"}")));
        fails("not a Java identifier: 'class' for item:test:widget count; rename it in api.json",
            in -> in.api().add("fields", json("{\"item:test:widget:count\": \"class\"}")));
    }

    @Test
    void twoFieldsDecidingOneLeafFail() {
        fails("conflicting rules: item:test:widget [count, label] all decide the wire leaf test:widget.count; name a transform for one of them in transforms.json",
            in -> in.mappings().getAsJsonObject("components").getAsJsonObject("item:test:widget").getAsJsonObject("fields")
                .getAsJsonObject("label").getAsJsonArray("rules").get(0).getAsJsonObject().addProperty("wire", "test:widget.count"));
    }

    @Test
    void missingDataFails() {
        fails("item:test:widget nested is an object without documented fields; type it in api.json types", in -> {
            JsonArray fields = in.components().getAsJsonArray("components").get(0).getAsJsonObject().getAsJsonArray("fields");
            fields.asList().removeIf(f -> f.getAsJsonObject().get("path").getAsString().startsWith("nested."));
        });
        fails("block:test:glow has the value forms [integer, boolean] but no object fields; name a field per form in api.json forms",
            in -> in.components().getAsJsonArray("components").get(3).getAsJsonObject().getAsJsonArray("forms").add(json("{\"type\": \"boolean\"}")));
        fails("ambiguous union: item:test:widget shape has a scalar form that fills no single field of Shape; settle it in api.json unions", in -> {
            JsonArray fields = in.components().getAsJsonArray("components").get(0).getAsJsonObject().getAsJsonArray("fields");
            fields.asList().removeIf(f -> f.getAsJsonObject().get("path").getAsString().equals("shape.id"));
            in.mappings().getAsJsonObject("components").getAsJsonObject("item:test:widget").getAsJsonObject("fields").remove("shape");
        });
        fails("list depth mismatch: item:test:widget label writes test:widget.list[].label from the component; name a transform for it in transforms.json",
            in -> in.mappings().getAsJsonObject("components").getAsJsonObject("item:test:widget").getAsJsonObject("fields")
                .getAsJsonObject("label").getAsJsonArray("rules").get(0).getAsJsonObject().addProperty("wire", "test:widget.list[].label"));
    }
}
