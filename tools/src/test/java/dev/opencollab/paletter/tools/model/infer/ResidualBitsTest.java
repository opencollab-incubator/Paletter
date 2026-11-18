package dev.opencollab.paletter.tools.model.infer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The unknown bit gate: bits every value of a table sets that no field of the leaf explains. */
class ResidualBitsTest {
    private static JsonObject field(String wire, String nbt, String table) {
        return JsonParser.parseString("{\"status\": \"inferred\", \"rules\": [{\"kind\": \"table\", \"table\": " + table
            + ", \"nbt\": \"" + nbt + "\", \"tags\": [], \"wire\": \"" + wire + "\"}]}").getAsJsonObject();
    }

    private static JsonObject result(JsonObject... fields) {
        JsonObject all = new JsonObject();
        for (int i = 0; i < fields.length; i++) {
            all.add("f" + i, fields[i]);
        }
        JsonObject result = new JsonObject();
        result.add("fields", all);
        return result;
    }

    @Test
    void bitsOfTwoPackedFieldsLeaveTheUnexplainedOne() {
        // face_dimming: 4/5, isotropic: 5/7 (bit 0 and bit 1 are theirs, bit 2 is in every value)
        JsonObject result = result(field("m.materials.*.packed_bools", "byte", "{\"false\": \"4\", \"true\": \"5\"}"),
            field("m.materials.*.packed_bools", "byte", "{\"false\": \"5\", \"true\": \"7\"}"));
        JsonArray findings = Findings.findings("block:m", result);
        assertEquals(1, findings.size());
        JsonObject finding = findings.get(0).getAsJsonObject();
        assertEquals("residual-bits block:m packed_bools", finding.get("id").getAsString());
        assertEquals("[2]", finding.get("bits").toString());
        assertEquals("[\"4\",\"5\",\"7\"]", finding.get("values").toString());
        assertEquals("[2]", result.getAsJsonObject("fields").getAsJsonObject("f0").getAsJsonArray("rules").get(0).getAsJsonObject().get("residualBits").toString());
    }

    @Test
    void anEnumThatUsesAllItsBitsHasNoResidual() {
        assertTrue(Findings.findings("c", result(field("c.x", "int", "{\"a\": \"0\", \"b\": \"1\", \"c\": \"2\", \"d\": \"3\"}"))).isEmpty());
        assertTrue(Findings.findings("c", result(field("c.x", "byte", "{\"false\": \"0\", \"true\": \"1\"}"))).isEmpty());
    }

    @Test
    void aLoneTableWithACommonBitIsAFinding() {
        JsonArray findings = Findings.findings("c", result(field("c.x", "byte", "{\"false\": \"4\", \"true\": \"6\"}")));
        assertEquals("[2]", findings.get(0).getAsJsonObject().get("bits").toString());
    }

    @Test
    void stringAndNegativeTablesAreNotIntegralBits() {
        assertTrue(Findings.findings("c", result(field("c.x", "string", "{\"a\": \"4\", \"b\": \"6\"}"))).isEmpty());
        assertTrue(Findings.findings("c", result(field("c.x", "int", "{\"a\": \"-4\", \"b\": \"-2\"}"))).isEmpty());
    }

    /** The newest capture decides: a bit only a newer build sets must not hide behind an older, explained finding. */
    @Test
    void aBitOnlyTheNewerCaptureSetsIsNotHiddenByTheOlderFinding() {
        // older build: bit 2 is in every value, and the format sweep explains it
        JsonObject older = result(field("m.materials.*.packed_bools", "byte", "{\"false\": \"4\", \"true\": \"5\"}"));
        older.add("findings", Findings.findings("block:m", older));
        // newer build: bit 3 as well, which the sweep does not explain
        JsonObject newer = result(field("m.materials.*.packed_bools", "byte", "{\"false\": \"12\", \"true\": \"13\"}"));
        newer.add("findings", Findings.findings("block:m", newer));
        Map<String, Long> formatBits = Map.of("block:m packed_bools", 4L);
        Map<String, String> findings = new TreeMap<>();
        Findings.collectFindings(findings, "block:m", older, formatBits);
        assertTrue(!findings.values().iterator().next().contains("UNEXPLAINED"), findings.toString());
        Findings.collectFindings(findings, "block:m", newer, formatBits);
        assertEquals(1, findings.size());
        assertTrue(findings.values().iterator().next().contains("UNEXPLAINED"), "the newer capture's bit 3 is unexplained: " + findings);
    }
}
