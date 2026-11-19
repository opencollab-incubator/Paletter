package dev.opencollab.paletter.tools.client;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureCodecTest {
    @Test
    void anotherCodecIsRecordedNextToTheProtocol() {
        JsonObject json = new JsonObject();
        CaptureWriter.addProtocol(json, 2259, 2225);
        assertEquals(2259, json.get("protocol").getAsInt());
        assertEquals(2225, json.get("codecProtocol").getAsInt());
    }

    @Test
    void aNativeCodecLeavesItOut() {
        JsonObject json = new JsonObject();
        CaptureWriter.addProtocol(json, 2225, 2225);
        assertEquals(2225, json.get("protocol").getAsInt());
        assertFalse(json.has("codecProtocol"));
    }

    @Test
    void theCodecOfAProtocolTheLibraryHasIsNative() {
        int newest = Capture.NEWEST.getProtocolVersion();
        assertEquals(newest, Capture.codecFor(newest).getProtocolVersion());
        assertTrue(Capture.codecFor(newest + 1000).getProtocolVersion() != newest + 1000);
    }
}
