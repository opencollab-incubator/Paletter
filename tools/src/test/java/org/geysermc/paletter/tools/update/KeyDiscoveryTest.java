package org.geysermc.paletter.tools.update;

import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.Model;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.geysermc.paletter.tools.model.infer.Wire.Leaf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The key probe on a small model: what it asks (from BDS texts, the wiki and a vanilla pack) and how it reads the answers. */
class KeyDiscoveryTest {
    static final String MODEL = """
        {"source": "test", "commit": "test", "license": "test", "formatNotes": [], "components": [
          {"id": "block:minecraft:mat", "kind": "block", "category": "component", "name": "minecraft:mat", "status": "active",
           "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "object"}],
           "fields": [
             {"path": "*", "types": [{"type": "object"}], "required": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "*.texture", "types": [{"type": "string"}], "required": false, "enumValues": [], "examples": [], "doc": ""},
             {"path": "*.tint_method", "types": [{"type": "string"}], "required": false, "enumValues": [], "examples": [], "doc": ""}
           ]},
          {"id": "block:minecraft:plain", "kind": "block", "category": "component", "name": "minecraft:plain", "status": "active",
           "doc": "", "page": "", "experiments": [], "notes": [], "forms": [{"type": "object"}],
           "fields": [{"path": "size", "types": [{"type": "number"}], "required": false, "enumValues": [], "examples": [], "doc": ""}]}
        ]}""";
    static final String FIXTURES = """
        {"formatVersion": "1.26.60", "filler": {}, "errata": [], "requires": {}, "samples": {"block:minecraft:mat:*.texture": "t"},
         "mapKeys": {"default": "k"}, "context": {}, "skip": {}}""";

    private static Path model(Path root) throws IOException {
        Path model = Files.createDirectories(root.resolve("model"));
        Files.writeString(model.resolve("components.json"), MODEL);
        Files.writeString(model.resolve("fixtures.json"), FIXTURES);
        return model;
    }

    private static AddonGenerator generator(Path root) throws IOException {
        Path model = model(root);
        return AddonGenerator.empty(Model.read(model.resolve("components.json")), Fixtures.read(model), "1.26.60");
    }

    /** A wiki cache that holds the four pages, so nothing is downloaded. */
    private static Path cache(Path root, String components, String history) throws IOException {
        Path wiki = Files.createDirectories(root.resolve("cache/wiki"));
        for (String page : WikiSource.COMPONENT_PAGES) {
            Files.writeString(wiki.resolve(page.replace('/', '_')), page.startsWith("block") ? components : "");
        }
        for (String page : WikiSource.HISTORY_PAGES) {
            Files.writeString(wiki.resolve(page.replace('/', '_')), page.startsWith("block") ? history : "");
        }
        return root.resolve("cache");
    }

    @Test
    void textsOfTheBinaryNameKeysNextToTheFieldsTheyBelongTo(@TempDir Path root) throws IOException {
        Path binary = root.resolve("bedrock_server");
        String texts = "'alpha_masked_tint' requires a non-default 'tint_method'.\0"
            + "'alpha_masked_tint' is only supported at format_version 1.21.120 or higher.\0"
            + "'minecraft:plain' cannot use 'rounded' member with 'format_version' < 1.21.70\0"
            + "a text without the quoted names of a field\0noise_without_space\0";
        Files.write(binary, texts.getBytes(StandardCharsets.ISO_8859_1));
        AddonGenerator generator = generator(root);

        KeyDiscovery.Evidence evidence = KeyDiscovery.gather(generator.model(), binary, cache(root, "", ""), List.of());

        assertTrue(evidence.candidates.containsKey(new KeyDiscovery.Candidate("block:minecraft:mat", "*", "alpha_masked_tint")),
            "named next to tint_method, so a member of the object that has it");
        assertTrue(evidence.candidates.containsKey(new KeyDiscovery.Candidate("block:minecraft:plain", "", "rounded")),
            "named next to the component, so a member of its root");
        assertFalse(evidence.candidates.keySet().stream().anyMatch(c -> c.component().equals("block:minecraft:mat") && c.level().isEmpty()),
            "every key of a map is accepted, asking for one says nothing");
        assertFalse(evidence.candidates.containsKey(new KeyDiscovery.Candidate("block:minecraft:mat", "*", "tint_method")), "known fields are not asked");
        assertEquals("1.21.120", evidence.bdsVersions.get("alpha_masked_tint").last());
        assertEquals("1.21.70", evidence.bdsVersions.get("rounded").last());
        assertEquals("1.21.70", evidence.bdsVersions.get("minecraft:plain").last());
    }

    @Test
    void wikiAndVanillaPacksAddCandidates(@TempDir Path root) throws IOException {
        String components = """
            ### Mat

            -   `*` — Object
                -   `texture` — String
                -   `emissive` — Boolean (optional)

            ```json
            "minecraft:mat": {"*": {"texture": "x"}}
            ```
            """;
        String history = """
            ## 1.21.120

            ### Components

            <Label color="green">Versioned</Label>

            -   Updated `minecraft:mat`
                -   Added `shine` instance parameter which determines something.
            """;
        Path pack = Files.createDirectories(root.resolve("pack/blocks"));
        Files.writeString(pack.resolve("a.json"), "{\"minecraft:block\": {\"components\": {\"minecraft:mat\": {\"*\": {\"texture\": \"a\", \"glow\": true}}}}}");
        Files.writeString(pack.resolve("broken.json"), "{ // not json");
        AddonGenerator generator = generator(root);

        KeyDiscovery.Evidence evidence = KeyDiscovery.gather(generator.model(), Files.write(root.resolve("empty"), new byte[0]),
            cache(root, components, history), List.of(root.resolve("pack")));

        KeyDiscovery.Candidate emissive = new KeyDiscovery.Candidate("block:minecraft:mat", "*", "emissive");
        assertTrue(evidence.candidates.get(emissive).stream().anyMatch(s -> s.startsWith("wiki.bedrock.dev blocks/block-components")));
        assertTrue(evidence.candidates.get(new KeyDiscovery.Candidate("block:minecraft:mat", "*", "shine")).stream()
            .anyMatch(s -> s.startsWith("wiki.bedrock.dev blocks/block-format-history")));
        assertTrue(evidence.candidates.get(new KeyDiscovery.Candidate("block:minecraft:mat", "*", "glow")).stream()
            .anyMatch(s -> s.startsWith("vanilla pack")));
        assertEquals("1.21.120", evidence.wikiVersions.get("shine").last(), "a Versioned change names the version of its key");
        assertEquals("1.21.120", evidence.wikiVersions.get("minecraft:mat").last());
        assertTrue(evidence.bdsVersions.isEmpty(), "the wiki never speaks for BDS");
    }

    @Test
    void answersOfBdsClassifyTheKeys(@TempDir Path root) throws IOException {
        AddonGenerator generator = generator(root);
        KeyDiscovery.Evidence evidence = new KeyDiscovery.Evidence();
        evidence.bdsVersions.put("emissive", new java.util.TreeSet<>(Set.of("1.21.120")));
        for (String key : List.of("emissive", "bogus", "picky")) {
            evidence.candidate(new KeyDiscovery.Candidate("block:minecraft:mat", "*", key), "test");
        }
        KeyDiscovery.plan(generator, evidence, Set.of());

        Map<String, Map<String, Leaf>> leaves = new java.util.HashMap<>();
        Map<String, List<String>> log = new java.util.HashMap<>();
        for (AddonGenerator.Variant v : generator.variants()) {
            String path = v.path();
            boolean accept = v.role().equals("base") || (path.endsWith("emissive") && v.label().equals("key:boolean"))
                || (path.endsWith("picky") && v.label().equals("key:string"));
            if (accept) {
                leaves.put("block " + v.identifier(), Map.of("components.x", new Leaf("byte", path.length())));
            } else if (path.endsWith("bogus")) {
                log.put(v.identifier(), List.of("ERROR [Blocks] block_definitions | pack | " + v.file() + " |  -> components -> minecraft:mat -> * -> bogus: "
                    + "this member was found in the input, but is not present in the Schema"));
            } else {
                log.put(v.identifier(), List.of("ERROR [Blocks] block_definitions | pack | " + v.file() + " |  -> components -> minecraft:mat -> * -> " + path.substring(2)
                    + ": expected a string"));
            }
        }
        JsonObject result = KeyDiscovery.results(generator, evidence, "9.9.9.9", leaves, log, null);

        JsonObject mat = result.getAsJsonObject("components").getAsJsonObject("block:minecraft:mat");
        JsonObject accepted = mat.getAsJsonObject("accepted");
        assertEquals(List.of("*.emissive", "*.picky"), List.copyOf(accepted.keySet()));
        assertEquals("[\"boolean\"]", accepted.getAsJsonObject("*.emissive").get("types").toString());
        assertEquals("1.21.120", accepted.getAsJsonObject("*.emissive").get("minFormatVersion").getAsString());
        assertEquals("[\"string\"]", accepted.getAsJsonObject("*.picky").get("types").toString());
        assertEquals("[\"paletter_a\"]", accepted.getAsJsonObject("*.picky").get("examples").toString(), "the values BDS accepted");
        assertEquals("[\"*.bogus\"]", mat.getAsJsonArray("unknown").toString());
        assertFalse(mat.has("known"));
    }

    @Test
    void errorsTheBaseHasSayNothingAboutAKey() {
        List<String> base = List.of("ERROR [Blocks] x | y | blocks/b_mat_1.json |  -> components: Block needs both");
        Set<String> inBase = KeyDiscovery.errors(base, Set.of());
        List<String> variant = List.of("ERROR [Blocks] x | y | blocks/b_mat_2.json |  -> components: Block needs both",
            "ERROR [Blocks] x | y | blocks/b_mat_2.json |  -> components -> minecraft:mat -> k: expected an object", "WARN [Blocks] ignored");
        assertEquals(1, KeyDiscovery.errors(variant, inBase).size());
    }

    @Test
    void earlierAnswersOfTheSameBuildAreNotAskedAgain(@TempDir Path root) throws IOException {
        Path model = model(root);
        Files.writeString(model.resolve(Fixtures.KEYS_FILE), "{\"bdsVersion\": \"9.9.9.9\", \"components\": {\"block:minecraft:mat\": "
            + "{\"accepted\": {}, \"unknown\": [\"*.bogus\"], \"known\": {\"*.picky\": \"expected an object\"}}}}");
        Fixtures fixtures = Fixtures.read(model);
        assertEquals(Set.of("block:minecraft:mat:*.bogus", "block:minecraft:mat:*.picky"), KeyDiscovery.answered(fixtures, "9.9.9.9"));
        assertTrue(KeyDiscovery.answered(fixtures, "9.9.9.8").isEmpty(), "another build may know the keys");
    }
}
