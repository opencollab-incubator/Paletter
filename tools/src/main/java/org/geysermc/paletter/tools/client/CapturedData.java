package org.geysermc.paletter.tools.client;

import org.geysermc.paletter.tools.data.Nbt;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativeGroup;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.creative.CreativeNode;
import org.geysermc.paletter.creative.CreativeStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A capture directory read back: raw item registry, raw block properties and the creative layout. */
public record CapturedData(Map<String, NbtMap> items, Map<String, NbtMap> blockProperties, CreativeLayout creative) {

    public static CapturedData read(Path directory) throws IOException {
        Map<String, NbtMap> items = new LinkedHashMap<>();
        for (NbtMap item : Nbt.readGzip(directory.resolve("raw_item_registry.nbt")).getList("items", NbtType.COMPOUND)) {
            items.put(item.getString("name"), item);
        }
        Map<String, NbtMap> blocks = new LinkedHashMap<>();
        for (NbtMap block : Nbt.readGzip(directory.resolve("raw_start_game_block_properties.nbt")).getList("blocks", NbtType.COMPOUND)) {
            blocks.put(block.getString("name"), block.getCompound("properties"));
        }
        JsonObject creative;
        try (var reader = Files.newBufferedReader(directory.resolve("creative_items.json"))) {
            creative = JsonParser.parseReader(reader).getAsJsonObject();
        }
        return new CapturedData(items, blocks, creativeLayout(creative));
    }

    /** Same conversion as the generator's importer: wire groups (unnamed = run of loose stacks) to the layout model. */
    static CreativeLayout creativeLayout(JsonObject root) throws IOException {
        JsonArray groups = root.getAsJsonArray("groups");
        List<List<CreativeStack>> stacksByGroup = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            stacksByGroup.add(new ArrayList<>());
        }
        for (JsonElement element : root.getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            stacksByGroup.get(item.get("groupId").getAsInt()).add(stack(item));
        }
        Map<CreativeCategory, List<CreativeNode>> categories = new EnumMap<>(CreativeCategory.class);
        for (int i = 0; i < groups.size(); i++) {
            JsonObject group = groups.get(i).getAsJsonObject();
            List<CreativeNode> nodes = categories.computeIfAbsent(
                CreativeCategory.fromBedrockName(group.get("category").getAsString()), c -> new ArrayList<>());
            String name = group.get("name").getAsString();
            if (name.isEmpty()) {
                nodes.addAll(stacksByGroup.get(i));
            } else {
                JsonObject icon = group.getAsJsonObject("icon");
                CreativeStack iconStack = icon == null || icon.get("id").getAsString().equals("minecraft:air") ? null : stack(icon);
                nodes.add(new CreativeGroup(name, iconStack, stacksByGroup.get(i)));
            }
        }
        return CreativeLayout.of(categories);
    }

    private static CreativeStack stack(JsonObject json) throws IOException {
        NbtMap block = json.has("block_state_b64") ? BlockStates.normalize(Nbt.readLeBase64(json.get("block_state_b64").getAsString())) : null;
        NbtMap tag = json.has("nbt_b64") ? Nbt.readLeBase64(json.get("nbt_b64").getAsString()) : null;
        return new CreativeStack(json.get("id").getAsString(), json.has("damage") ? json.get("damage").getAsInt() : 0,
            json.has("count") ? json.get("count").getAsInt() : 1, block, tag);
    }

}
