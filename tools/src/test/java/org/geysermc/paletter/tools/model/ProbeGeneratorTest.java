package org.geysermc.paletter.tools.model;

import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.model.Model.Component;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What BDS's answers change in the generator: the control of companions that need each other, the types and keys that
 * come from {@code bds-feedback.json} and {@code bds-keys.json}, and the variants of the format sweep.
 */
class ProbeGeneratorTest {
    /** geo and mat need each other; geo's rotation is a string in the docs; mat's tint needs a newer format. */
    static final String MODEL = """
        {"source": "test", "commit": "test", "license": "test", "formatNotes": [], "components": [
          {"id": "block:minecraft:geo", "kind": "block", "category": "component", "name": "minecraft:geo", "status": "active",
           "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "object"}],
           "fields": [
             {"path": "identifier", "types": [{"type": "string"}], "required": true, "enumValues": [], "examples": [], "doc": ""},
             {"path": "rotation", "types": [{"type": "string"}], "required": false, "enumValues": ["minecraft:a"], "examples": [], "doc": ""},
             {"path": "items", "types": [{"type": "array", "items": {"type": "object"}}, {"type": "object"}], "required": false, "enumValues": [], "examples": [], "doc": ""}
           ]},
          {"id": "block:minecraft:mat", "kind": "block", "category": "component", "name": "minecraft:mat", "status": "active",
           "doc": "", "page": "", "minFormatVersion": "1.19.40", "experiments": [], "notes": [], "forms": [{"type": "object"}],
           "fields": [
             {"path": "texture", "types": [{"type": "string"}], "required": true, "enumValues": [], "examples": [], "doc": ""},
             {"path": "tint", "types": [{"type": "boolean"}], "required": false, "minFormatVersion": "1.21.120", "enumValues": [], "examples": [], "doc": ""}
           ]}
        ]}""";
    static final String FIXTURES = """
        {"formatVersion": "1.26.60", "filler": {}, "errata": [], "samples": {}, "mapKeys": {"default": "k"}, "context": {}, "skip": {},
         "requires": {"block:minecraft:geo": {"minecraft:mat": {"texture": "t"}}, "block:minecraft:mat": {"minecraft:geo": "full"}}}""";

    private static Path model(Path root, String feedback, String keys) throws IOException {
        Path model = Files.createDirectories(root.resolve("model"));
        Files.writeString(model.resolve("components.json"), MODEL);
        Files.writeString(model.resolve("fixtures.json"), FIXTURES);
        if (feedback != null) {
            Files.writeString(model.resolve("bds-feedback.json"), feedback);
        }
        if (keys != null) {
            Files.writeString(model.resolve(Fixtures.KEYS_FILE), keys);
        }
        return model;
    }

    private static AddonGenerator generator(Path model) throws IOException {
        return new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model), "1.26.60");
    }

    private static AddonGenerator.Variant control(AddonGenerator generator, String component) {
        return generator.variants().stream().filter(v -> v.componentId().equals(component) && v.role().equals("control")).findFirst().orElseThrow();
    }

    @Test
    void companionsThatNeedEachOtherGetTheMinimalPairAsControl(@TempDir Path root) throws IOException {
        AddonGenerator generator = generator(model(root, null, null));

        JsonObject geo = control(generator, "block:minecraft:geo").definition().getAsJsonObject("minecraft:block").getAsJsonObject("components");
        assertEquals("full", geo.get("minecraft:geo").getAsString(), "geo needs mat, and mat needs geo: the pair is the control");
        assertEquals("t", geo.getAsJsonObject("minecraft:mat").get("texture").getAsString());
        JsonObject mat = control(generator, "block:minecraft:mat").definition().getAsJsonObject("minecraft:block").getAsJsonObject("components");
        assertTrue(mat.has("minecraft:geo") && mat.has("minecraft:mat"));
    }

    @Test
    void aControlWithoutMutualCompanionsHasNoComponent(@TempDir Path root) throws IOException {
        Path model = model(root, null, null);
        Files.writeString(model.resolve("fixtures.json"), FIXTURES.replace(", \"block:minecraft:mat\": {\"minecraft:geo\": \"full\"}", ""));
        AddonGenerator generator = generator(model);
        assertNull(control(generator, "block:minecraft:geo").component());
        JsonObject components = control(generator, "block:minecraft:geo").definition().getAsJsonObject("minecraft:block").getAsJsonObject("components");
        assertFalse(components.has("minecraft:geo"));
    }

    @Test
    void typeErrorsOfTheContentLogFixTheDocsTypes(@TempDir Path root) throws IOException {
        String feedback = """
            {"block:minecraft:geo": {"rotation": ["object"], "items[]": ["object"], "identifier": ["object"]}}""";
        Component geo = generator(model(root, feedback, null)).model().component("block:minecraft:geo");

        assertEquals("object", geo.field("rotation").types().getFirst().type(), "'expected an object' where the docs say string");
        assertEquals("string", geo.field("rotation").types().get(1).type(), "the docs type stays, so the next capture asks BDS again");
        assertEquals("object", geo.field("items").types().getFirst().type(), "the list of the docs is the form BDS does not take");
        assertEquals("array", geo.field("items").types().get(1).type());
        assertEquals(2, geo.field("identifier").types().size(), "an object type is added where there was none");
    }

    @Test
    void keysTheProbeFoundBecomeFields(@TempDir Path root) throws IOException {
        String keys = """
            {"bdsVersion": "9.9.9.9", "components": {"block:minecraft:mat": {"accepted": {
              "emissive": {"types": ["boolean"], "examples": [true], "minFormatVersion": "1.21.120"},
              "texture": {"types": ["integer"]}}}}}""";
        Component mat = generator(model(root, null, keys)).model().component("block:minecraft:mat");

        Model.Field emissive = mat.field("emissive");
        assertNotNull(emissive);
        assertEquals("boolean", emissive.types().getFirst().type());
        assertEquals("1.21.120", emissive.minFormatVersion());
        assertTrue(emissive.examples().getFirst().getAsBoolean());
        assertTrue(emissive.doc().startsWith("(bds-schema 9.9.9.9)"), "generated fields say where they come from");
        assertEquals("string", mat.field("texture").types().getFirst().type(), "a documented field keeps its docs types");
    }

    @Test
    void probedKeysOnlyReachTheBuildTheProbeRanOn(@TempDir Path root) throws IOException {
        Path model = model(root, null, """
            {"bdsVersion": "9.9.9.9", "components": {"block:minecraft:mat": {"accepted": {"emissive": {"types": ["boolean"]}}}}}""");
        Model docs = Model.read(model.resolve("components.json"));

        Component probed = new AddonGenerator(docs, Fixtures.read(model).forBuild("9.9.9.9"), "1.26.60").model().component("block:minecraft:mat");
        Component older = new AddonGenerator(docs, Fixtures.read(model).forBuild("9.9.8.1"), "1.26.60").model().component("block:minecraft:mat");

        assertNotNull(probed.field("emissive"), "the build the probe ran on accepted the key");
        assertNull(older.field("emissive"), "another build was not asked: its add-on does not use the key");
    }

    @Test
    void theFormatSweepDropsWhatNeedsANewerFormat(@TempDir Path root) throws IOException {
        AddonGenerator generator = AddonGenerator.empty(Model.read(model(root, null, null).resolve("components.json")),
            Fixtures.read(root.resolve("model")), "1.26.60");

        AddonGenerator.Variant old = generator.atFormat("block:minecraft:mat", "1.21.100", "", null);
        AddonGenerator.Variant current = generator.atFormat("block:minecraft:mat", "1.21.120", "", null);
        AddonGenerator.Variant field = generator.atFormat("block:minecraft:mat", "1.21.100", "tint", null);

        assertFalse(old.component().getAsJsonObject().has("tint"));
        assertTrue(current.component().getAsJsonObject().has("tint"), "the format has the field");
        assertEquals("1.21.100", field.definition().get("format_version").getAsString());
        assertTrue(field.component().getAsJsonObject().get("tint").getAsBoolean(), "the field under test stays, whatever its format");
        assertNull(generator.atFormat("block:minecraft:mat", "1.19.0", "", null), "the component does not exist at that format yet");
        JsonObject manifest = generator.manifest().get(0).getAsJsonObject();
        assertEquals("1.21.100", manifest.get("format").getAsString());
        assertEquals("format", manifest.get("role").getAsString());
        assertEquals("1.26.60", generator.definitionOf("block:minecraft:mat", "paletter:x", null).get("format_version").getAsString());
    }
}
