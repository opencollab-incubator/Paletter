package org.geysermc.paletter.codegen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Bedrock version that changes a component's shape is a new spec entry: observations are split by the protocol of
 * their source at the component's {@code protocolSplits}, and each window becomes its own generated shape. The newest
 * keeps the class name, so the encoders only gain a case for the old one.
 */
class ProtocolWindowTest {

    private static void capture(Path dir, int protocol, Object value) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("capture.json"), "{\"protocol\": " + protocol + "}");
        NbtMap root = NbtMap.builder().putList("items", NbtType.COMPOUND, NbtMap.builder()
            .putString("name", "test:item")
            .putCompound("components", NbtMap.builder()
                .putCompound("test:thing", NbtMap.fromMap(Map.of("value", value)))
                .putCompound("test:stable", NbtMap.builder().putInt("count", protocol).build())
                .build())
            .build()).build();
        try (OutputStream stream = Files.newOutputStream(dir.resolve("custom_item_registry.nbt"));
             NBTOutputStream output = NbtUtils.createWriter(stream)) {
            output.writeTag(root);
        }
    }

    @Test
    void aChangedShapeBecomesASecondWindow(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("paletter/src/main/resources/paletter"));
        capture(root.resolve("codegen/captures/old"), 2200, (byte) 1);
        capture(root.resolve("codegen/captures/new"), 2300, "one");
        JsonObject overrides = JsonParser.parseString("""
            {"components": {"item:test:thing": {"protocolSplits": [2250]}}}""").getAsJsonObject();

        JsonObject components = Inference.infer(root, List.of(), overrides).getAsJsonObject("components");
        JsonObject newest = components.getAsJsonObject("item:test:thing");
        JsonObject old = components.getAsJsonObject("item:test:thing@..2250");
        assertEquals(2250, newest.get("sinceProtocol").getAsInt());
        assertFalse(newest.has("untilProtocol"));
        assertEquals("string", newest.getAsJsonObject("shape").getAsJsonObject("fields").getAsJsonObject("value").get("type").getAsString());
        assertEquals(2250, old.get("untilProtocol").getAsInt());
        assertFalse(old.has("sinceProtocol"));
        assertEquals("byte", old.getAsJsonObject("shape").getAsJsonObject("fields").getAsJsonObject("value").get("type").getAsString());
        // Components without splits keep one unbounded shape, whatever the protocol of their sources
        JsonObject stable = components.getAsJsonObject("item:test:stable");
        assertFalse(stable.has("sinceProtocol") || stable.has("untilProtocol"));

        Map<String, String> files = new Generator(wrap(components), overrides).generate();
        String thing = files.get("item/Thing.java");
        String thingOld = files.get("item/ThingUntil2250.java");
        assertTrue(thing.contains("public record Thing(@NonNull String value)"), thing);
        assertTrue(thing.contains("2250, ComponentType.UNBOUNDED_UNTIL, Thing::fromNbt"), thing);
        assertTrue(thingOld.contains("public record ThingUntil2250(boolean value)"), thingOld);
        assertTrue(thingOld.contains("ComponentType.UNBOUNDED_SINCE, 2250, ThingUntil2250::fromNbt"), thingOld);
        assertTrue(thing.contains("package " + Generator.BASE_PACKAGE + ".item;"), thing);
        String registry = files.get("item/ItemComponentTypes.java");
        assertTrue(registry.contains("Thing.TYPE") && registry.contains("ThingUntil2250.TYPE"), registry);
    }

    private static JsonObject wrap(JsonObject components) {
        JsonObject spec = new JsonObject();
        spec.add("components", components);
        return spec;
    }

    @Test
    void captureProtocolComesFromTheNearestSummary(@TempDir Path root) throws IOException {
        Path captures = root.resolve("captures");
        Path file = captures.resolve("bds/addon/custom_item_registry.nbt");
        Files.createDirectories(file.getParent());
        assertEquals(-1, Inference.captureProtocol(captures, file));
        Files.writeString(captures.resolve("bds/capture.json"), "{\"bdsVersion\": \"1.26.63.1\", \"referencePalette\": \"26.60 (2225)\"}");
        assertEquals(-1, Inference.captureProtocol(captures, file));
        Files.writeString(captures.resolve("bds/capture.json"), "{\"protocol\": 2225}");
        assertEquals(2225, Inference.captureProtocol(captures, file));
        Files.writeString(captures.resolve("bds/addon/capture.json"), "{\"protocol\": 2300}");
        assertEquals(2300, Inference.captureProtocol(captures, file));
        assertEquals("item:minecraft:damage", Inference.baseKey("item:minecraft:damage@2200..2250"));
    }
}
