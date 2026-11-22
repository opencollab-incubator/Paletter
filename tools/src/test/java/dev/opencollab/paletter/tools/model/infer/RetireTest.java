package dev.opencollab.paletter.tools.model.infer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Retire}: a protocol leaves the windows, alike neighbours join, and what else of it must go is listed. */
class RetireTest {
    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static final String MAPPINGS = """
        {"_generated": "g", "captures": [
           {"bdsVersion": "1.1", "dataVersion": "1.1", "protocol": 10, "windowProtocol": 10},
           {"bdsVersion": "1.2", "dataVersion": "1.2", "protocol": 20, "windowProtocol": 20},
           {"bdsVersion": "1.3", "dataVersion": "1.3", "protocol": 30, "windowProtocol": 30}],
         "envelope": {"item": [{"protocols": [10, 20], "leaves": {"a": "1 (int)"}}, {"protocols": [30], "leaves": {"a": "2 (int)"}}]},
         "components": {
           "item:x": {"windows": [
             {"protocols": [10], "status": "sent", "fields": {"f": {"status": "inferred", "rules": [{"kind": "identity", "wire": "x.f"}]}}},
             {"protocols": [20], "status": "sent", "fields": {"f": {"status": "inferred", "rules": [{"kind": "identity", "wire": "x.g"}]}}},
             {"protocols": [30], "status": "sent", "fields": {"f": {"status": "inferred", "rules": [{"kind": "identity", "wire": "x.f"}]}}}]},
           "item:only20": {"windows": [{"protocols": [20], "status": "sent", "fields": {}}]}}}""";

    private static final String EVIDENCE = """
        {"_generated": "g", "components": {
           "item:x": {"windows": [{"protocols": [10], "fields": {"f": {"accepted": 1}}}, {"protocols": [20], "fields": {"f": {"accepted": 2}}},
                                  {"protocols": [30], "fields": {"f": {"accepted": 3}}}]},
           "item:only20": {"windows": [{"protocols": [20]}]}}}""";

    @Test
    void retiringTheMiddleProtocolJoinsItsAlikeNeighbours() {
        JsonObject mappings = json(MAPPINGS);
        JsonObject evidence = json(EVIDENCE);
        List<JsonObject> retired = Retire.retire(mappings, evidence, 20);
        assertEquals(1, retired.size());
        assertEquals("1.2", retired.getFirst().get("bdsVersion").getAsString());
        assertEquals(2, mappings.getAsJsonArray("captures").size());
        // A(10) B(20) A(30) without 20: one window, the older one's content
        JsonObject x = mappings.getAsJsonObject("components").getAsJsonObject("item:x");
        assertEquals(1, x.getAsJsonArray("windows").size(), x.toString());
        assertEquals("[10,30]", x.getAsJsonArray("windows").get(0).getAsJsonObject().get("protocols").toString());
        JsonObject ex = evidence.getAsJsonObject("components").getAsJsonObject("item:x");
        assertEquals(json("{\"protocols\": [10, 30], \"fields\": {\"f\": {\"accepted\": 1}}}"), ex.getAsJsonArray("windows").get(0).getAsJsonObject());
        assertEquals(1, ex.getAsJsonArray("windows").size());
        // A component only the retired protocol had leaves both files
        assertFalse(mappings.getAsJsonObject("components").has("item:only20"));
        assertFalse(evidence.getAsJsonObject("components").has("item:only20"));
        // The envelope loses the protocol; its windows still differ
        assertEquals("[{\"protocols\":[10],\"leaves\":{\"a\":\"1 (int)\"}},{\"protocols\":[30],\"leaves\":{\"a\":\"2 (int)\"}}]",
            mappings.getAsJsonObject("envelope").getAsJsonArray("item").toString());
    }

    @Test
    void anUnknownProtocolFails() {
        assertThrows(IllegalArgumentException.class, () -> Retire.retire(json(MAPPINGS), json(EVIDENCE), 99));
    }

    @Test
    void misalignedEvidenceFails() {
        JsonObject evidence = json(EVIDENCE);
        evidence.getAsJsonObject("components").getAsJsonObject("item:x").getAsJsonArray("windows").remove(2);
        assertThrows(IllegalArgumentException.class, () -> Retire.retire(json(MAPPINGS), evidence, 20));
    }

    @Test
    void theFilesAreRewrittenAndTheLeftoversListed(@TempDir Path root) throws IOException {
        Path model = Files.createDirectories(root.resolve("data/model"));
        Files.writeString(model.resolve("mappings.json"), MAPPINGS);
        Files.writeString(model.resolve(MappingEvidence.FILE), EVIDENCE);
        Path resources = Files.createDirectories(root.resolve("paletter/src/main/resources/paletter"));
        Files.writeString(resources.resolve("versions.txt"), "# header\n1.1 10\n1.2 20\n1.3 30\n");
        Files.writeString(resources.resolve("1.2.nbt"), "");
        Files.createDirectories(root.resolve("data/bds/1.2/spec"));
        Files.createDirectories(root.resolve("tools/src/test/resources/test-data/1.2"));
        Files.writeString(root.resolve("tools/src/test/resources/test-data/SOURCE.md"), "| 1.2 | abc |\n");
        List<String> leftovers = Retire.run(root, 20);
        assertEquals(List.of("paletter/src/main/resources/paletter/versions.txt: the row of 1.2", "paletter/src/main/resources/paletter/1.2.nbt",
            "tools/src/test/resources/test-data/1.2/", "tools/src/test/resources/test-data/SOURCE.md: the row of 1.2", "data/bds/1.2/"),
            leftovers.subList(0, 5));
        assertTrue(leftovers.getLast().startsWith("then: builds table, inferMappings"));
        JsonObject written = json(Files.readString(model.resolve("mappings.json")));
        assertEquals(2, written.getAsJsonArray("captures").size());
        assertTrue(Files.readString(model.resolve(MappingEvidence.FILE)).contains("\"accepted\": 1"));
    }
}
