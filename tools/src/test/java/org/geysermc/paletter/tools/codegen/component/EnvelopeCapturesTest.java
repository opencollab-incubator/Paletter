package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.internal.component.WireOnlyWire;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The item and block envelopes ({@code ItemCompiler}, {@code BlockCompiler}) are written as one protocol window. This
 * holds them to it: what BDS sends around the components of a custom item and block (a control variant without any
 * component of its own) is the same in every spec capture, and the item properties of {@code WireOnlyWire}'s fields are
 * there in the oldest one. A capture that differs fails the test: the envelope then needs a window for it.
 */
class EnvelopeCapturesTest {
    private static String controlOf(Path capture, String component) throws IOException {
        for (JsonElement e : JsonParser.parseString(Files.readString(capture.resolve("variants.json"), StandardCharsets.UTF_8)).getAsJsonArray()) {
            JsonObject v = e.getAsJsonObject();
            if (v.get("component").getAsString().equals(component) && v.get("role").getAsString().equals("control")) {
                return v.get("identifier").getAsString();
            }
        }
        throw new AssertionError("no control of " + component + " in " + capture);
    }

    @Test
    void theItemEnvelopeIsTheSameInEveryCapture() throws IOException {
        List<Path> captures = ComponentConformanceTest.captures();
        NbtMap first = null;
        for (Path capture : captures) {
            String id = controlOf(capture, "item:minecraft:max_stack_size");
            NbtMap properties = null;
            for (NbtMap item : ComponentConformanceTest.read(capture.resolve("custom_item_registry.nbt")).getList("items", NbtType.COMPOUND)) {
                if (item.getString("name").equals(id)) {
                    properties = item.getCompound("data").getCompound("components").getCompound("item_properties");
                }
            }
            assertNotNull(properties, id + " in " + capture);
            assertTrue(properties.containsKey("mining_speed") && properties.containsKey("frame_count"), capture + ": " + properties);
            assertTrue(WireOnlyWire.MINING_SPEED_FROM <= ComponentConformanceTest.protocolOf(capture), "mining_speed window starts after " + capture);
            assertTrue(WireOnlyWire.FRAME_COUNT_FROM <= ComponentConformanceTest.protocolOf(capture), "frame_count window starts after " + capture);
            if (first == null) {
                first = properties;
            } else {
                assertEquals(first, properties, "item_properties of " + capture + " differ from the first capture's: ItemCompiler needs a window");
            }
        }
    }

    @Test
    void theBlockEnvelopeIsTheSameInEveryCapture() throws IOException {
        NbtMap first = null;
        for (Path capture : ComponentConformanceTest.captures()) {
            String id = controlOf(capture, "block:minecraft:friction");
            NbtMap properties = null;
            for (NbtMap block : ComponentConformanceTest.read(capture.resolve("custom_block_properties.nbt")).getList("blocks", NbtType.COMPOUND)) {
                if (block.getString("name").equals(id)) {
                    NbtMap p = block.getCompound("properties");
                    org.cloudburstmc.nbt.NbtMapBuilder data = p.getCompound("vanilla_block_data").toBuilder();
                    data.remove("block_id");
                    properties = p.toBuilder().putCompound("vanilla_block_data", data.build()).build();
                }
            }
            assertNotNull(properties, id + " in " + capture);
            if (first == null) {
                first = properties;
            } else {
                assertEquals(first, properties, "block properties of " + capture + " differ from the first capture's: BlockCompiler needs a window");
            }
        }
    }
}
