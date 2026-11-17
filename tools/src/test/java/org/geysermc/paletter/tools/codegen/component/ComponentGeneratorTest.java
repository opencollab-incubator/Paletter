package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.Golden;
import org.geysermc.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import org.geysermc.paletter.tools.codegen.component.ComponentGenerator.Inputs;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                 {"path": "offset", "types": [{"type": "vec3"}], "defaultValue": [0, 1, 0], "required": false, "enumValues": [], "examples": [], "doc": ""},
                 {"path": "rarity", "types": [{"type": "string"}], "required": false, "enumValues": ["common", "rare"], "examples": [], "doc": "How rare."},
                 {"path": "stripes", "types": [{"type": "boolean"}, {"type": "array", "items": {"type": "string"}}], "required": false,
                  "enumValues": [], "examples": [], "doc": "Stripes on or off, or the striped parts."},
                 {"path": "fuel", "types": [{"type": "string"}], "required": false, "enumValues": [], "examples": [], "doc": "The item it burns."},
                 {"path": "reach", "types": [{"type": "number"}], "defaultValue": 3, "required": true, "enumValues": [], "examples": [],
                  "doc": "The docs require it; BDS takes it omitted."}
               ], "notes": ["Renamed from `test:former` in 1.0."]},
              {"id": "item:test:gadget", "kind": "item", "category": "component", "name": "test:gadget", "status": "deprecated",
               "doc": "Old. Use test:widget instead.", "page": "", "experiments": ["Test Toggle"], "forms": [{"type": "object"}, {"type": "boolean"}],
               "fields": [{"path": "value", "types": [{"type": "boolean"}], "required": false, "enumValues": [], "examples": [], "doc": "On."},
                          {"path": "level", "types": [{"type": "integer"}], "required": false, "enumValues": [], "examples": [],
                           "doc": "Only sent from protocol 200 on."}],
               "notes": []},
              {"id": "block:test:hidden", "kind": "block", "category": "component", "name": "test:hidden", "status": "active",
               "doc": "Server side.", "page": "creator/Reference/Content/BlockReference/Examples/BlockComponents/minecraftBlock_hidden.md", "experiments": [], "forms": [{"type": "object"}], "fields": [], "notes": []},
              {"id": "block:test:slab", "kind": "block", "category": "component", "name": "test:slab", "status": "active",
               "doc": "Parts.", "page": "creator/Reference/Content/BlockReference/Examples/BlockComponents/minecraftBlock_slab.md", "experiments": [],
               "forms": [{"type": "array", "items": {"type": "object"}}, {"type": "object"}],
               "fields": [{"path": "[].size", "types": [{"type": "number"}], "defaultValue": 16, "required": false, "enumValues": [], "examples": [], "doc": "Size."}],
               "notes": []},
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
            {"captures": [{"capture": "data/bds/8.8.8/spec", "bdsVersion": "8.8.8", "protocol": 100, "windowProtocol": 100,
                           "firstCustomBlockId": 10000, "blockItemIdPlusBlockId": 255},
                         {"capture": "data/bds/9.9.9/spec", "bdsVersion": "9.9.9", "protocol": 200, "windowProtocol": 200,
                           "firstCustomBlockId": 10000, "blockItemIdPlusBlockId": 255}],
             "traitStates": {"test:turn": {
               "test:facing": [{"name": "test:facing", "type": "string", "values": ["south", "north"],
                                "valuesFrom": "the vanilla state test:facing", "default": "verified (name and default): a hash"}],
               "test:links": [{"name": "test:link_a", "type": "bool", "values": [0, 1], "valuesFrom": "the vanilla state test:link_a",
                               "default": "unverified: no hash"},
                              {"name": "test:part", "type": "int", "values": [0, 1, 2], "valuesFrom": "the docs", "default": "unverified: no hash"}]}},
             "components": {
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
                  "rarity": {"status": "inferred", "rules": [{"kind": "table", "table": {"common": "common", "odd": "common", "rare": "rare"}, "nbt": "string",
                             "tags": [], "wire": "test:widget.rarity"}], "whenAbsent": {"test:widget.rarity": "common (string)"}},
                  "stripes": {"status": "hand-rule"},
                  "fuel": {"status": "inferred", "rules": [{"kind": "identity", "nbt": "string", "tags": [], "wire": "test:widget.fuel"}]},
                  "reach": {"status": "inferred", "rules": [{"kind": "identity", "nbt": "float", "tags": [], "wire": "test:widget.reach"}],
                            "whenAbsent": {"test:widget.reach": "3.0 (float)"}},
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
              "block:test:slab": {"status": "sent", "wire": ["test:slab"], "fields": {"[].size": {"status": "hand-rule"}}, "protocols": [100],
                "protocol200": {"status": "sent", "wire": ["test:slab"], "forms": {"object": {"status": "rejected"}},
                  "fields": {"[].size": {"status": "hand-rule"}}}},
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
            Map.of("item:test:widget:colors", "testMap", "item:test:widget:stripes", "testMap", "block:test:slab:[]", "testMap"),
            json("{\"since\": \"0.1.0\", \"keyedExtras\": {\"item:test:widget\": "
                + "{\"from\": \"nested\", \"field\": \"moreNested\", \"doc\": \"Nested things under any other key.\"}}, "
                + "\"openEnums\": [\"item:test:widget:rarity\"], \"optional\": [\"item:test:widget:reach\"], "
                + "\"descriptors\": {\"item:test:widget:fuel\": \"item\"}, "
                + "\"unions\": {\"item:test:widget:stripes\": {\"use\": \"boolean\", \"alternative\": {\"array<string>\": \"names\"}}}, "
                + "\"rootLists\": {\"block:test:slab\": \"parts\"}, "
                + "\"forms\": {\"block:test:slab\": {\"object\": {\"listElementOf\": \"parts\"}}}}"), "9.0.0");
    }

    // ---- Golden files --------------------------------------------------------------------------------------------

    @Test
    void generatesTheGoldenSources() throws IOException {
        Map<String, String> files = ComponentGenerator.generate(inputs());
        Golden.assertDirectory(GOLDEN, files);
    }

    @Test
    void theModelIsWhatTheGoldenSourcesShow() {
        ApiModel model = ComponentGenerator.model(inputs());
        assertEquals(Set.of("item:test:gadget", "block:test:glow", "item:test:widget", "block:test:hidden", "item:test:old", "item:test:former",
                "item:test:count", "item:test:ancient", "item:test:quiet", "block:test:slab"),
            model.components.stream().map(c -> c.id).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(), model.omitted.keySet(), "server only and rejected components are generated too");
        // Server only in every window: the whole range, no bounds
        ApiModel.Comp hidden = model.component("block:test:hidden");
        assertTrue(hidden.serverOnly != null && !hidden.serverOnly.bounded());
        assertTrue(hidden.neverSent());
        assertEquals(null, hidden.rejected);
        // Rejected in every window (the oldest capture included)
        ApiModel.Comp old = model.component("item:test:old");
        assertTrue(old.rejected != null && old.rejected.since() == Integer.MIN_VALUE);
        assertEquals(null, old.serverOnly);
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
    void aScaleOfOneOverNDivides() {
        // degrees -> quarter turns: 90 * 0.011111 would truncate to 0 in an int leaf, 90 / 90.0 does not
        Inputs in = inputs();
        in.mappings().getAsJsonObject("components").getAsJsonObject("item:test:widget").getAsJsonObject("fields").getAsJsonObject("speed")
            .getAsJsonArray("rules").get(0).getAsJsonObject().addProperty("scale", 0.011111);
        String source = ComponentGenerator.generate(in).get("org/geysermc/paletter/internal/component/ItemWire.java");
        assertTrue(source.contains(" / 90.0 + 0.0)"), source);
    }

    @Test
    void theRecordConstructorChecksTheLimitsOfAValueForm() {
        // What BDS says about the component's value itself (path "") limits the field of its value form
        Inputs in = inputs();
        in.feedback().add("block:test:glow", json("{\"\": [\"max 15\"]}"));
        String glow = ComponentGenerator.generate(in).get("org/geysermc/paletter/block/component/Glow.java");
        assertTrue(glow.contains("""
                public Glow {
                    Check.range("Glow.value", value, null, 15.0);
                }
            """), glow);
        // Text the generator writes keeps its Javadoc tags
        assertTrue(glow.contains("(add-on JSON {@code \"test:glow\": <integer>})"), glow);
    }

    /**
     * A component whose object {@code geo} has an inferred {@code id} and a {@code bones} map only a hand transform compiles.
     * BDS of protocol 100 never varied the map (a constant), BDS of protocol 200 did; the variants of {@code bones} were
     * compared with the control, so its rules also name the sibling's leaf.
     */
    private static Inputs lamp() {
        Inputs in = inputs();
        in.components().getAsJsonArray("components").add(json("""
            {"id": "block:test:lamp", "kind": "block", "category": "component", "name": "test:lamp", "status": "active",
             "doc": "A lamp.", "page": "", "experiments": [], "forms": [{"type": "object"}], "notes": [],
             "fields": [
               {"path": "geo", "types": [{"type": "object", "ref": "Geo"}], "required": true, "enumValues": [], "examples": [], "doc": "The geometry."},
               {"path": "geo.id", "types": [{"type": "string"}], "required": false, "enumValues": [], "examples": [], "doc": "Its name."},
               {"path": "geo.bones", "types": [{"type": "map", "items": {"type": "string"}}], "defaultValue": {}, "required": false,
                "enumValues": [], "examples": [], "doc": "Its bones."}
             ]}"""));
        String id = """
            "geo.id": {"status": "inferred", "rules": [{"kind": "identity", "nbt": "string", "tags": [], "wire": "test:lamp.geo.id"}],
                       "whenAbsent": {"test:lamp.geo.id": "none (string)"}}""";
        in.mappings().getAsJsonObject("components").add("block:test:lamp", json("""
            {"status": "sent", "wire": ["test:lamp"], "protocols": [100],
             "constants": {"test:lamp.geo.bones": "{} (compound)", "test:lamp.geo.solid": "0 (byte)"},
             "fields": {"geo": {"status": "inferred", "rules": []}, %s, "geo.bones": {"status": "dropped"}},
             "protocol200": {"status": "sent", "wire": ["test:lamp"], "protocols": [200],
               "constants": {"test:lamp.geo.solid": "0 (byte)"},
               "fields": {"geo": {"status": "inferred", "rules": []}, %s,
                 "geo.bones": {"status": "hand-rule", "transform": "testMap",
                   "rules": [{"kind": "empty-map", "nbt": "compound", "tags": [], "wire": "test:lamp.geo.bones"},
                             {"kind": "constant", "value": "1.000000", "mapEntry": true, "nbt": "string", "tags": [], "wire": "test:lamp.geo.bones.arm"},
                             {"kind": "constant", "value": "paletter", "nbt": "string", "tags": ["injected-default"], "wire": "test:lamp.geo.id"}],
                   "whenAbsent": {"test:lamp.geo.bones": "{} (compound)", "test:lamp.geo.id": "none (string)"}}}}}""".formatted(id, id)));
        Map<String, String> transforms = new java.util.TreeMap<>(in.transforms());
        transforms.put("block:test:lamp:geo.bones", "testMap");
        return new Inputs(in.components(), in.fixtures(), in.feedback(), in.mappings(), transforms, in.api(), in.since());
    }

    @Test
    void aFieldTransformSitsBesideInferredSiblings() {
        String wire = ComponentGenerator.generate(lamp()).get("org/geysermc/paletter/internal/component/BlockWire.java");
        String lamp = wire.substring(wire.indexOf("private static void lamp("));
        lamp = lamp.substring(0, lamp.indexOf("\n    }\n") + 1);
        // The sibling keeps its inferred rule and its absent value, although the transformed field's variants touched its leaf
        assertTrue(lamp.contains("out.put(\"test:lamp.geo.id\", v1);"), lamp);
        assertTrue(lamp.contains("out.put(\"test:lamp.geo.id\", \"none\");"), lamp);
        // The transform is called with the object that holds the field, after the generic rules
        assertTrue(lamp.indexOf("Transforms.testMap(ctx, out, v0);") > lamp.indexOf("out.put(\"test:lamp.geo.id\", v1);"), lamp);
        // What the transform writes is not written as a constant first; a constant nothing else writes still is
        assertFalse(lamp.contains("\"test:lamp.geo.bones\""), lamp);
        assertTrue(lamp.contains("out.put(\"test:lamp.geo.solid\", (byte) 0);"), lamp);
        // so the two windows encode alike and are one
        assertFalse(wire.contains("lampUntil200") || wire.contains("lampFrom200"), "the windows differ only in a constant the transform overwrites");
    }

    @Test
    void theRecordConstructorChecksAnOrderBdsDemands() {
        // bds-feedback.json: BDS refused a max below the min of the object
        Inputs in = lamp();
        in.components().getAsJsonArray("components").asList().stream().map(JsonElement::getAsJsonObject)
            .filter(c -> c.get("id").getAsString().equals("block:test:lamp")).findFirst().orElseThrow().getAsJsonArray("fields").addAll(json("""
                {"fields": [
                  {"path": "geo.span", "types": [{"type": "object", "ref": "Span"}], "required": false, "enumValues": [], "examples": [], "doc": ""},
                  {"path": "geo.span.min", "types": [{"type": "integer"}], "defaultValue": 0, "required": false, "enumValues": [], "examples": [], "doc": ""},
                  {"path": "geo.span.max", "types": [{"type": "integer"}], "required": false, "enumValues": [], "examples": [], "doc": ""}
                ]}""").getAsJsonArray("fields"));
        in.feedback().add("block:test:lamp", json("{\"geo.span\": [\"order min max\"]}"));
        String lamp = ComponentGenerator.generate(in).get("org/geysermc/paletter/block/component/Lamp.java");
        // max is not required, so it can be unset: checked only when it is set
        assertTrue(lamp.contains("""
                        if (max != null) {
                            Check.order("Lamp.Span", "min", min, "max", max);
                        }
            """), lamp);
        assertTrue(lamp.contains("Not below {@code min} (BDS "), lamp);
    }

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
    void capturesThatNumberCustomBlocksDifferentlyFail() {
        fails("the spec captures differ in firstCustomBlockId ([10000, 20000]): the code using it needs a protocol window",
            in -> in.mappings().getAsJsonArray("captures").get(1).getAsJsonObject().addProperty("firstCustomBlockId", 20000));
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

    // ---- Forms BDS accepts that the docs do not model ---------------------------------------------------------------

    private static final String WIDGET_WIRE = "org/geysermc/paletter/internal/component/ItemWire.java";

    @Test
    void anOpenEnumSendsWhatBdsSentForUndocumentedValues() {
        // The table has "odd -> common" from an undocumented sample: every string the docs do not list is "common"
        String wire = ComponentGenerator.generate(inputs()).get(WIDGET_WIRE);
        assertTrue(wire.contains("default -> out.put(\"test:widget.rarity\", \"common\")"), wire);
        assertTrue(!wire.contains("case \"odd\""), "the undocumented sample is the default, not a case");
        String widget = ComponentGenerator.generate(inputs()).get("org/geysermc/paletter/item/component/Widget.java");
        assertTrue(widget.contains("@Nullable String rarity"), widget);
        assertTrue(widget.contains("Documented values: {@code common}, {@code\n *        rare}; BDS takes other strings too."), widget);
        assertTrue(!widget.contains("enum Rarity"), "an open enum has no constants");
    }

    @Test
    void anOpenEnumWhoseUndocumentedSamplesDisagreeKeepsTheirCasesAndDropsTheRest() {
        Inputs in = inputs();
        in.mappings().getAsJsonObject("components").getAsJsonObject("item:test:widget").getAsJsonObject("fields").getAsJsonObject("rarity")
            .getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonObject("table").addProperty("odd_too", "rare");
        String wire = ComponentGenerator.generate(in).get(WIDGET_WIRE);
        assertTrue(wire.contains("case \"odd\" -> out.put(\"test:widget.rarity\", \"common\")"), wire);
        assertTrue(wire.contains("case \"odd_too\" -> out.put(\"test:widget.rarity\", \"rare\")"), wire);
        assertTrue(wire.contains("default -> ctx.drop(Widget.NAME, \"rarity = \" + v16 + \" has no known wire value\")"), wire);
    }

    @Test
    void anOpenEnumWithoutUndocumentedSamplesDropsUnknownValues() {
        Inputs in = inputs();
        in.mappings().getAsJsonObject("components").getAsJsonObject("item:test:widget").getAsJsonObject("fields").getAsJsonObject("rarity")
            .getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonObject("table").remove("odd");
        assertTrue(ComponentGenerator.generate(in).get(WIDGET_WIRE).contains("default -> ctx.drop(Widget.NAME, \"rarity = \""));
    }

    @Test
    void anOptionalFieldIsNotRequiredWhateverTheDocsSay() {
        String widget = ComponentGenerator.generate(inputs()).get("org/geysermc/paletter/item/component/Widget.java");
        assertTrue(widget.contains("private @Nullable Float reach = 3.0f;"), widget);
        assertTrue(!widget.contains("Widget.reach is required"), widget);
        Inputs in = inputs();
        in.api().remove("optional");
        assertTrue(ComponentGenerator.generate(in).get("org/geysermc/paletter/item/component/Widget.java").contains("Widget.reach is required"));
    }

    @Test
    void aUnionAlternativeMayBeAListOfStrings() {
        String widget = ComponentGenerator.generate(inputs()).get("org/geysermc/paletter/item/component/Widget.java");
        assertTrue(widget.contains("@Nullable List<String> stripesNames"), widget);
        assertTrue(widget.contains("public @NonNull Builder stripesNames(@NonNull String... stripesNames)"), widget);
        assertTrue(widget.contains("Widget sets both stripes and stripesNames"), widget);
    }

    @Test
    void anObjectFormThatIsOneEntryOfTheListIsAFactoryAndAnIndexEntry() {
        Map<String, String> files = ComponentGenerator.generate(inputs());
        String slab = files.get("org/geysermc/paletter/block/component/Slab.java");
        assertTrue(slab.contains("public static @NonNull Slab of(@NonNull Entry entry)"), slab);
        assertTrue(slab.contains("return builder().parts(entry).build();"), slab);
        assertTrue(slab.contains("BDS rejects this form since 9.9.9 (protocol 200); the list is accepted by every captured BDS."), slab);
        assertTrue(files.get("org/geysermc/paletter/internal/component/ComponentIndex.java")
            .contains("FORMS.put(Slab.class, Map.of(\"array<object>\", \"parts\", \"object\", \"parts[]\"))"));
        // Without the declaration the object form is not offered
        Inputs in = inputs();
        in.api().getAsJsonObject("forms").remove("block:test:slab");
        assertTrue(!ComponentGenerator.generate(in).get("org/geysermc/paletter/block/component/Slab.java").contains("of(@NonNull Entry entry)"));
    }

    @Test
    void anElementFormNeedsItsFormAndAListOfObjects() {
        fails("api.json forms: block:test:slab object is an entry of 'nothing', which is no list of objects",
            in -> in.api().getAsJsonObject("forms").getAsJsonObject("block:test:slab").getAsJsonObject("object").addProperty("listElementOf", "nothing"));
        fails("api.json forms: block:test:slab has no boolean value form for listElementOf", in -> {
            JsonObject forms = in.api().getAsJsonObject("forms").getAsJsonObject("block:test:slab");
            forms.add("boolean", forms.remove("object"));
        });
    }

    @Test
    void descriptorFieldsAreIndexedForTools() {
        assertTrue(ComponentGenerator.generate(inputs()).get("org/geysermc/paletter/internal/component/ComponentIndex.java")
            .contains("DESCRIPTORS.put(Widget.class, Map.of(\"fuel\", \"item\"))"));
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
                shuffleKeys(in.mappings(), random), in.transforms(), shuffleKeys(in.api(), random), in.since());
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
        fails("conflicting rules: item:test:widget [count, label] all decide the wire leaf test:widget.count; name a transform for one of them in HandTransforms",
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
            in -> in.components().getAsJsonArray("components").get(4).getAsJsonObject().getAsJsonArray("forms").add(json("{\"type\": \"boolean\"}")));
        fails("ambiguous union: item:test:widget shape has a scalar form that fills no single field of Shape; settle it in api.json unions", in -> {
            JsonArray fields = in.components().getAsJsonArray("components").get(0).getAsJsonObject().getAsJsonArray("fields");
            fields.asList().removeIf(f -> f.getAsJsonObject().get("path").getAsString().equals("shape.id"));
            in.mappings().getAsJsonObject("components").getAsJsonObject("item:test:widget").getAsJsonObject("fields").remove("shape");
        });
        fails("list depth mismatch: item:test:widget label writes test:widget.list[].label from the component; name a transform for it in HandTransforms",
            in -> in.mappings().getAsJsonObject("components").getAsJsonObject("item:test:widget").getAsJsonObject("fields")
                .getAsJsonObject("label").getAsJsonArray("rules").get(0).getAsJsonObject().addProperty("wire", "test:widget.list[].label"));
    }
}
