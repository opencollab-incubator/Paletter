package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.model.MappingWindows;
import org.geysermc.paletter.tools.model.Schema;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a capture without a variant of a component is a build that does not have it (its own schema lacks it, another
 * build's names it) and when it is a missing capture, which the component API still fails on.
 */
class AbsentComponentTest {
    private static final String ID = "block:minecraft:new_thing";

    private static Schema schema(String build, String blocks, String skipped) {
        return Schema.of(JsonParser.parseString("""
            {"bdsVersion": "%s", "formats": {"block": ["1.26.20"]}, "block": {%s}, "skipped": [%s]}
            """.formatted(build, blocks, skipped)).getAsJsonObject());
    }

    private static final String OLD_BLOCK = "\"minecraft:old_thing\": [{\"formats\": [\"1.26.20\"], \"root\": {\"types\": [\"boolean\"]}, \"fields\": {}}]";
    private static final String NEW_BLOCK = "\"minecraft:new_thing\": [{\"formats\": [\"1.26.20\"], \"root\": {\"types\": [\"boolean\"]}, \"fields\": {}}]";

    private static SpecCaptures.Capture capture(String bdsVersion, String... components) {
        List<SpecCaptures.Variant> variants = Stream.of(components).map(c -> SpecCaptures.Variant.of(JsonParser.parseString("""
            {"identifier": "paletter:v", "kind": "block", "file": "blocks/v.json", "component": "%s", "role": "control", "path": "", "label": "control"}
            """.formatted(c)).getAsJsonObject())).toList();
        return new SpecCaptures.Capture("data/bds/" + bdsVersion + "/spec", bdsVersion, 1, variants, Map.of(), Map.of(), List.of(),
            new Feedback(), Map.of(), null);
    }

    private static Map<String, Schema> schemas() {
        Map<String, Schema> schemas = new TreeMap<>();
        schemas.put("1.0.0", schema("1.0.0", OLD_BLOCK, ""));
        schemas.put("2.0.0", schema("2.0.0", OLD_BLOCK + ", " + NEW_BLOCK, ""));
        return schemas;
    }

    @Test
    void aBuildWhoseOwnSchemaLacksTheComponentDoesNotHaveIt() {
        JsonObject result = MappingInference.absent(capture("1.0.0", "block:minecraft:old_thing"), ID, schemas());
        assertNotNull(result);
        assertEquals(MappingWindows.ABSENT, result.get("status").getAsString());
        assertTrue(result.get("note").getAsString().contains("1.0.0"), result.toString());
    }

    @Test
    void aBuildWithoutASchemaOfItsOwnIsAMissingCapture() {
        // The older build's schema is not evidence for a newer build that has none
        assertNull(MappingInference.absent(capture("1.5.0"), ID, schemas()));
    }

    @Test
    void aBuildWhoseSchemaHasTheComponentIsAMissingCapture() {
        assertNull(MappingInference.absent(capture("2.0.0"), ID, schemas()));
    }

    @Test
    void aComponentNoSchemaNamesIsNeverAbsent() {
        // e.g. a component of the docs the schemas call otherwise: their silence says nothing
        assertNull(MappingInference.absent(capture("1.0.0"), "block:minecraft:docs_only", schemas()));
    }

    @Test
    void aComponentTheExtractSkippedIsNotAbsent() {
        Map<String, Schema> schemas = schemas();
        schemas.put("1.0.0", schema("1.0.0", OLD_BLOCK, "\"block 1.26.20 minecraft:new_thing: unreadable\""));
        assertNull(MappingInference.absent(capture("1.0.0"), ID, schemas));
    }

    @Test
    void aCaptureWithVariantsOfTheComponentIsNotAbsent() {
        assertNull(MappingInference.absent(capture("1.0.0", ID), ID, schemas()));
    }

    /** The committed schemas: a build without one of its own never has an absent component, nor one whose schema names it. */
    @Test
    void theCommittedSchemasDecideOnlyForTheirOwnBuild() throws IOException {
        Path bds = Path.of(System.getProperty("paletter.root", "..")).resolve("data/bds");
        Map<String, Schema> schemas = new TreeMap<>();
        List<String> builds;
        try (Stream<Path> dirs = Files.list(bds)) {
            builds = dirs.filter(Files::isDirectory).map(d -> d.getFileName().toString()).sorted().toList();
        }
        for (String build : builds) {
            Path file = bds.resolve(build).resolve(Schema.FILE);
            if (Files.isRegularFile(file)) {
                schemas.put(build, Schema.read(file));
            }
        }
        assertTrue(schemas.containsKey("1.26.63.1"), schemas.keySet().toString());
        Schema newest = schemas.get("1.26.63.1");
        String component = "block:minecraft:enchantment_power_transmitter";
        assertTrue(newest.names(component), "BDS 1.26.63.1 has it");
        assertNull(MappingInference.absent(capture("1.26.63.1"), component, schemas), "its own build: a missing capture");
        for (String build : builds) {
            if (!schemas.containsKey(build)) {
                assertNull(MappingInference.absent(capture(build), component, schemas), build + " has no schema of its own");
            }
        }
        // An older build whose own schema is the 1.26.63.1 one without the component does not have it
        JsonObject older = JsonParser.parseString(Files.readString(bds.resolve("1.26.63.1").resolve(Schema.FILE))).getAsJsonObject();
        older.getAsJsonObject("block").remove("minecraft:enchantment_power_transmitter");
        older.addProperty("bdsVersion", "1.26.36.1");
        Map<String, Schema> withOlder = new TreeMap<>(schemas);
        withOlder.put("1.26.36.1", Schema.of(older));
        JsonObject absent = MappingInference.absent(capture("1.26.36.1"), component, withOlder);
        assertNotNull(absent);
        assertEquals(MappingWindows.ABSENT, absent.get("status").getAsString());
    }
}
