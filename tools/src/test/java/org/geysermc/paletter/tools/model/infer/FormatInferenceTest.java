package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.tools.data.Nbt;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The format sweep's capture read as the inference reads it: each variant against the control of its own format. */
class FormatInferenceTest {
    private static String component(String kind, String name, String status) {
        return "{\"id\": \"" + kind + ":minecraft:" + name + "\", \"kind\": \"" + kind + "\", \"category\": \"component\", \"name\": \"minecraft:" + name
            + "\", \"status\": \"" + status + "\", \"doc\": \"\", \"page\": \"\", \"experiments\": [], \"notes\": [], \"forms\": [{\"type\": \"object\"}], \"fields\": []}";
    }

    private static final String MODEL = "{\"source\": \"t\", \"commit\": \"t\", \"license\": \"t\", \"formatNotes\": [], \"components\": ["
        + String.join(", ", component("block", "old_light", "deprecated"), component("block", "light", "active"), component("block", "gone", "deprecated"),
            component("item", "seed", "deprecated"), component("item", "silent", "deprecated")) + "]}";
    private static final String FIXTURES = "{\"formatVersion\": \"1.26.60\", \"filler\": {\"minecraft:filler\": {}}, \"errata\": [], \"samples\": {},"
        + " \"mapKeys\": {\"default\": \"k\"}, \"context\": {}, \"skip\": {}, \"requires\": {}}";

    private int blockIds;

    private NbtMap block(String name, NbtMap components) {
        return NbtMap.builder().putString("name", name).putCompound("properties", NbtMap.builder().putCompound("components", components.toBuilder().putCompound("minecraft:base", NbtMap.EMPTY).build())
            .putCompound("vanilla_block_data", NbtMap.builder().putInt("block_id", ++blockIds).build()).build()).build();
    }

    private static NbtMap item(String name, boolean componentBased, NbtMap components) {
        return NbtMap.builder().putString("name", name).putBoolean("componentBased", componentBased)
            .putCompound("data", NbtMap.builder().putCompound("components", components).build()).build();
    }

    private static JsonObject variant(String identifier, String kind, String component, String role, String format) {
        JsonObject v = new JsonObject();
        v.addProperty("identifier", identifier);
        v.addProperty("kind", kind);
        v.addProperty("file", kind + "s/" + identifier.substring(8) + ".json");
        v.addProperty("component", component);
        v.addProperty("role", role);
        v.addProperty("path", "");
        v.addProperty("label", role);
        v.addProperty("format", format);
        return v;
    }

    @Test
    void eachVariantIsComparedWithTheControlOfItsFormat(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("spec-format"));
        Path model = Files.createDirectories(root.resolve("model"));
        Files.writeString(model.resolve("components.json"), MODEL);
        Files.writeString(model.resolve("fixtures.json"), FIXTURES);
        JsonArray variants = new JsonArray();
        List<NbtMap> blocks = new ArrayList<>();
        List<NbtMap> items = new ArrayList<>();
        NbtMap empty = NbtMap.EMPTY;
        NbtMap filler = NbtMap.builder().putCompound("minecraft:filler", empty).build();
        // old_light: at 1.16.100 BDS upgrades it into light (the control of that format sends nothing of it); at 1.18.10 it is rejected
        variants.add(variant("paletter:b_c1", "block", "block:minecraft:old_light", "control", "1.16.100"));
        variants.add(variant("paletter:b_f1", "block", "block:minecraft:old_light", "format", "1.16.100"));
        variants.add(variant("paletter:b_c2", "block", "block:minecraft:old_light", "control", "1.18.10"));
        variants.add(variant("paletter:b_f2", "block", "block:minecraft:old_light", "format", "1.18.10"));
        blocks.add(block("paletter:b_c1", empty));
        blocks.add(block("paletter:b_f1", NbtMap.builder().putCompound("minecraft:light", NbtMap.builder().putInt("level", 1).build()).build()));
        blocks.add(block("paletter:b_c2", empty));
        // gone: a control, and nothing accepted at the format
        variants.add(variant("paletter:b_c3", "block", "block:minecraft:gone", "control", "1.16.100"));
        variants.add(variant("paletter:b_f3", "block", "block:minecraft:gone", "format", "1.16.100"));
        blocks.add(block("paletter:b_c3", empty));
        // seed: an old-style item at 1.10.0, its own key on the wire
        variants.add(variant("paletter:seed_c", "item", "item:minecraft:seed", "control", "1.10.0"));
        variants.add(variant("paletter:seed_f", "item", "item:minecraft:seed", "format", "1.10.0"));
        items.add(item("paletter:seed_c", false, filler));
        items.add(item("paletter:seed_f", false, NbtMap.builder().putCompound("minecraft:seed", NbtMap.builder().putString("crop_result", "x").build()).build()));
        // a seed that sends no components at all: an old-style item the capture's wire does not carry, accepted all the same
        variants.add(variant("paletter:seed_c2", "item", "item:minecraft:seed", "control", "1.16.0"));
        variants.add(variant("paletter:seed_f2", "item", "item:minecraft:seed", "format", "1.16.0"));
        items.add(item("paletter:seed_c2", false, NbtMap.EMPTY));
        items.add(item("paletter:seed_f2", false, NbtMap.builder().putCompound("minecraft:seed", NbtMap.builder().putString("crop_result", "x").build()).build()));
        // silent: accepted, sends what the control sends
        variants.add(variant("paletter:silent_c", "item", "item:minecraft:silent", "control", "1.20.10"));
        variants.add(variant("paletter:silent_f", "item", "item:minecraft:silent", "format", "1.20.10"));
        items.add(item("paletter:silent_c", true, filler));
        items.add(item("paletter:silent_f", true, filler));
        Files.writeString(dir.resolve("variants.json"), variants.toString());
        Files.writeString(dir.resolve("capture.json"), "{\"bdsVersion\": \"9.9.9.9\", \"protocol\": 1}");
        Files.writeString(dir.resolve("content-log.txt"),
            "ERROR [Blocks] block_definitions | behavior_packs/p | blocks/b_f2.json |  -> components -> minecraft:old_light: bad\n"
            + "ERROR [Blocks] block_definitions | behavior_packs/p | blocks/b_f3.json |  -> components -> minecraft:gone: bad\n");
        Nbt.writeGzip(dir.resolve("custom_block_properties.nbt"), NbtMap.builder().putList("blocks", NbtType.COMPOUND, blocks).build());
        Nbt.writeGzip(dir.resolve("custom_item_registry.nbt"), NbtMap.builder().putList("items", NbtType.COMPOUND, items).build());

        Map<String, Set<String>> wire = Map.of("block:minecraft:light", Set.of("minecraft:light"));
        Map<String, TreeMap<String, JsonObject>> result = FormatInference.read(dir, Model.read(model.resolve("components.json")), Fixtures.read(model), wire);

        assertEquals(Set.of("block:minecraft:old_light", "block:minecraft:gone", "item:minecraft:seed", "item:minecraft:silent"), result.keySet());
        assertEquals(List.of("1.16.100"), List.copyOf(result.get("block:minecraft:old_light").keySet()), "1.18.10 was rejected: not listed");
        JsonObject upgraded = result.get("block:minecraft:old_light").get("1.16.100");
        assertEquals("[\"minecraft:light\"]", upgraded.getAsJsonArray("wire").toString());
        assertEquals("[\"block:minecraft:light\"]", upgraded.getAsJsonArray("upgradedTo").toString(), "the wire is the key of another component");
        assertTrue(result.get("block:minecraft:gone").isEmpty(), "swept, accepted nowhere");
        JsonObject legacy = result.get("item:minecraft:seed").get("1.10.0");
        assertTrue(legacy.get("legacyEnvelope").getAsBoolean());
        assertEquals("[\"minecraft:seed\"]", legacy.getAsJsonArray("wire").toString(), "the filler of the control is not a change");
        assertNull(legacy.get("upgradedTo"));
        assertTrue(result.get("item:minecraft:seed").get("1.16.0").get("legacyEnvelope").getAsBoolean(), "an empty old-style control still counts");
        JsonObject silent = result.get("item:minecraft:silent").get("1.20.10");
        assertTrue(silent.getAsJsonArray("wire").isEmpty(), "accepted and sent nothing: server only");
        assertFalse(silent.has("legacyEnvelope"));
    }
}
