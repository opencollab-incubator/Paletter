package org.geysermc.paletter.generator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.block.BlockFallback;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativeGroup;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.creative.CreativeNode;
import org.geysermc.paletter.creative.CreativeStack;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataCodec;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.data.VanillaItem;
import org.geysermc.paletter.data.VersionFallbacks;
import org.geysermc.paletter.item.ItemFallback;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Imports a palette dump. Expected files in the source directory, as produced by dumping a BDS client session
 * (the layout of CloudburstMC/Data):
 * <ul>
 *     <li>{@code block_palette.nbt}: gzipped NBT, {@code {blocks: [{name, states, ...}]}};</li>
 *     <li>{@code runtime_item_states.json}: {@code [{name, id, version, componentBased}]};</li>
 *     <li>{@code item_components.nbt}: gzipped NBT, item identifier to component data;</li>
 *     <li>{@code creative_items.json}: {@code {groups: [{name, category, icon}], items: [{id, groupId, damage?, block_state_b64?, nbt_b64?}]}};</li>
 *     <li>{@code data_driven_blocks.nbt} (optional): gzipped NBT, block identifier to block property data.</li>
 * </ul>
 * Item tags and default block states come from the BDS addon dump ({@code --bds-dump}), or item tags from a plain
 * {@code --item-tags} JSON file.
 */
final class Importer {
    private final Path output;
    private final Path fallbacksDirectory;

    Importer(Path output, Path fallbacksDirectory) {
        this.output = output;
        this.fallbacksDirectory = fallbacksDirectory;
    }

    void run(Path source, String name, int protocol, Path itemTagsFile, Path bdsDump) throws IOException {
        DataVersion version = new DataVersion(name, protocol);
        System.out.println("Importing " + version + " from " + source);

        List<NbtMap> blocks = readBlocks(source.resolve("block_palette.nbt"));
        List<VanillaItem> items = readItems(source.resolve("runtime_item_states.json"), source.resolve("item_components.nbt"));
        List<BlockPropertyData> dataDriven = readDataDrivenBlocks(source.resolve("data_driven_blocks.nbt"));
        CreativeLayout creative = readCreative(source.resolve("creative_items.json"));

        Map<String, List<String>> tags = Map.of();
        Map<String, NbtMap> defaults = Map.of();
        if (bdsDump != null) {
            BdsDump dump = BdsDump.read(bdsDump);
            tags = dump.itemTags();
            defaults = dump.blockDefaults();
        }
        if (itemTagsFile != null) {
            tags = readItemTags(itemTagsFile);
        }
        VersionFallbacks fallbacks = readFallbacks(fallbacksDirectory.resolve(name + ".json"));

        VanillaData data = new VanillaData(version, blocks, items, dataDriven, creative, tags, fallbacks, defaults);
        Files.createDirectories(output);
        try (OutputStream stream = Files.newOutputStream(output.resolve(name + ".nbt"))) {
            PaletteDataCodec.write(data, stream);
        }
        updateIndex(version);
        System.out.printf("Wrote %s: %d block states, %d items, %d data driven blocks, %d creative stacks, %d item tags, %d block fallbacks, %d item fallbacks, %d block defaults%n",
            version, blocks.size(), items.size(), dataDriven.size(), creative.stacks().count(), tags.size(),
            fallbacks.blocks().size(), fallbacks.items().size(), defaults.size());
    }

    private static List<NbtMap> readBlocks(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file); NBTInputStream input = NbtUtils.createGZIPReader(stream, true, true)) {
            NbtMap root = (NbtMap) input.readTag();
            List<NbtMap> blocks = new ArrayList<>();
            for (NbtMap block : root.getList("blocks", NbtType.COMPOUND)) {
                blocks.add(BlockStates.normalize(block));
            }
            return blocks;
        }
    }

    private static List<VanillaItem> readItems(Path statesFile, Path componentsFile) throws IOException {
        NbtMap components;
        try (InputStream stream = Files.newInputStream(componentsFile); NBTInputStream input = NbtUtils.createGZIPReader(stream, true, true)) {
            components = (NbtMap) input.readTag();
        }
        List<VanillaItem> items = new ArrayList<>();
        for (JsonElement element : readJson(statesFile).getAsJsonArray()) {
            JsonObject item = element.getAsJsonObject();
            String identifier = item.get("name").getAsString();
            boolean componentBased = item.get("componentBased").getAsBoolean();
            NbtMap componentData = components.getCompound(identifier, null);
            if (componentData == null && componentBased) {
                System.out.println("  warning: component based item " + identifier + " has no components in the dump; using empty components");
                componentData = NbtMap.EMPTY;
            }
            items.add(new VanillaItem(identifier, item.get("id").getAsInt(), item.get("version").getAsInt(), componentBased, componentData));
        }
        return items;
    }

    private static List<BlockPropertyData> readDataDrivenBlocks(Path file) throws IOException {
        if (Files.notExists(file)) {
            return List.of();
        }
        try (InputStream stream = Files.newInputStream(file); NBTInputStream input = NbtUtils.createGZIPReader(stream)) {
            NbtMap root = (NbtMap) input.readTag();
            List<BlockPropertyData> blocks = new ArrayList<>();
            root.forEach((name, properties) -> blocks.add(new BlockPropertyData(name, (NbtMap) properties)));
            return blocks;
        }
    }

    /**
     * The dump stores the creative inventory in wire format: a flat list of groups (unnamed ones being runs of
     * loose stacks) and items pointing at group indexes. Converts it to the layout model.
     */
    private static CreativeLayout readCreative(Path file) throws IOException {
        JsonObject root = readJson(file).getAsJsonObject();
        JsonArray groups = root.getAsJsonArray("groups");
        List<List<CreativeStack>> stacksByGroup = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            stacksByGroup.add(new ArrayList<>());
        }
        for (JsonElement element : root.getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            int groupId = item.has("groupId") ? item.get("groupId").getAsInt() : 0;
            stacksByGroup.get(groupId).add(readStack(item));
        }

        Map<CreativeCategory, List<CreativeNode>> categories = new EnumMap<>(CreativeCategory.class);
        for (int i = 0; i < groups.size(); i++) {
            JsonObject group = groups.get(i).getAsJsonObject();
            CreativeCategory category = CreativeCategory.fromBedrockName(group.get("category").getAsString());
            List<CreativeNode> nodes = categories.computeIfAbsent(category, c -> new ArrayList<>());
            String name = group.get("name").getAsString();
            if (name.isEmpty()) {
                nodes.addAll(stacksByGroup.get(i));
            } else {
                JsonObject icon = group.getAsJsonObject("icon");
                CreativeStack iconStack = icon == null || icon.get("id").getAsString().equals("minecraft:air") ? null : readStack(icon);
                nodes.add(new CreativeGroup(name, iconStack, stacksByGroup.get(i)));
            }
        }
        return CreativeLayout.of(categories);
    }

    private static CreativeStack readStack(JsonObject json) throws IOException {
        NbtMap block = json.has("block_state_b64") ? BlockStates.normalize(readLittleEndian(json.get("block_state_b64").getAsString())) : null;
        NbtMap tag = json.has("nbt_b64") ? readLittleEndian(json.get("nbt_b64").getAsString()) : null;
        return new CreativeStack(json.get("id").getAsString(),
            json.has("damage") ? json.get("damage").getAsInt() : 0,
            json.has("count") ? json.get("count").getAsInt() : 1,
            block, tag);
    }

    private static NbtMap readLittleEndian(String base64) throws IOException {
        try (NBTInputStream input = NbtUtils.createReaderLE(new ByteArrayInputStream(Base64.getDecoder().decode(base64)))) {
            return (NbtMap) input.readTag();
        }
    }

    private static Map<String, List<String>> readItemTags(Path file) throws IOException {
        Map<String, List<String>> tags = new TreeMap<>();
        readJson(file).getAsJsonObject().entrySet().forEach(entry -> {
            List<String> items = new ArrayList<>();
            entry.getValue().getAsJsonArray().forEach(item -> items.add(item.getAsString()));
            tags.put(entry.getKey(), items);
        });
        return tags;
    }

    /**
     * Fallback files map identifiers of the newest version to replacements in this one:
     * <pre>{@code
     * {
     *   "blocks": {
     *     "minecraft:poplar_log": "minecraft:pale_oak_log",
     *     "minecraft:straw_bed": {"target": "minecraft:yellow_carpet", "clear_states": true},
     *     "minecraft:disc_press": null
     *   },
     *   "items": {"minecraft:poplar_boat": {"target": "minecraft:pale_oak_boat", "force": true}}
     * }
     * }</pre>
     * {@code null} acknowledges that something has no fallback on purpose. {@code force} replaces things that do
     * exist in the version, but are only usable with an experiment enabled.
     */
    static VersionFallbacks readFallbacks(Path file) throws IOException {
        if (Files.notExists(file)) {
            return VersionFallbacks.NONE;
        }
        JsonObject root = readJson(file).getAsJsonObject();
        Set<String> unmapped = new LinkedHashSet<>();
        Map<String, BlockFallback> blocks = new LinkedHashMap<>();
        if (root.has("blocks")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("blocks").entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonNull()) {
                    unmapped.add(entry.getKey());
                } else if (value.isJsonPrimitive()) {
                    blocks.put(entry.getKey(), BlockFallback.of(value.getAsString()));
                } else {
                    JsonObject object = value.getAsJsonObject();
                    blocks.put(entry.getKey(), new BlockFallback(object.get("target").getAsString(),
                        flag(object, "clear_states"), flag(object, "force")));
                }
            }
        }
        Map<String, ItemFallback> items = new LinkedHashMap<>();
        if (root.has("items")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("items").entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonNull()) {
                    unmapped.add(entry.getKey());
                } else if (value.isJsonPrimitive()) {
                    items.put(entry.getKey(), ItemFallback.of(value.getAsString()));
                } else {
                    JsonObject object = value.getAsJsonObject();
                    items.put(entry.getKey(), new ItemFallback(object.get("target").getAsString(), flag(object, "force")));
                }
            }
        }
        return new VersionFallbacks(blocks, items, unmapped);
    }

    private static boolean flag(JsonObject object, String name) {
        return object.has(name) && object.get(name).getAsBoolean();
    }

    private void updateIndex(DataVersion version) throws IOException {
        Path index = output.resolve("versions.txt");
        Map<String, Integer> versions = new TreeMap<>();
        if (Files.exists(index)) {
            for (String line : Files.readAllLines(index)) {
                line = line.strip();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    String[] parts = line.split("\\s+");
                    versions.put(parts[0], Integer.parseInt(parts[1]));
                }
            }
        }
        versions.put(version.name(), version.protocolVersion());
        List<String> lines = new ArrayList<>();
        lines.add("# <data version> <first protocol version> - written by the generator");
        versions.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .forEach(entry -> lines.add(entry.getKey() + " " + entry.getValue()));
        Files.write(index, lines);
    }

    static JsonElement readJson(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader);
        }
    }
}
