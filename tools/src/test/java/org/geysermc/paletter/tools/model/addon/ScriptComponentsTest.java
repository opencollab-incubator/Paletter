package org.geysermc.paletter.tools.model.addon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

/** The script custom components of the spec add-on: the variants of {@code scriptComponents}, their definitions, the script and the manifest. */
class ScriptComponentsTest {
    private static final String MODEL = """
        {"source": "test", "commit": "test", "license": "test", "formatNotes": [], "components": [
          {"id": "block:minecraft:custom_components", "kind": "block", "category": "component", "name": "minecraft:custom_components",
           "status": "active", "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "object"}],
           "fields": [{"path": "ignored", "types": [{"type": "string"}], "required": false, "enumValues": [], "examples": [], "doc": ""}]},
          {"id": "item:minecraft:custom_components", "kind": "item", "category": "component", "name": "minecraft:custom_components",
           "status": "active", "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "object"}], "fields": []},
          {"id": "item:minecraft:block_placer", "kind": "item", "category": "component", "name": "minecraft:block_placer",
           "status": "active", "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "object"}],
           "fields": [{"path": "block", "types": [{"type": "string"}], "required": true, "enumValues": [], "examples": [], "doc": ""}]}
        ]}""";
    private static final String FIXTURES = """
        {"formatVersion": "1.26.60", "filler": {"minecraft:max_stack_size": 64}, "errata": [], "mapKeys": {"default": "k"}, "mapValues": {},
         "context": {}, "skip": {}, "requires": {}, "samples": {"item:minecraft:block_placer:block": "minecraft:dirt"},
         "scriptComponents": {
           "module": "2.0.0",
           "block": {"paletter:none": [], "paletter:interact": ["onPlayerInteract"]},
           "item": {"paletter:use_on": ["onUseOn", "onUse"]},
           "variants": {
             "block:minecraft:custom_components": [
               {"format": "1.21.80", "json": ["paletter:interact"]},
               {"json": {"paletter:interact": {"a": 1}}},
               {"json": {"permutations": [{"condition": "true", "components": {"paletter:interact": {}}}]}}
             ],
             "item:minecraft:custom_components": [{"json": {"paletter:use_on": {}}}],
             "item:minecraft:block_placer": [{"json": {"minecraft:block_placer": {"block": "minecraft:dirt"}, "paletter:use_on": {}}}]
           }}}""";

    private static AddonGenerator generator(Path root) throws IOException {
        Path model = Files.createDirectories(root.resolve("model"));
        Files.writeString(model.resolve("components.json"), MODEL);
        Files.writeString(model.resolve("fixtures.json"), FIXTURES);
        return new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model), "1.26.60");
    }

    private static List<AddonGenerator.Variant> variants(AddonGenerator generator, String component, String role) {
        return generator.variants().stream().filter(v -> v.componentId().equals(component) && v.role().equals(role)).toList();
    }

    private static JsonObject components(AddonGenerator.Variant v) {
        return components(v.definition());
    }

    private static JsonObject components(JsonObject definition) {
        return definition.getAsJsonObject(definition.has("minecraft:block") ? "minecraft:block" : "minecraft:item").getAsJsonObject("components");
    }

    @Test
    void theComponentsGetAControlAndTheirScriptVariantsOnly(@TempDir Path root) throws IOException {
        AddonGenerator generator = generator(root);
        List<String> roles = generator.variants().stream().filter(v -> v.componentId().equals("block:minecraft:custom_components"))
            .map(AddonGenerator.Variant::role).toList();
        assertEquals(List.of("control", "script", "script", "script"), roles, "the docs model's fields and forms are not varied");
        assertEquals(1, variants(generator, "item:minecraft:custom_components", "script").size());
    }

    @Test
    void theOldFormIsAnArrayUnderTheNameAtItsOwnFormatVersion(@TempDir Path root) throws IOException {
        AddonGenerator.Variant old = variants(generator(root), "block:minecraft:custom_components", "script").get(0);
        assertEquals("1.21.80", old.format());
        assertEquals("1.21.80", old.definition().get("format_version").getAsString());
        assertEquals(JsonParser.parseString("[\"paletter:interact\"]"), components(old).get("minecraft:custom_components"));
    }

    @Test
    void theNewFormIsOneKeyPerComponentAndMayHoldPermutations(@TempDir Path root) throws IOException {
        AddonGenerator generator = generator(root);
        List<AddonGenerator.Variant> block = variants(generator, "block:minecraft:custom_components", "script");
        assertEquals(JsonParser.parseString("{\"paletter:interact\": {\"a\": 1}}"), components(block.get(1)));
        assertFalse(components(block.get(1)).has("minecraft:custom_components"));

        AddonGenerator.Variant permuted = block.get(2);
        assertFalse(components(permuted).has("permutations"));
        assertFalse(components(permuted).has("paletter:interact"));
        assertEquals(JsonParser.parseString("[{\"condition\": \"true\", \"components\": {\"paletter:interact\": {}}}]"),
            permuted.definition().getAsJsonObject("minecraft:block").get("permutations"));

        assertEquals(JsonParser.parseString("{\"paletter:use_on\": {}}"),
            components(variants(generator, "item:minecraft:custom_components", "script").get(0)));
    }

    @Test
    void thePlacersKeepTheirOwnComponentNextToTheScriptOne(@TempDir Path root) throws IOException {
        AddonGenerator generator = generator(root);
        AddonGenerator.Variant both = variants(generator, "item:minecraft:block_placer", "script").get(0);
        assertEquals(JsonParser.parseString("{\"minecraft:block_placer\": {\"block\": \"minecraft:dirt\"}, \"paletter:use_on\": {}}"), components(both));
        // the placer's own variants are not mistaken for such a map
        AddonGenerator.Variant base = variants(generator, "item:minecraft:block_placer", "base").get(0);
        assertTrue(components(base).has("minecraft:block_placer"));
        assertFalse(components(base).has("block"));
    }

    @Test
    void aCapturedVariantIsRebuiltFromItsJson(@TempDir Path root) throws IOException {
        AddonGenerator generator = generator(root);
        for (String component : List.of("block:minecraft:custom_components", "item:minecraft:block_placer")) {
            for (AddonGenerator.Variant v : variants(generator, component, "script")) {
                if (v.format() != null) {
                    continue; // definitionOf builds at the generator's format; the capture keeps the variant's own
                }
                assertEquals(v.definition(), generator.definitionOf(component, v.identifier(), v.role(), v.component()), v.identifier());
            }
        }
    }

    @Test
    void otherRolesKeepTheValueUnderTheComponentsNameWhateverItsShape(@TempDir Path root) throws IOException {
        // the format sweep's base for custom_components is {}: it is the component's value, not an empty map of components
        JsonObject definition = generator(root).definitionOf("block:minecraft:custom_components", "paletter:x", "format", new JsonObject());
        assertEquals(JsonParser.parseString("{\"minecraft:custom_components\": {}}"), components(definition));
    }

    @Test
    void theCommittedFormatSweepsRebuildWithTheValueUnderTheName() throws IOException {
        Path bds = Path.of(System.getProperty("paletter.root", "..")).resolve("data/bds");
        Path model = Path.of(System.getProperty("paletter.root", "..")).resolve("data/model");
        AddonGenerator generator = new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model));
        int rebuilt = 0;
        try (java.util.stream.Stream<Path> builds = Files.list(bds)) {
            for (Path variants : builds.map(b -> b.resolve("spec-format/variants.json")).filter(Files::exists).toList()) {
                for (var e : JsonParser.parseString(Files.readString(variants)).getAsJsonArray()) {
                    JsonObject entry = e.getAsJsonObject();
                    if (entry.get("component").getAsString().endsWith(":minecraft:custom_components")) {
                        JsonObject definition = generator.definitionOf(entry.get("component").getAsString(), entry.get("identifier").getAsString(),
                            entry.get("role").getAsString(), entry.get("json"));
                        assertEquals(entry.get("json"), components(definition).get("minecraft:custom_components"), entry.get("identifier").getAsString());
                        rebuilt++;
                    }
                }
            }
        }
        assertTrue(rebuilt > 0, "no committed format sweep variant of custom_components to rebuild");
    }

    @Test
    void theScriptRegistersTheTableAndTheManifestPinsTheModule(@TempDir Path root) throws IOException {
        Path out = root.resolve("addon");
        generator(root).write(out);
        JsonObject manifest = JsonParser.parseString(Files.readString(out.resolve("behavior_pack/manifest.json"))).getAsJsonObject();
        assertTrue(manifest.getAsJsonArray("modules").asList().stream().anyMatch(m ->
            m.getAsJsonObject().get("type").getAsString().equals("script") && m.getAsJsonObject().get("entry").getAsString().equals("scripts/main.js")));
        assertTrue(manifest.getAsJsonArray("dependencies").asList().stream().anyMatch(d -> d.getAsJsonObject().has("module_name")
            && d.getAsJsonObject().get("module_name").getAsString().equals("@minecraft/server")
            && d.getAsJsonObject().get("version").getAsString().equals("2.0.0")));

        String script = Files.readString(out.resolve("behavior_pack/scripts/main.js"));
        for (String registered : List.of("paletter:none", "paletter:interact", "onPlayerInteract", "paletter:use_on", "onUseOn", "onUse")) {
            assertTrue(script.contains("\"" + registered + "\""), registered);
        }
        assertTrue(script.contains("system.beforeEvents.startup.subscribe"));
        assertTrue(script.contains("registerCustomComponent"));
        assertTrue(script.contains(AddonGenerator.SCRIPT_MARKER));
        assertEquals("PALETTER-COMPONENTS 3", generator(root.resolve("again")).scriptMarker(), "the count the script prints: every registered component");
    }
}
