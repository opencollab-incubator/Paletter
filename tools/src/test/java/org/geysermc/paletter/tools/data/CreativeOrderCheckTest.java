package org.geysermc.paletter.tools.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemGroup;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.PaletteLayer;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativeOrdering;
import org.geysermc.paletter.data.DataVersion;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paletter's default creative ordering against what BDS 1.26.63.1 sent for three add-ons (see docs/wire-notes.md):
 * the capture add-on (66 blocks, 84 items, with experiments), the creative order probe (every kind of menu category
 * placement) and the large probe (700 blocks, so BDS's block map has more than 512 buckets).
 */
class CreativeOrderCheckTest {
    private static final Path CAPTURES = Path.of("../data/bds/1.26.63.1");
    private static final Path DATA = Path.of("../paletter/src/main/resources/paletter");

    @ParameterizedTest
    @CsvSource({"addon, 149", "creative-probe, 30", "creative-large, 700"})
    void bdsOrderingMatchesTheCapture(String capture, int customStacks) throws Exception {
        Path directory = CAPTURES.resolve(capture);
        CreativeOrderCheck.Result result = CreativeOrderCheck.check(2225, DATA,
            directory.resolve("creative_items.json.gz"), directory.resolve("custom_item_registry.nbt"),
            directory.resolve("custom_block_properties.nbt"));
        assertTrue(result.matches(), String.join("\n", result.differences()));
        assertTrue(result.customStacks() >= customStacks, "only " + result.customStacks() + " custom stacks compared");
    }

    @Test
    void appendOrderingDoesNot() throws Exception {
        Path directory = CAPTURES.resolve("creative-probe");
        CreativeOrderCheck.Result result = CreativeOrderCheck.check(2225, DATA,
            directory.resolve("creative_items.json.gz"), directory.resolve("custom_item_registry.nbt"),
            directory.resolve("custom_block_properties.nbt"), CreativeOrdering.APPEND);
        assertFalse(result.matches());
    }

    @Test
    void previewProtocolIsComparedWithThePreviewLayout(@TempDir Path directory) throws Exception {
        Paletter previews = CreativeOrderCheck.withPreviews(DATA, CreativeOrdering.BDS, PaletteLayer.builder("none").build());
        DataVersion preview = previews.versions().getLast();
        assertTrue(preview.protocolVersion() > 2225, "no preview bundled");
        Path creative = directory.resolve("creative_items.json");
        Files.writeString(creative, creativeItems(previews.palette(preview.protocolVersion())));
        Path empty = directory.resolve("empty.nbt");
        Nbt.writeGzip(empty, NbtMap.builder().putList("items", NbtType.COMPOUND).putList("blocks", NbtType.COMPOUND).build());
        CreativeOrderCheck.Result result = CreativeOrderCheck.check(preview.protocolVersion(), DATA, creative, empty, empty);
        assertTrue(result.matches(), String.join("\n", result.differences()));
        assertFalse(CreativeOrderCheck.check(2225, DATA, creative, empty, empty).matches(), "the 26.60 layout should differ");
    }

    @Test
    void unbundledProtocolIsSkipped() throws Exception {
        Path directory = CAPTURES.resolve("addon");
        CreativeOrderCheck.Result result = CreativeOrderCheck.check(2226, DATA,
            directory.resolve("creative_items.json.gz"), directory.resolve("custom_item_registry.nbt"),
            directory.resolve("custom_block_properties.nbt"));
        assertNotNull(result.skipped());
        assertTrue(result.differences().isEmpty());
    }

    /** What BDS would send for a world with no add-ons: the palette's creative inventory as {@code creative_items.json}. */
    private static String creativeItems(Palette palette) {
        JsonArray groups = new JsonArray();
        for (CreativeItemGroup group : palette.creative().groups()) {
            JsonObject icon = new JsonObject();
            icon.addProperty("id", group.getIcon() == null || group.getIcon().getDefinition() == null
                || group.getIcon().getDefinition().getRuntimeId() == 0 ? "minecraft:air" : group.getIcon().getDefinition().getIdentifier());
            JsonObject json = new JsonObject();
            json.addProperty("category", java.util.Arrays.stream(CreativeCategory.values())
                .filter(category -> category.protocol() == group.getCategory()).findFirst().orElseThrow().bedrockName());
            json.addProperty("name", group.getName());
            json.add("icon", icon);
            groups.add(json);
        }
        JsonArray items = new JsonArray();
        for (CreativeItemData item : palette.creative().items()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", item.getItem().getDefinition().getIdentifier());
            if (item.getItem().getDamage() != 0) {
                json.addProperty("damage", item.getItem().getDamage());
            }
            json.addProperty("groupId", item.getGroupId());
            items.add(json);
        }
        JsonObject root = new JsonObject();
        root.add("groups", groups);
        root.add("items", items);
        return root.toString();
    }
}
