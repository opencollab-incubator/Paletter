package dev.opencollab.paletter.tools.client;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PacketOrderTest {
    @Test
    void writesTheOrderAndTheStartGameIndex() {
        CaptureClient.Result result = new CaptureClient.Result();
        result.packetOrder.addAll(java.util.List.of("-> RequestNetworkSettingsPacket", "<- NetworkSettingsPacket", "<- StartGamePacket", "-> RequestChunkRadiusPacket"));
        result.startGameIndex = 2;

        JsonObject json = new JsonObject();
        CaptureWriter.addPacketOrder(json, result);

        assertEquals(4, json.getAsJsonArray("packetOrder").size());
        assertEquals("<- StartGamePacket", json.getAsJsonArray("packetOrder").get(json.get("startGameIndex").getAsInt()).getAsString());
    }

    @Test
    void startGameIndexIsMinusOneWithoutStartGame() {
        CaptureClient.Result result = new CaptureClient.Result();
        JsonObject json = new JsonObject();
        CaptureWriter.addPacketOrder(json, result);
        assertEquals(-1, json.get("startGameIndex").getAsInt());
        assertFalse(json.getAsJsonArray("packetOrder").iterator().hasNext());
    }
}
