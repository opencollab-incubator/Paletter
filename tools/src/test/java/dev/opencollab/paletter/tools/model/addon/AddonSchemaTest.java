package dev.opencollab.paletter.tools.model.addon;

import dev.opencollab.paletter.tools.model.Fixtures;
import dev.opencollab.paletter.tools.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The add-on generator with BDS's schema: what it varies where the schema and the content log disagree, and what it cuts. */
class AddonSchemaTest {
    private static final String BIG = String.join(", ", IntStream.range(0, 40).mapToObj(i -> "\"v" + i + "\"").toList());

    /** {@code count} has no limit in the docs; {@code mode} lists 40 values. */
    static final String MODEL = """
        {"source": "test", "commit": "test", "license": "test", "formatNotes": [], "components": [
          {"id": "block:minecraft:box", "kind": "block", "category": "component", "name": "minecraft:box", "status": "active",
           "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "object"}],
           "fields": [
             {"path": "count", "types": [{"type": "integer"}], "required": false, "defaultValue": 1, "enumValues": [], "examples": [], "doc": ""},
             {"path": "mode", "types": [{"type": "string"}], "required": false, "defaultValue": "v0", "enumValues": [%s], "examples": [], "doc": ""},
             {"path": "kind", "types": [{"type": "string"}], "required": false, "enumValues": [], "examples": [], "doc": ""}
           ]}
        ]}""".formatted(BIG);
    static final String FIXTURES = """
        {"formatVersion": "1.26.60", "filler": {}, "errata": [], "mapKeys": {"default": "k"}, "context": {}, "skip": {}, "requires": {}, "samples": {}}""";
    /** The log says 0 to 20 and the value 'mud'; the schema 0 to 15 and water and lava. */
    static final String FEEDBACK = """
        {"block:minecraft:box": {"count": ["range 0 20"], "kind": ["values 'water', 'mud'"]}}""";
    static final String SCHEMA = """
        {"bdsVersion": "1.26.99.1", "formats": {"block": ["1.26.60"]}, "block": {"minecraft:box": [{"formats": ["1.26.60"], "root": {"types": ["object"]},
         "fields": {"count": {"types": ["integer"], "minimum": 0.0, "maximum": 15.0},
                    "kind": {"types": ["string"], "enum": ["water", "lava"]}}}]}}""";

    private static AddonGenerator generator(Path root) throws IOException {
        Path model = Files.createDirectories(root.resolve("model"));
        Files.writeString(model.resolve("components.json"), MODEL);
        Files.writeString(model.resolve("fixtures.json"), FIXTURES);
        Files.writeString(model.resolve("bds-feedback.json"), FEEDBACK);
        Files.createDirectories(root.resolve("bds/1.26.99.1"));
        Files.writeString(root.resolve("bds/1.26.99.1/schema.json"), SCHEMA);
        return new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model), "1.26.60");
    }

    private static List<String> labels(AddonGenerator generator, String path) {
        return generator.variants().stream().filter(v -> v.role().equals("field") && v.path().equals(path))
            .map(v -> v.label().substring(v.label().indexOf(':') + 1)).toList();
    }

    @Test
    void whereTheSchemaAndTheLogDisagreeBothBoundariesAreTested(@TempDir Path root) throws IOException {
        AddonGenerator generator = generator(root);
        List<String> count = labels(generator, "count");
        assertEquals(List.of("0", "-1", "15", "16"), count.subList(0, 4), "the schema's limits first");
        assertTrue(count.containsAll(List.of("20", "21")), "and the log's: BDS decides");
        List<String> kind = labels(generator, "kind");
        assertTrue(kind.containsAll(List.of("water", "lava", "mud")), kind.toString());
    }

    @Test
    void theValuesTheGeneratorCutAreReported(@TempDir Path root) throws IOException {
        List<String> cut = generator(root).cutValues();
        assertEquals(1, cut.size(), cut.toString());
        assertTrue(cut.getFirst().startsWith("block:minecraft:box mode (string): kept 32 of "), cut.getFirst());
        assertFalse(cut.getFirst().contains("v0,"), "the values that are varied are not listed as cut");
    }
}
