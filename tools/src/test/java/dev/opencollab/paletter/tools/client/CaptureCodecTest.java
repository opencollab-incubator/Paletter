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

    /** NEWEST is the highest codec the library ships, found without naming it: a bump of the library moves it. */
    @Test
    void theNewestCodecIsTheHighestTheLibraryShips() {
        java.util.SortedSet<Integer> protocols = Capture.codecProtocols();
        assertEquals(protocols.last().intValue(), Capture.NEWEST.getProtocolVersion());
        // The committed captures were decoded with 2225, so the library has at least that one, and older ones besides
        assertTrue(protocols.contains(2225), protocols.toString());
        assertTrue(protocols.size() > 10, protocols.toString());
        for (int protocol : protocols.tailSet(protocols.last() - 100)) {
            assertEquals(protocol, Capture.codecFor(protocol).getProtocolVersion(), "Bedrock_v" + protocol);
        }
    }

    @Test
    void theCodecOfAProtocolTheLibraryHasIsNative() {
        int newest = Capture.NEWEST.getProtocolVersion();
        assertEquals(newest, Capture.codecFor(newest).getProtocolVersion());
        assertTrue(Capture.codecFor(newest + 1000).getProtocolVersion() != newest + 1000);
    }
}
