package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the results of several captures become protocol windows of one component. */
class WindowMergeTest {
    private static JsonObject result(String status, String wire, int accepted, String error) {
        return JsonParser.parseString("""
            {"variants": {"accepted": %d, "rejected": 1}, "wire": ["test:a"], "status": "%s",
             "fields": {"x": {"accepted": %d, "rejected": 0, "bdsErrors": ["%s"], "status": "inferred",
                        "rules": [{"kind": "identity", "nbt": "int", "tags": [], "wire": "%s"}]}}}
            """.formatted(accepted, status, accepted, error, wire)).getAsJsonObject();
    }

    @Test
    void sameShapeJoinsThePreviousWindowWhateverBdsSaid() {
        JsonObject merged = MappingInference.merge(List.of(
            result("sent", "test:a.x", 5, "message of the old build"),
            result("sent", "test:a.x", 7, "message of the new build"),
            result("sent", "test:a.x", 9, "message of the newest build")), List.of(100, 200, 300));
        assertEquals("[100,200,300]", merged.getAsJsonArray("protocols").toString());
        assertTrue(merged.keySet().stream().noneMatch(k -> k.startsWith("protocol") && !k.equals("protocols")), merged.keySet().toString());
    }

    @Test
    void aDifferentShapeStartsAWindowAndIsComparedWithThePreviousOneNotTheFirst() {
        JsonObject merged = MappingInference.merge(List.of(
            result("sent", "test:a.x", 5, "e"), result("sent", "test:a.y", 5, "e"), result("sent", "test:a.x", 5, "e"),
            result("sent", "test:a.x", 6, "f")), List.of(100, 200, 300, 400));
        assertEquals("[100]", merged.getAsJsonArray("protocols").toString());
        assertEquals("[200]", merged.getAsJsonObject("protocol200").getAsJsonArray("protocols").toString());
        assertEquals("[300,400]", merged.getAsJsonObject("protocol300").getAsJsonArray("protocols").toString(),
            "the third equals the first, but it follows the second: a window of its own, which the fourth joins");
        assertNull(merged.get("protocol400"));
    }

    @Test
    void aRemovalIsAWindowOfItsOwn() {
        JsonObject gone = result("rejected", "test:a.x", 0, "no longer known");
        gone.add("wire", new com.google.gson.JsonArray());
        gone.add("fields", new JsonObject());
        JsonObject merged = MappingInference.merge(List.of(result("sent", "test:a.x", 5, "e"), gone), List.of(100, 200));
        assertEquals("rejected", merged.getAsJsonObject("protocol200").get("status").getAsString());
        assertEquals(merged.getAsJsonObject("protocol200"), MappingInference.newest(merged));
    }

    @Test
    void withoutResultsThereIsNothing() {
        assertNull(MappingInference.merge(List.of(), List.of()));
    }

    @Test
    void theWireShapeLeavesOutWhatBdsSaid() {
        JsonObject a = result("sent", "test:a.x", 5, "one");
        JsonObject b = result("sent", "test:a.x", 99, "two");
        b.getAsJsonObject("variants").addProperty("rejected", 40);
        b.addProperty("note", "something");
        assertEquals(MappingInference.wireShape(a), MappingInference.wireShape(b));
        b.getAsJsonObject("fields").getAsJsonObject("x").addProperty("status", "dropped");
        assertTrue(!MappingInference.wireShape(a).equals(MappingInference.wireShape(b)));
    }
}
