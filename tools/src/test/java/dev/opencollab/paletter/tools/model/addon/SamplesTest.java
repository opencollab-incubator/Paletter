package dev.opencollab.paletter.tools.model.addon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.model.Fixtures;
import dev.opencollab.paletter.tools.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The samples the rules of the mapping inference need from the add-on: every value of a schema enum, Molang under several keys and
 * in the object form, names in several orders, the booleans of an object in every combination, and boxes with the origin and the
 * size varied together. A model and schema of their own, and the committed ones.
 */
class SamplesTest {
    private static final String ENUM = String.join(", ", IntStream.range(0, 40).mapToObj(i -> "\"v" + i + "\"").toList());

    private static final String MODEL = """
        {"source": "test", "commit": "test", "license": "test", "formatNotes": [], "components": [
          {"id": "block:minecraft:geo", "kind": "block", "category": "component", "name": "minecraft:geo", "status": "active",
           "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "object"}],
           "fields": [
             {"path": "bones", "types": [{"type": "map", "items": {"type": "string"}}], "required": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "lock", "types": [{"type": "array", "items": {"type": "string"}}], "required": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "mode", "types": [{"type": "string"}], "required": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "mode2", "types": [{"type": "string"}], "required": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "faces", "types": [{"type": "object"}], "required": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "faces.a", "types": [{"type": "boolean"}], "required": false, "defaultValue": true, "enumValues": [], "examples": [], "doc": ""},
             {"path": "faces.b", "types": [{"type": "boolean"}], "required": false, "defaultValue": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "faces.c", "types": [{"type": "boolean"}], "required": false, "defaultValue": false, "enumValues": [], "examples": [], "doc": ""}
           ]},
          {"id": "block:minecraft:cube", "kind": "block", "category": "component", "name": "minecraft:cube", "status": "active",
           "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "object"}],
           "fields": [
             {"path": "origin", "types": [{"type": "vec3"}], "required": true, "enumValues": [], "examples": [], "doc": ""},
             {"path": "size", "types": [{"type": "vec3"}], "required": true, "enumValues": [], "examples": [], "doc": ""}
           ]},
          {"id": "block:minecraft:boxes", "kind": "block", "category": "component", "name": "minecraft:boxes", "status": "active",
           "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "array", "items": {"type": "object"}}],
           "fields": [
             {"path": "[].origin", "types": [{"type": "vec3"}], "required": true, "enumValues": [], "examples": [], "doc": ""},
             {"path": "[].size", "types": [{"type": "vec3"}], "required": true, "enumValues": [], "examples": [], "doc": ""}
           ]},
          {"id": "block:minecraft:listed", "kind": "block", "category": "component", "name": "minecraft:listed", "status": "active",
           "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "array", "items": {"type": "object"}}],
           "fields": [
             {"path": "[].origin", "types": [{"type": "vec3"}], "required": true, "enumValues": [], "examples": [], "doc": ""},
             {"path": "[].size", "types": [{"type": "vec3"}], "required": true, "enumValues": [], "examples": [], "doc": ""}
           ]}
        ]}""";

    private static final String FIXTURES = """
        {"formatVersion": "1.26.60", "filler": {}, "errata": [], "mapKeys": {"default": "k", "bones": "bone_a"}, "context": {}, "skip": {}, "requires": {},
         "mapValues": {"bones": ["0.5", "1e3", "-1", "0.25", "2", "3", "true"]},
         "listNames": {"block:minecraft:geo:lock": ["n1", "n2", "n3", "n4", "n5"]},
         "boolCombinations": ["block:minecraft:geo"],
         "together": {"block:minecraft:geo:c": {"extra": 1}},
         "probeOnly": ["block:minecraft:geo:faces.c"],
         "unpinnedEnums": {"block:minecraft:geo:mode2": "test"},
         "samples": {"block:minecraft:geo:bones{}": "q.x"}}""";

    private static final String FEEDBACK = """
        {"block:minecraft:cube": {"": ["box origin size -8,0,-8 8,16,8"]},
         "block:minecraft:listed": {"": ["box origin size -10,0,-10 10,20,10"]}}""";

    private static final String SCHEMA = """
        {"bdsVersion": "1.26.99.1", "formats": {"block": ["1.26.60"]}, "block": {
         "minecraft:geo": [{"formats": ["1.26.60"], "root": {"types": ["object"]}, "fields": {
            "bones": {"types": ["object"]},
            "bones{}": {"types": ["string", "object", "number", "boolean"]},
            "bones{}.expression": {"types": ["string"], "required": true},
            "bones{}.version": {"types": ["integer"], "required": true},
            "mode": {"types": ["string"], "enum": [%s]},
            "mode2": {"types": ["string"], "enum": [%s]}}}],
         "minecraft:cube": [{"formats": ["1.26.60"], "root": {"types": ["object"]}, "fields": {
            "origin": {"types": ["array", "object"]},
            "origin.x": {"types": ["number"], "default": 0.0},
            "origin.y": {"types": ["number"], "default": 0.0},
            "origin.z": {"types": ["number"], "default": 0.0}}}]}}""".formatted(ENUM, ENUM);

    private static AddonGenerator generator(Path root, boolean schema) throws IOException {
        Path model = Files.createDirectories(root.resolve("model"));
        Files.writeString(model.resolve("components.json"), MODEL);
        Files.writeString(model.resolve("fixtures.json"), FIXTURES);
        Files.writeString(model.resolve("bds-feedback.json"), FEEDBACK);
        Files.createDirectories(root.resolve("bds/1.26.99.1"));
        Files.writeString(root.resolve("bds/1.26.99.1/schema.json"), SCHEMA);
        return new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model, schema), "1.26.60");
    }

    private static List<AddonGenerator.Variant> variants(AddonGenerator generator, String component, String path) {
        return generator.variants().stream().filter(v -> v.componentId().equals(component) && v.path().equals(path)
            && !v.role().equals("omit") && !v.label().contains(" linked ")).toList();
    }

    @Test
    void everyValueOfASchemaEnumIsVariedAndNoneIsCut(@TempDir Path root) throws IOException {
        AddonGenerator generator = generator(root, true);
        Set<String> sent = new TreeSet<>();
        variants(generator, "block:minecraft:geo", "mode").forEach(v -> sent.add(v.value().getAsString()));
        for (int i = 0; i < 40; i++) {
            assertTrue(sent.contains("v" + i), "v" + i + " is varied");
        }
        // The one the reason of unpinnedEnums names is cut at MAX_VALUES like any other generated list, and reported
        Set<String> unpinned = new TreeSet<>();
        variants(generator, "block:minecraft:geo", "mode2").forEach(v -> unpinned.add(v.value().getAsString()));
        assertTrue(unpinned.size() < 40, unpinned.toString());
        assertEquals(1, generator.cutValues().size(), generator.cutValues().toString());
        assertTrue(generator.cutValues().getFirst().startsWith("block:minecraft:geo mode2 (string): kept 32 of "));
    }

    @Test
    void withoutTheSchemaNothingIsPinned(@TempDir Path root) throws IOException {
        // No enum in the model without the schema: only the invalid value and the samples
        Set<String> sent = new TreeSet<>();
        variants(generator(root, false), "block:minecraft:geo", "mode").forEach(v -> sent.add(v.value().getAsString()));
        assertFalse(sent.contains("v39"), sent.toString());
    }

    /** The strings of a map's variants by key. */
    private static Map<String, Set<String>> molangByKey(List<AddonGenerator.Variant> variants, String labelPart) {
        Map<String, Set<String>> byKey = new TreeMap<>();
        for (AddonGenerator.Variant v : variants) {
            if (v.label().contains(labelPart)) {
                v.value().getAsJsonObject().entrySet().forEach(e -> byKey.computeIfAbsent(e.getKey(), k -> new TreeSet<>()).add(e.getValue().getAsString()));
            }
        }
        return byKey;
    }

    @Test
    void molangIsTriedUnderSeveralKeys(@TempDir Path root) throws IOException {
        List<AddonGenerator.Variant> bones = variants(generator(root, true), "block:minecraft:geo", "bones");
        Map<String, Set<String>> byKey = molangByKey(bones, "{3 keys}");
        assertEquals(Set.of("bone_a", "bone_a_2", "bone_a_3"), byKey.keySet());
        // Each of the 7 strings is under two keys at least
        for (String text : List.of("0.5", "1e3", "-1", "0.25", "2", "3", "true")) {
            long keys = byKey.values().stream().filter(set -> set.contains(text)).count();
            assertTrue(keys >= 2, text + " is under " + keys + " key(s)");
        }
        // Every variant is one JSON object of three entries
        bones.stream().filter(v -> v.label().contains("{3 keys}")).forEach(v -> assertEquals(3, v.value().getAsJsonObject().size()));
    }

    @Test
    void molangIsTriedInTheObjectFormAndAsANumberWhereTheSchemaListsThem(@TempDir Path root) throws IOException {
        List<AddonGenerator.Variant> bones = variants(generator(root, true), "block:minecraft:geo", "bones");
        List<JsonObject> objects = bones.stream().filter(v -> v.label().contains("{1 object}")).map(v -> v.value().getAsJsonObject()).toList();
        assertEquals(8, objects.size(), "the sample and each of the 7 strings");
        JsonObject first = objects.getFirst().getAsJsonObject("bone_a");
        assertEquals("q.x", first.get("expression").getAsString(), "the sample of fixtures.json, as the schema's expression member");
        assertEquals(1, first.get("version").getAsInt(), "every required member is there");
        assertEquals(2, first.size());
        assertTrue(objects.stream().anyMatch(o -> o.getAsJsonObject("bone_a").get("expression").getAsString().equals("1e3")));
        assertTrue(bones.stream().anyMatch(v -> v.label().contains("{1 number}") && v.value().getAsJsonObject().get("bone_a").getAsJsonPrimitive().isNumber()));
        // Without the schema the model knows no other element type: none of them
        assertTrue(variants(generator(root, false), "block:minecraft:geo", "bones").stream()
            .noneMatch(v -> v.label().contains("{1 object}") || v.label().contains("{1 number}")));
    }

    @Test
    void namesAreWrittenInEveryOrderOfThreeAndInTwoMoreSetsAndAll(@TempDir Path root) throws IOException {
        List<List<String>> lists = new ArrayList<>();
        for (AddonGenerator.Variant v : variants(generator(root, true), "block:minecraft:geo", "lock")) {
            if (v.value().isJsonArray() && !v.value().getAsJsonArray().isEmpty() && v.value().getAsJsonArray().get(0).getAsString().matches("n\\d")
                && v.value().getAsJsonArray().size() >= 3) {
                List<String> names = new ArrayList<>();
                v.value().getAsJsonArray().forEach(e -> names.add(e.getAsString()));
                lists.add(names);
            }
        }
        assertEquals(12, lists.size(), lists.toString());
        Set<List<String>> threeOrders = new HashSet<>();
        lists.stream().filter(l -> l.size() == 3 && new HashSet<>(l).equals(Set.of("n1", "n2", "n3"))).forEach(threeOrders::add);
        assertEquals(6, threeOrders.size(), "all six orders of the first three");
        assertTrue(lists.contains(List.of("n5", "n4", "n3", "n2", "n1")) && lists.contains(List.of("n1", "n2", "n3", "n4", "n5")));
        assertTrue(lists.contains(List.of("n4", "n3", "n2")) && lists.contains(List.of("n3", "n4", "n5")));
    }

    @Test
    void theBooleansOfAnObjectAreTriedInEveryCombination(@TempDir Path root) throws IOException {
        List<AddonGenerator.Variant> faces = variants(generator(root, true), "block:minecraft:geo", "faces").stream()
            .filter(v -> v.label().startsWith("object:{a=")).toList();
        // a and b (a base can hold them): 4; with c (set only by a variant of its own, with what it needs): 8
        assertEquals(12, faces.size(), faces.stream().map(AddonGenerator.Variant::label).toList().toString());
        Set<String> two = new TreeSet<>();
        Set<String> three = new TreeSet<>();
        for (AddonGenerator.Variant v : faces) {
            JsonObject o = v.value().getAsJsonObject();
            if (o.has("c")) {
                assertEquals(1, o.get("extra").getAsInt(), "c brings the members it needs");
                three.add(o.get("a") + "," + o.get("b") + "," + o.get("c"));
            } else {
                two.add(o.get("a") + "," + o.get("b"));
            }
        }
        assertEquals(Set.of("true,true", "true,false", "false,true", "false,false"), two);
        assertEquals(8, three.size());
    }

    private static List<JsonObject> boxes(List<AddonGenerator.Variant> variants) {
        List<JsonObject> boxes = new ArrayList<>();
        for (AddonGenerator.Variant v : variants) {
            if (v.label().startsWith("object:") && v.label().contains(" + ") && !v.label().contains("past any limit")) {
                boxes.add(v.value().getAsJsonObject());
            }
        }
        return boxes;
    }

    private static double[] vec(JsonObject box, String member) {
        JsonArray array = box.getAsJsonArray(member);
        return IntStream.range(0, 3).mapToDouble(i -> array.get(i).getAsDouble()).toArray();
    }

    private static void assertJointBoxes(List<JsonObject> boxes, double[] low, double[] high) {
        assertEquals(9, boxes.size());
        for (int axis = 0; axis < 3; axis++) {
            Set<List<Double>> pairs = new HashSet<>();
            Set<Double> origins = new TreeSet<>();
            Set<Double> sizes = new TreeSet<>();
            for (JsonObject box : boxes) {
                double origin = vec(box, "origin")[axis];
                double size = vec(box, "size")[axis];
                assertTrue(size >= 0, "no negative size");
                assertTrue(origin >= low[axis] && origin + size <= high[axis], "inside the limit BDS gave");
                pairs.add(List.of(origin, size));
                origins.add(origin);
                sizes.add(size);
            }
            assertEquals(9, pairs.size(), "axis " + axis + ": every origin with every size");
            assertEquals(3, origins.size());
            assertEquals(3, sizes.size());
        }
    }

    @Test
    void boxesVaryTheOriginAndTheSizeTogetherInsideBdsLimit(@TempDir Path root) throws IOException {
        AddonGenerator generator = generator(root, true);
        // The component with the limit as a fact: the whole value
        List<AddonGenerator.Variant> cube = variants(generator, "block:minecraft:cube", "").stream().filter(v -> v.role().equals("form")).toList();
        assertJointBoxes(boxes(cube), new double[]{-8, 0, -8}, new double[]{8, 16, 8});
        // The boxes of a list, which has no fact of its own: the tightest of the model's, as the elements of the list
        List<AddonGenerator.Variant> list = variants(generator, "block:minecraft:boxes", "[]");
        assertJointBoxes(boxes(list), new double[]{-8, 0, -8}, new double[]{8, 16, 8});
        list.stream().filter(v -> v.label().contains(" + ")).forEach(v -> assertTrue(v.component().isJsonArray() && v.component().getAsJsonArray().size() == 1));
    }

    @Test
    void aBoxWithoutALimitYetHasABoxPastAnyLimitAndOneWithAFactDoesNot(@TempDir Path root) throws IOException {
        AddonGenerator generator = generator(root, true);
        assertTrue(variants(generator, "block:minecraft:boxes", "[]").stream().anyMatch(v -> v.label().contains("(past any limit)")),
            "BDS says its limit when a box is past it");
        assertTrue(variants(generator, "block:minecraft:cube", "").stream().noneMatch(v -> v.label().contains("(past any limit)")));
        // The limit BDS reports on the component holds for the elements of its list
        List<AddonGenerator.Variant> listed = variants(generator, "block:minecraft:listed", "[]");
        assertTrue(listed.stream().noneMatch(v -> v.label().contains("(past any limit)")));
        assertJointBoxes(boxes(listed), new double[]{-10, 0, -10}, new double[]{10, 20, 10});
        assertTrue(boxes(listed).stream().anyMatch(b -> vec(b, "origin")[0] == -10));
    }

    @Test
    void aVectorTheSchemaAlsoDescribesByItsMembersStaysAVector(@TempDir Path root) throws IOException {
        AddonGenerator.Variant base = generator(root, true).variants().stream()
            .filter(v -> v.componentId().equals("block:minecraft:cube") && v.role().equals("base")).findFirst().orElseThrow();
        assertTrue(base.component().getAsJsonObject().get("origin").isJsonArray(), base.component().toString());
    }

    @Test
    void theCommittedAddonHasTheSamplesTheRulesNeed() throws IOException {
        Path model = Path.of("..", "data", "model").toAbsolutePath().normalize();
        AddonGenerator generator = new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model, true), "1.26.60");
        // every schema enum value of the use animations, as the whole value and as the field
        Set<String> animations = Set.of("none", "eat", "drink", "block", "bow", "camera", "crossbow", "spear", "spyglass", "brush");
        for (String path : List.of("", "value")) {
            Set<String> sent = new TreeSet<>();
            variants(generator, "item:minecraft:use_animation", path).forEach(v -> {
                if (v.value() != null && v.value().isJsonPrimitive()) {
                    sent.add(v.value().getAsString());
                }
            });
            assertTrue(sent.containsAll(animations), "use_animation " + path + ": " + sent);
        }
        // Molang under several keys and in the object form, and the hash ordered names, for all three components that have them
        for (String component : List.of("block:minecraft:geometry", "block:minecraft:item_visual", "block:minecraft:embedded_visual")) {
            String prefix = component.endsWith("geometry") ? "" : "geometry.";
            List<AddonGenerator.Variant> bones = variants(generator, component, prefix + "bone_visibility");
            assertTrue(bones.stream().anyMatch(v -> v.label().contains("{3 keys}")), component + ": bones under several keys");
            assertTrue(bones.stream().anyMatch(v -> v.label().contains("{1 object}")), component + ": the {expression, version} form");
            assertTrue(variants(generator, component, prefix + "uv_lock").stream().filter(v -> v.value().isJsonArray() && v.value().getAsJsonArray().size() >= 3
                && v.value().getAsJsonArray().get(0).getAsString().startsWith("bone_")).count() >= 12, component + ": names in several orders");
        }
        // Each packed bit alone and in pairs
        for (String face : List.of("*", "down", "up", "north", "south", "east", "west")) {
            long combinations = variants(generator, "block:minecraft:material_instances", face).stream().filter(v -> v.label().startsWith("object:{face_dimming=")).count();
            assertEquals(12, combinations, face);
        }
        // Boxes with the origin and the size varied together: the collision boxes and the selection box
        assertEquals(9, boxes(variants(generator, "block:minecraft:collision_box", "[]")).size());
        assertEquals(9, boxes(variants(generator, "block:minecraft:selection_box", "")).size());
        // The enums the add-on keeps whole are not in the report of cut values (the unpinned ones, groups of the docs, may be)
        assertTrue(generator.cutValues().stream().noneMatch(c -> c.contains("use_animation") || c.contains("tint_method") || c.contains("render_method")
            || c.contains("particle_type") || c.contains("bone_visibility")), generator.cutValues().toString());
    }

    @Test
    void everyOpenEnumOfTheApiHasNoPinnedValues() throws IOException {
        Path model = Path.of("..", "data", "model").toAbsolutePath().normalize();
        Fixtures fixtures = Fixtures.read(model, true);
        JsonElement api = JsonParser.parseString(Files.readString(model.resolve("api.json")));
        // An open enum is a string in the API: no table, so no reason to vary all of its values
        for (JsonElement key : api.getAsJsonObject().getAsJsonArray("openEnums")) {
            String component = key.getAsString().substring(0, key.getAsString().lastIndexOf(':'));
            String path = key.getAsString().substring(key.getAsString().lastIndexOf(':') + 1);
            if (!fixtures.schemaEnum(component, path).isEmpty()) {
                assertFalse(fixtures.pinsEnum(component, path), key.getAsString() + " is an open enum with a schema enum: list it in unpinnedEnums");
            }
        }
    }
}
