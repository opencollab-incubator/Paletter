package dev.opencollab.paletter.tools.model.infer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.model.MappingWindows;
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
        JsonObject merged = MappingWindows.merge(List.of(
            result("sent", "test:a.x", 5, "message of the old build"),
            result("sent", "test:a.x", 7, "message of the new build"),
            result("sent", "test:a.x", 9, "message of the newest build")), List.of(100, 200, 300));
        assertEquals(List.of("windows"), List.copyOf(merged.keySet()), "the component holds only its window list");
        List<JsonObject> windows = MappingWindows.of(merged);
        assertEquals(1, windows.size());
        assertEquals("[100,200,300]", windows.getFirst().getAsJsonArray("protocols").toString());
        assertEquals("message of the old build", windows.getFirst().getAsJsonObject("fields").getAsJsonObject("x").getAsJsonArray("bdsErrors").get(0).getAsString(),
            "the first capture of the window speaks for it");
    }

    @Test
    void aDifferentShapeStartsAWindowAndIsComparedWithThePreviousOneNotTheFirst() {
        JsonObject merged = MappingWindows.merge(List.of(
            result("sent", "test:a.x", 5, "e"), result("sent", "test:a.y", 5, "e"), result("sent", "test:a.x", 5, "e"),
            result("sent", "test:a.x", 6, "f")), List.of(100, 200, 300, 400));
        List<JsonObject> windows = MappingWindows.of(merged);
        assertEquals(List.of("[100]", "[200]", "[300,400]"), windows.stream().map(w -> w.getAsJsonArray("protocols").toString()).toList(),
            "the third equals the first, but it follows the second: a window of its own, which the fourth joins");
        assertEquals(List.of(100, 200, 300, 400), List.copyOf(MappingWindows.protocols(merged)));
    }

    @Test
    void theProtocolsComeFirstInEachWindow() {
        JsonObject merged = MappingWindows.merge(List.of(result("sent", "test:a.x", 5, "e"), result("sent", "test:a.y", 5, "e")), List.of(100, 200));
        for (JsonObject window : MappingWindows.of(merged)) {
            assertEquals("protocols", window.keySet().iterator().next(), window.keySet().toString());
        }
    }

    @Test
    void aRemovalIsAWindowOfItsOwn() {
        JsonObject gone = result("rejected", "test:a.x", 0, "no longer known");
        gone.add("wire", new com.google.gson.JsonArray());
        gone.add("fields", new JsonObject());
        JsonObject merged = MappingWindows.merge(List.of(result("sent", "test:a.x", 5, "e"), gone), List.of(100, 200));
        assertEquals(2, MappingWindows.of(merged).size());
        assertEquals("rejected", MappingWindows.newest(merged).get("status").getAsString());
        assertEquals("[200]", MappingWindows.newest(merged).getAsJsonArray("protocols").toString());
        assertEquals("sent", MappingWindows.oldest(merged).get("status").getAsString());
    }

    @Test
    void aBuildWithoutTheComponentIsAWindowOfItsOwnNotPartOfItsNeighbour() {
        // the absent result of a build whose schema lacks the component (MappingInference.absent) between two that send it
        JsonObject absent = new JsonObject();
        absent.addProperty("status", MappingWindows.ABSENT);
        JsonObject merged = MappingWindows.merge(List.of(result("sent", "test:a.x", 5, "e"), absent, result("sent", "test:a.x", 5, "e")),
            List.of(100, 200, 300));
        assertEquals(List.of("sent", MappingWindows.ABSENT, "sent"), MappingWindows.of(merged).stream().map(w -> w.get("status").getAsString()).toList());
        assertEquals(List.of("[100]", "[200]", "[300]"), MappingWindows.of(merged).stream().map(w -> w.getAsJsonArray("protocols").toString()).toList());
    }

    @Test
    void withoutResultsThereIsNothing() {
        assertNull(MappingWindows.merge(List.of(), List.of()));
    }

    @Test
    void theWireShapeLeavesOutWhatBdsSaid() {
        JsonObject a = result("sent", "test:a.x", 5, "one");
        JsonObject b = result("sent", "test:a.x", 99, "two");
        b.getAsJsonObject("variants").addProperty("rejected", 40);
        b.addProperty("note", "something");
        assertEquals(MappingWindows.wireShape(a), MappingWindows.wireShape(b));
        b.getAsJsonObject("fields").getAsJsonObject("x").addProperty("status", "dropped");
        assertTrue(!MappingWindows.wireShape(a).equals(MappingWindows.wireShape(b)));
    }
}
