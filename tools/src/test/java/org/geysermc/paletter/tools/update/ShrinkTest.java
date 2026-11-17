package org.geysermc.paletter.tools.update;

import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.Model;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.geysermc.paletter.tools.model.rule.Leaf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * How {@code spec-capture} shrinks the variants a build rejected for a member they do not vary, and how the generator
 * rebuilds a capture's add-on from its {@code variants.json}.
 */
class ShrinkTest {
    /** box: a container whose title an older build does not know; turn: a trait whose offset needs a state. */
    static final String MODEL = """
        {"source": "test", "commit": "test", "license": "test", "formatNotes": [], "components": [
          {"id": "block:minecraft:box", "kind": "block", "category": "component", "name": "minecraft:box", "status": "active",
           "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "object"}],
           "fields": [
             {"path": "container", "types": [{"type": "object"}], "required": true, "enumValues": [], "examples": [], "doc": ""},
             {"path": "container.slot_count", "types": [{"type": "integer"}], "required": true, "enumValues": [], "examples": [], "doc": ""},
             {"path": "container.title", "types": [{"type": "string"}], "required": true, "enumValues": [], "examples": [], "doc": ""}
           ]},
          {"id": "block:trait:minecraft:turn", "kind": "block", "category": "trait", "name": "minecraft:turn", "status": "active",
           "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "object"}],
           "fields": [
             {"path": "states", "types": [{"type": "array", "items": {"type": "string"}}], "required": true, "enumValues": ["a", "b"], "examples": [], "doc": ""},
             {"path": "offset", "types": [{"type": "number"}], "required": true, "enumValues": [], "examples": [], "doc": ""}
           ]}
        ]}""";
    static final String FIXTURES = """
        {"formatVersion": "1.26.60", "filler": {}, "errata": [], "samples": {}, "mapKeys": {"default": "k"}, "context": {}, "skip": {},
         "requires": {}}""";

    private static AddonGenerator generator(Path root, Map<String, AddonGenerator.Shrunk> shrunk) throws IOException {
        Path model = Files.createDirectories(root.resolve("model"));
        Files.writeString(model.resolve("components.json"), MODEL);
        Files.writeString(model.resolve("fixtures.json"), FIXTURES);
        return new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model), "1.26.60", shrunk);
    }

    private static AddonGenerator.Variant find(AddonGenerator generator, String component, String role, String label) {
        return generator.variants().stream().filter(v -> v.componentId().equals(component) && v.role().equals(role)
            && AddonGenerator.Shrunk.generatedLabel(v.label()).equals(label)).findFirst().orElseThrow();
    }

    /** Every variant sent; the given ones logged errors. */
    private static Map<String, Map<String, Leaf>> sent(AddonGenerator generator) {
        Map<String, Map<String, Leaf>> leaves = new HashMap<>();
        generator.variants().forEach(v -> leaves.put(v.kind() + " " + v.identifier(), Map.of()));
        return leaves;
    }

    private static String blockError(AddonGenerator.Variant v, String where) {
        return "ERROR [Blocks] block_definitions | behavior_packs/paletter_spec | " + v.file() + " |  -> " + where;
    }

    @Test
    void aRejectedBaseLosesTheMemberTheLogNamesAndItsFieldVariantsAreBuiltOnIt(@TempDir Path root) throws IOException {
        AddonGenerator first = generator(root, Map.of());
        AddonGenerator.Variant base = find(first, "block:minecraft:box", "base", "base");
        assertEquals(JsonParser.parseString("{\"container\": {\"slot_count\": 1, \"title\": \"paletter_a\"}}"), base.component());
        Map<String, List<String>> log = new HashMap<>();
        log.put(base.identifier(), List.of(blockError(base,
            "components -> minecraft:box -> container -> title: this member was found in the input, but is not present in the Schema")));

        Map<String, AddonGenerator.Shrunk> shrunk = SpecCapture.shrink(first.variants(), sent(first), log, Map.of());

        assertEquals(1, shrunk.size());
        AddonGenerator.Shrunk s = shrunk.values().iterator().next();
        assertEquals(List.of("container.title"), s.dropped());
        AddonGenerator second = generator(root, shrunk);
        AddonGenerator.Variant shrunkBase = find(second, "block:minecraft:box", "base", "base");
        assertEquals("base without container.title", shrunkBase.label());
        assertEquals(base.identifier(), shrunkBase.identifier(), "the variant keeps its identifier");
        assertEquals(JsonParser.parseString("{\"container\": {\"slot_count\": 1}}"), shrunkBase.component());
        AddonGenerator.Variant slots = find(second, "block:minecraft:box", "field", "integer:7");
        assertEquals(JsonParser.parseString("{\"container\": {\"slot_count\": 7}}"), slots.component());
    }

    @Test
    void aRejectedFieldVariantLosesTheBaseKeyAQuotedMessageNamesButNotTheFieldItVaries(@TempDir Path root) throws IOException {
        AddonGenerator first = generator(root, Map.of());
        AddonGenerator.Variant states = find(first, "block:trait:minecraft:turn", "field", "array<string>:[b]");
        AddonGenerator.Variant offset = find(first, "block:trait:minecraft:turn", "field", "number:30");
        Map<String, List<String>> log = new HashMap<>();
        log.put(states.identifier(), List.of(blockError(states,
            "description -> traits -> minecraft:turn: \"offset\" can only be used when \"a\" is enabled in \"states\".")));
        log.put(offset.identifier(), List.of(blockError(offset,
            "description -> traits -> minecraft:turn -> offset: the input is outside of the accepted range [0, 1]")));

        Map<String, AddonGenerator.Shrunk> shrunk = SpecCapture.shrink(first.variants(), sent(first), log, Map.of());

        assertEquals(1, shrunk.size(), "a variant rejected for the value it varies stays as it is");
        AddonGenerator second = generator(root, shrunk);
        AddonGenerator.Variant without = find(second, "block:trait:minecraft:turn", "field", "array<string>:[b]");
        assertEquals("array<string>:[b] without offset", without.label());
        assertEquals(JsonParser.parseString("{\"states\": [\"b\"]}"), without.component());
    }

    @Test
    void aBaseWhoseLogNamesNoMemberBecomesTheOmitVariantTheBuildAccepted(@TempDir Path root) throws IOException {
        AddonGenerator first = generator(root, Map.of());
        Map<String, List<String>> log = new HashMap<>();
        for (AddonGenerator.Variant v : first.variants()) {
            boolean keepsTitle = v.componentId().equals("block:minecraft:box") && v.component() != null && !v.component().isJsonNull()
                && v.component().toString().contains("title");
            if (keepsTitle) {
                log.put(v.identifier(), List.of(blockError(v, "components -> minecraft:box: invalid string")));
            }
        }

        Map<String, AddonGenerator.Shrunk> shrunk = SpecCapture.shrink(first.variants(), sent(first), log, Map.of());

        AddonGenerator.Shrunk base = shrunk.get(AddonGenerator.Shrunk.key("base", "block:minecraft:box", "", "base"));
        assertEquals(List.of("container.title"), base.dropped());
        assertEquals(JsonParser.parseString("{\"container\": {\"slot_count\": 1}}"), base.json());
        assertFalse(shrunk.keySet().stream().anyMatch(k -> k.startsWith("field ")), "field variants wait for the shrunk base");
    }

    @Test
    void theGeneratorRebuildsACapturedAddonFromItsVariants(@TempDir Path root) throws IOException {
        AddonGenerator first = generator(root, Map.of());
        AddonGenerator.Variant base = find(first, "block:minecraft:box", "base", "base");
        Map<String, AddonGenerator.Shrunk> shrunk = new LinkedHashMap<>(SpecCapture.shrink(first.variants(), sent(first),
            Map.of(base.identifier(), List.of(blockError(base, "components -> minecraft:box -> container -> title: unknown"))), Map.of()));
        AddonGenerator captured = generator(root, shrunk);

        Map<String, AddonGenerator.Shrunk> read = AddonGenerator.Shrunk.of(captured.manifest());
        AddonGenerator rebuilt = generator(root, read);

        assertEquals(shrunk.keySet(), read.keySet());
        assertEquals(captured.manifest(), rebuilt.manifest());
    }

    @Test
    void contentLogLinesLoseTheirTimestampAndTheNoLogFilePrefix() {
        String plain = "[2026-11-16 21:00:00:123 ERROR] [Blocks] block_definitions | blocks/b_x_1.json |  -> components -> minecraft:x: bad";
        assertEquals("ERROR [Blocks] block_definitions | blocks/b_x_1.json |  -> components -> minecraft:x: bad", SpecCapture.contentLine(plain));
        assertEquals("WARN [Item] \"minecraft:x\": odd", SpecCapture.contentLine("NO LOG FILE! - [2026-11-16 21:00:00:123 WARN] [Item] \"minecraft:x\": odd"));
        assertThrows(IllegalStateException.class, () -> SpecCapture.contentLine("LOG LOST [2026-11-16 21:00:00:123 ERROR] [Blocks] bad"));
    }
}
