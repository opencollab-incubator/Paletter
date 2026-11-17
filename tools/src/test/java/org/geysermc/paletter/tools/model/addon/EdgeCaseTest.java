package org.geysermc.paletter.tools.model.addon;

import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The edge cases each type's generator asks BDS about, from the type and the limits of the docs and of BDS's feedback. */
class EdgeCaseTest {
    static final String MODEL = """
        {"source": "test", "commit": "test", "license": "test", "formatNotes": [], "components": [
          {"id": "block:minecraft:box", "kind": "block", "category": "component", "name": "minecraft:box", "status": "active",
           "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "object"}],
           "fields": [
             {"path": "count", "types": [{"type": "integer"}], "required": false, "defaultValue": 1, "range": {"min": 1, "max": 64},
              "enumValues": [], "examples": [], "doc": ""},
             {"path": "free", "types": [{"type": "number"}], "required": false, "defaultValue": 0, "enumValues": [], "examples": [], "doc": ""},
             {"path": "angle", "types": [{"type": "integer"}], "required": false, "defaultValue": 0, "enumValues": [], "examples": [], "doc": ""},
             {"path": "mode", "types": [{"type": "string"}], "required": false, "defaultValue": "a", "enumValues": ["a", "b"], "examples": [], "doc": ""},
             {"path": "block", "types": [{"type": "string"}], "required": true, "enumValues": [], "examples": [], "doc": ""},
             {"path": "when", "types": [{"type": "string"}], "required": false, "defaultValue": "", "enumValues": [], "examples": [],
              "doc": "A Molang expression."},
             {"path": "origin", "types": [{"type": "vec3"}], "required": true, "enumValues": [], "examples": [], "doc": ""},
             {"path": "size", "types": [{"type": "vec3"}], "required": true, "enumValues": [], "examples": [], "doc": ""},
             {"path": "faces", "types": [{"type": "array", "items": {"type": "string"}}], "required": false, "defaultValue": [],
              "enumValues": ["up", "down", "north"], "examples": [], "doc": ""},
             {"path": "rules", "types": [{"type": "array", "items": {"type": "string"}}], "required": false, "defaultValue": [],
              "enumValues": [], "examples": [], "doc": ""},
             {"path": "bones", "types": [{"type": "map", "items": {"type": "boolean"}}], "required": false, "defaultValue": {},
              "enumValues": [], "examples": [], "doc": ""},
             {"path": "shown", "types": [{"type": "map", "items": {"type": "string"}}, {"type": "map", "items": {"type": "boolean"}}],
              "required": false, "defaultValue": {}, "enumValues": [], "examples": [], "doc": ""}
           ]}
        ]}""";
    static final String FIXTURES = """
        {"formatVersion": "1.26.60", "filler": {}, "errata": [], "mapKeys": {"default": "k"}, "mapValues": {"shown": ["0.5", "true"]}, "context": {}, "skip": {}, "requires": {},
         "samples": {"block:minecraft:box:block": "minecraft:stone", "block:minecraft:box:origin": [-8, 0, -8], "block:minecraft:box:size": [16, 16, 16]}}""";
    static final String FEEDBACK = """
        {"block:minecraft:box": {"": ["box origin size -8,0,-8 8,16,8"], "rules": ["maxItems 3"], "angle": ["step 90"]}}""";

    private static List<String> labels(Path root, String path) throws IOException {
        return labels(root, path, FEEDBACK);
    }

    private static List<String> labels(Path root, String path, String feedback) throws IOException {
        Path model = Files.createDirectories(root.resolve("model"));
        Files.writeString(model.resolve("components.json"), MODEL);
        Files.writeString(model.resolve("fixtures.json"), FIXTURES);
        Files.writeString(model.resolve("bds-feedback.json"), feedback);
        AddonGenerator generator = new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model), "1.26.60");
        return generator.variants().stream().filter(v -> v.role().equals("field") && v.path().equals(path))
            .map(v -> v.label().substring(v.label().indexOf(':') + 1)).toList();
    }

    @Test
    void numbersGetTheirLimitsOnePastThemAndANonInteger(@TempDir Path root) throws IOException {
        List<String> count = labels(root, "count");
        assertEquals(List.of("1", "0", "64", "65"), count.subList(0, 4), "the docs' range first, and one past each end");
        assertTrue(count.contains("1.5"), "a non-integer for an integer field");
        List<String> free = labels(root, "free");
        assertTrue(free.containsAll(List.of("0", "-1")) && !free.contains("65"), "no known limit: only the safe values");
        assertTrue(labels(root, "angle").containsAll(List.of("45", "135")), "off the step BDS's rule has");
    }

    @Test
    void aLimitBdsLogsWinsOverTheDocsRange(@TempDir Path root) throws IOException {
        List<String> count = labels(root, "count", """
            {"block:minecraft:box": {"": ["box origin size -8,0,-8 8,16,8"], "count": ["max 16", "min 2"]}}""");
        assertEquals(List.of("2", "1", "16", "17"), count.subList(0, 4), "BDS's limits, not the docs' 1 to 64");
        assertTrue(!count.contains("64") && !count.contains("65"));
    }

    @Test
    void anIntegerFieldWithAnOddStepIsSentOnlyWholeNumbersExceptItsOneNonInteger(@TempDir Path root) throws IOException {
        List<String> angle = labels(root, "angle", """
            {"block:minecraft:box": {"": ["box origin size -8,0,-8 8,16,8"], "angle": ["step 3"]}}""");
        assertTrue(angle.stream().filter(label -> !label.equals("1.5")).allMatch(label -> label.matches("-?\\d+")), "every label is a whole number: " + angle);
        assertEquals(1, angle.stream().filter(label -> label.equals("1.5")).count(), "the non-integer variant survives once");
        assertTrue(angle.contains("1"), "the honest 1 is not evicted: " + angle);
    }

    @Test
    void mapsGetEachFurtherValueOfTheirElementsInAMapOfItsOwn(@TempDir Path root) throws IOException {
        Path model = Files.createDirectories(root.resolve("model"));
        Files.writeString(model.resolve("components.json"), MODEL);
        Files.writeString(model.resolve("fixtures.json"), FIXTURES);
        AddonGenerator generator = new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model), "1.26.60");
        List<AddonGenerator.Variant> shown = generator.variants().stream().filter(v -> v.role().equals("field") && v.path().equals("shown")).toList();
        // fixtures.json mapValues: the strings for the map of strings, none of them for the map of booleans
        assertEquals(List.of("map<string>:{}", "map<string>:{1}", "map<string>:{2}", "map<string>:{unknown key}", "map<string>:{1} 0.5",
            "map<string>:{1} true", "map<boolean>:{}", "map<boolean>:{1}", "map<boolean>:{2}", "map<boolean>:{unknown key}"), shown.stream().map(AddonGenerator.Variant::label).toList());
        assertEquals("{\"k\":\"0.5\"}", shown.get(4).value().toString());
    }

    @Test
    void stringsGetEveryEnumValueAndAnInvalidOneOrTheIdentifierEdges(@TempDir Path root) throws IOException {
        assertEquals(List.of("paletter_invalid", "a", "b"), labels(root, "mode"));
        List<String> block = labels(root, "block");
        assertTrue(block.containsAll(List.of("minecraft:stone", "", "stone", "minecraft:paletter_unknown")), block.toString());
        assertTrue(labels(root, "when").containsAll(List.of("1", "0")), "a Molang constant");
    }

    @Test
    void vectorsGetTheBoxLimitLessTheirPartnerAndAWrongLength(@TempDir Path root) throws IOException {
        // origin + size inside (-8,0,-8)..(8,16,8), size [16,16,16] in the base: origin from (-24,-16,-24) to (-8,0,-8)
        List<String> origin = labels(root, "origin");
        assertEquals(List.of("-24,-16,-24", "-25,-16,-24", "-24,-17,-24", "-24,-16,-25", "-8,0,-8", "-7,0,-8", "-8,1,-8", "-8,0,-7"),
            origin.subList(0, 8));
        assertTrue(origin.containsAll(List.of("1,2", "1,2,3,4")));
    }

    @Test
    void listsGetCombinationsDuplicatesAndTheirLengthLimit(@TempDir Path root) throws IOException {
        List<String> faces = labels(root, "faces");
        assertTrue(faces.containsAll(List.of("[]", "[up]", "[down]", "[north]", "[up,down]", "[up,north]", "[down,north]", "[all]", "[2x]",
            "[wrong type]")), faces.toString());
        List<String> rules = labels(root, "rules");
        assertTrue(rules.containsAll(List.of("[3x]", "[4x]")), "BDS's maxItems and one more: " + rules);
    }

    @Test
    void mapsGetAnUnknownKey(@TempDir Path root) throws IOException {
        assertEquals(List.of("{}", "{1}", "{2}", "{unknown key}"), labels(root, "bones"));
        assertFalse(labels(root, "bones").contains("[]"));
    }
}
