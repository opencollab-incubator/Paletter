package org.geysermc.paletter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.item.component.MaxStackSize;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vanilla item overrides against what BDS 1.26.63.1 sent for an add-on that overrides {@code minecraft:apple}
 * (data/bds/1.26.63.1/override). Experimental: the client's reaction to Paletter's overrides is unverified.
 */
class ItemOverrideTest {
    private static final Paletter PALETTER = Paletter.bundled();
    private static final int LATEST = PALETTER.versions().getLast().protocolVersion();

    @Test
    void replacingTheAppleSendsWhatBdsSentForTheOverriddenApple() throws IOException {
        Palette palette = Palettes.palette(LATEST, PaletteLayer.builder("override")
            .overrideItem("minecraft:apple", item -> item.replaceComponents(MaxStackSize.of(3))).build());
        RegisteredItem apple = palette.items().getDefinition("minecraft:apple");
        assertEquals(ItemVersion.DATA_DRIVEN, apple.getVersion());
        assertTrue(apple.isComponentBased());

        Path capture = Path.of(System.getProperty("paletter.root", "..")).resolve("data/bds/1.26.63.1/override/registry.json");
        JsonObject bds = JsonParser.parseString(Files.readString(capture)).getAsJsonObject().getAsJsonObject("minecraft:apple");
        assertEquals(canonical(bds.getAsJsonObject("data").get("components")), canonical(apple.getComponentData().getCompound("components")));
        // The id differs on purpose: BDS moves the overridden item to 853, Paletter keeps the vanilla id (unverified on a client)
        assertEquals(853, bds.get("id").getAsInt());
        assertNotEquals(853, apple.getRuntimeId());
    }

    @Test
    void modifyingKeepsTheVanillaComponents() {
        Palette vanilla = PALETTER.palette(LATEST);
        Palette palette = Palettes.palette(LATEST, PaletteLayer.builder("override")
            .overrideItem("minecraft:apple", item -> item.modifyComponents(MaxStackSize.of(3))).build());
        NbtMap before = vanilla.items().getDefinition("minecraft:apple").getComponentData().getCompound("components");
        NbtMap after = palette.items().getDefinition("minecraft:apple").getComponentData().getCompound("components");
        assertEquals(3, after.getCompound("item_properties").getInt("max_stack_size"));
        assertTrue(after.containsKey("minecraft:food"), "a merge keeps the vanilla food component");
        assertEquals(before.getCompound("item_properties").getInt("creative_category"), after.getCompound("item_properties").getInt("creative_category"));
    }

    /** Numbers compare by value (NBT byte/int/float against JSON), compounds by key. */
    private static Object canonical(Object value) {
        if (value instanceof JsonElement json) {
            if (json.isJsonObject()) {
                Map<String, Object> map = new TreeMap<>();
                json.getAsJsonObject().asMap().forEach((key, child) -> map.put(key, canonical(child)));
                return map;
            }
            if (json.isJsonArray()) {
                return json.getAsJsonArray().asList().stream().map(ItemOverrideTest::canonical).toList();
            }
            return json.getAsJsonPrimitive().isNumber() ? (Object) json.getAsDouble() : json.getAsString();
        }
        if (value instanceof NbtMap nbt) {
            Map<String, Object> map = new TreeMap<>();
            nbt.forEach((key, child) -> map.put(key, canonical(child)));
            return map;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ItemOverrideTest::canonical).toList();
        }
        return value instanceof Number number ? (Object) number.doubleValue() : value;
    }
}
