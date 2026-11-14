package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.internal.component.WireOnlyWire;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hand-written windows of {@code WireOnlyWire} that the captures can date: {@code mining_speed} and {@code frame_count}
 * are leaves of the item envelope ({@code envelope} of {@code mappings.json}). Their window must not start after the
 * oldest envelope window, and every envelope window must carry them.
 */
class WireOnlyWindowsTest {
    @Test
    void theItemPropertiesOfWireOnlyComponentsAreInEveryEnvelopeWindow() throws IOException {
        JsonObject mappings = JsonParser.parseString(Files.readString(ComponentConformanceTest.MODEL.resolve("mappings.json"),
            StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray windows = mappings.getAsJsonObject("envelope").getAsJsonArray("item");
        int oldest = windows.get(0).getAsJsonObject().getAsJsonArray("protocols").get(0).getAsInt();
        for (Map.Entry<String, Integer> leaf : Map.of("item_properties.mining_speed", WireOnlyWire.MINING_SPEED_FROM,
            "item_properties.frame_count", WireOnlyWire.FRAME_COUNT_FROM).entrySet()) {
            assertTrue(leaf.getValue() <= oldest, leaf.getKey() + ": WireOnlyWire's window starts after the oldest capture, " + oldest);
            for (JsonElement window : windows) {
                assertTrue(window.getAsJsonObject().getAsJsonObject("leaves").has(leaf.getKey()), leaf.getKey() + " is not in " + window);
            }
        }
    }
}
