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

/**
 * The values {@code fixtures.json} asks the add-on to try beyond what the docs and BDS's messages list: strings of a field
 * ({@code probeValues}) and block descriptors ({@code descriptorValues}) for every field that is one.
 */
class ProbeValuesTest {
    private static final String MODEL = """
        {"source": "test", "commit": "test", "license": "test", "formatNotes": [], "components": [
          {"id": "item:minecraft:probe", "kind": "item", "category": "component", "name": "minecraft:probe", "status": "active",
           "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "object"}],
           "fields": [
             {"path": "color", "types": [{"type": "string"}], "required": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "mode", "types": [{"type": "string"}], "required": false, "enumValues": ["a", "b"], "examples": [], "doc": ""},
             {"path": "use_on", "types": [{"type": "array", "items": {"type": "object"}}], "required": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "use_on[].name", "types": [{"type": "string"}], "required": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "use_on[].states", "types": [{"type": "map", "items": {"type": "integer"}}], "required": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "use_on[].tags", "types": [{"type": "string"}], "required": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "picked", "types": [{"type": "object"}], "required": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "picked.name", "types": [{"type": "string"}], "required": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "picked.states", "types": [{"type": "map", "items": {"type": "integer"}}], "required": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "picked.tags", "types": [{"type": "string"}], "required": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "plain", "types": [{"type": "object"}], "required": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "plain.name", "types": [{"type": "string"}], "required": false, "enumValues": [], "examples": [], "doc": ""}
           ]}
        ]}""";
    private static final String FIXTURES = """
        {"formatVersion": "1.26.60", "filler": {}, "errata": [], "mapKeys": {"default": "k"}, "context": {}, "skip": {}, "requires": {},
         "samples": {"item:minecraft:probe:use_on[]": {"name": "minecraft:stone"}, "item:minecraft:probe:picked": {"name": "minecraft:stone"}},
         "probeValues": {"_comment": "x", "item:minecraft:probe:color": ["gold", "red"], "item:minecraft:probe:mode": ["c"]},
         "descriptorValues": {"_comment": "x", "values": [{"name": "minecraft:oak_log", "states": {"pillar_axis": "y"}},
                                                          {"name": "minecraft:lever", "states": {"open_bit": true}}]}}""";

    private static List<String> labels(Path root, String path) throws IOException {
        Path model = Files.createDirectories(root.resolve("model"));
        Files.writeString(model.resolve("components.json"), MODEL);
        Files.writeString(model.resolve("fixtures.json"), FIXTURES);
        AddonGenerator generator = new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model), "1.26.60");
        return generator.variants().stream().filter(v -> v.role().equals("field") && v.path().equals(path))
            .map(v -> v.label().substring(v.label().indexOf(':') + 1)).toList();
    }

    @Test
    void probeValuesAreStringsOfTheirOwnBesideTheOnesTheDocsAndBdsList(@TempDir Path root) throws IOException {
        List<String> color = labels(root, "color");
        assertTrue(color.containsAll(List.of("gold", "red", "paletter_a", "paletter_b", "")), color.toString());
        // beside an enum too: the enum's values and the invalid one stay
        assertEquals(List.of("paletter_invalid", "a", "b", "c"), labels(root, "mode"));
        assertFalse(labels(root, "plain").contains("gold"), "only the field the key names");
    }

    @Test
    void descriptorValuesAreTriedInEveryFieldThatIsADescriptor(@TempDir Path root) throws IOException {
        // a list of descriptors: each as the only element; a descriptor object: each as the value
        List<String> useOn = labels(root, "use_on");
        assertTrue(useOn.containsAll(List.of("[1] oak_log{\"pillar_axis\":\"y\"}", "[1] lever{\"open_bit\":true}")), useOn.toString());
        List<String> picked = labels(root, "picked");
        assertTrue(picked.containsAll(List.of("oak_log{\"pillar_axis\":\"y\"}", "lever{\"open_bit\":true}")), picked.toString());
        // an object without states and tags is no descriptor, a string no object
        assertTrue(labels(root, "plain").stream().noneMatch(label -> label.contains("oak_log")));
        assertTrue(labels(root, "color").stream().noneMatch(label -> label.contains("oak_log")));
    }
}
