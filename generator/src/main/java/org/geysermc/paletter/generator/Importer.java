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
import java.util.TreeSet;

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
 * Files may also carry the version like Geyser's resources ({@code block_palette.26_60.nbt}).
 * Item tags and default block states come from the BDS addon dump ({@code --bds-dump}), or item tags from a plain
 * {@code --item-tags} JSON file (which wins over the dump).
 */
final class Importer {
    private final Path output;
    private final Path fallbacksDirectory;

    Importer(Path output, Path fallbacksDirectory) {
        this.output = output;
        this.fallbacksDirectory = fallbacksDirectory;
    }

    void run(Path source, String name, int protocol, Path itemTagsFile, Path bdsDump) throws IOException {
        run(source, name, protocol, itemTagsFile, bdsDump, false);
    }

    /**
     * @param preview mark the version as a preview in {@code versions.txt}: consumers only see it when they opt in
     */
    void run(Path source, String name, int protocol, Path itemTagsFile, Path bdsDump, boolean preview) throws IOException {
        DataVersion version = new DataVersion(name, protocol);
        System.out.println("Importing " + version + " from " + source);

        Source read = readSource(source, name);
        List<NbtMap> blocks = read.blocks();
        List<VanillaItem> items = read.items();
        List<BlockPropertyData> dataDriven = read.dataDriven() == null ? List.of() : read.dataDriven();
        CreativeLayout creative = read.creative();

        Map<String, List<String>> tags = Map.of();
        Map<String, NbtMap> defaults = Map.of();
        if (bdsDump != null) {
            BdsDump dump = BdsDump.read(bdsDump);
            if (!dump.version().startsWith("1." + name.substring(0, name.length() - 1))) {
                System.out.println("  warning: the dump is of BDS " + dump.version() + ", not of " + name);
            }
            tags = dump.itemTags();
            defaults = defaults(dump, blocks);
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
        updateIndex(version, preview);
        System.out.printf("Wrote %s: %d block states, %d items, %d data driven blocks, %d creative stacks, %d item tags, %d block fallbacks, %d item fallbacks, %d block defaults%n",
            version, blocks.size(), items.size(), dataDriven.size(), creative.stacks().count(), tags.size(),
            fallbacks.blocks().size(), fallbacks.items().size(), defaults.size());
    }

    /**
     * Block defaults of the dump, reduced to network states. Only defaults that differ from a block's first palette
     * state are kept: the first state is the default of every block without an entry, so storing it is redundant.
     */
    static Map<String, NbtMap> defaults(BdsDump dump, List<NbtMap> blocks) {
        List<String> rejected = new ArrayList<>();
        Map<String, NbtMap> defaults = dump.projectDefaults(blocks, rejected);
        rejected.forEach(problem -> System.out.println("  warning: " + problem));
        Map<String, NbtMap> first = new TreeMap<>();
        blocks.forEach(state -> first.putIfAbsent(BlockStates.name(state), state));
        Map<String, NbtMap> differing = new TreeMap<>();
        defaults.forEach((block, state) -> {
            if (!state.equals(first.get(block))) {
                differing.put(block, state);
            }
        });
        Set<String> missing = new TreeSet<>(first.keySet());
        missing.removeAll(dump.blockDefaults().keySet());
        if (!missing.isEmpty()) {
            System.out.println("  warning: no BDS default for " + missing.size() + " blocks: " + missing);
        }
        System.out.println("  " + defaults.size() + " block defaults, " + differing.size() + " of them not the first state");
        return differing;
    }

    /**
     * A source directory read into memory, as {@link #run} imports it.
     *
     * @param rawBlocks the palette entries as stored, with keys the bundled format drops ({@code version},
     *                  {@code network_id}, {@code name_hash}, ...)
     * @param blocks the palette entries reduced to {@code {name, states}}, in file order
     * @param items the items, sorted by identifier (the order of the file is not kept)
     * @param components the item component file: identifier to component data, including entries without an item
     * @param dataDriven the data driven blocks, or null if the source has no such file
     */
    record Source(List<NbtMap> rawBlocks, List<NbtMap> blocks, List<VanillaItem> items, NbtMap components,
                  List<BlockPropertyData> dataDriven, CreativeLayout creative) {
    }

    /** Reads a source directory (CloudburstMC/Data or Geyser's versioned layout) without writing anything. */
    static Source readSource(Path source, String name) throws IOException {
        List<NbtMap> rawBlocks = readRawBlocks(file(source, "block_palette.nbt", name));
        List<NbtMap> blocks = rawBlocks.stream().map(BlockStates::normalize).toList();
        NbtMap components = readGzip(file(source, "item_components.nbt", name));
        List<VanillaItem> items = readItems(file(source, "runtime_item_states.json", name), components);
        Path dataDrivenFile = file(source, "data_driven_blocks.nbt", name);
        List<BlockPropertyData> dataDriven = Files.exists(dataDrivenFile) ? readDataDrivenBlocks(dataDrivenFile) : null;
        CreativeLayout creative = readCreative(file(source, "creative_items.json", name));
        return new Source(rawBlocks, blocks, items, components, dataDriven, creative);
    }

    /**
     * Files are either named plainly (CloudburstMC/Data) or with the version, as Geyser ships them
     * ({@code block_palette.26_60.nbt}).
     */
    static Path file(Path source, String file, String version) {
        Path plain = source.resolve(file);
        if (Files.exists(plain)) {
            return plain;
        }
        int dot = file.lastIndexOf('.');
        return source.resolve(file.substring(0, dot) + "." + version.replace('.', '_') + file.substring(dot));
    }

    private static List<NbtMap> readRawBlocks(Path file) throws IOException {
        return List.copyOf(readGzip(file).getList("blocks", NbtType.COMPOUND));
    }

    private static NbtMap readGzip(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file); NBTInputStream input = NbtUtils.createGZIPReader(stream, true, true)) {
            return (NbtMap) input.readTag();
        }
    }

    private static List<VanillaItem> readItems(Path statesFile, NbtMap components) throws IOException {
        List<VanillaItem> items = new ArrayList<>();
        for (JsonElement element : readJson(statesFile).getAsJsonArray()) {
            JsonObject item = element.getAsJsonObject();
            String identifier = item.get("name").getAsString();
            boolean componentBased = item.get("componentBased").getAsBoolean();
            NbtMap componentData = components.getCompound(identifier, null);
            if (componentData == null) {
                // BDS sends a (mostly empty) compound for every item; CloudburstMC/Data and Geyser keep the empty ones,
                // the capture writer drops them. Empty either way, so every source imports to the same file
                if (componentBased) {
                    System.out.println("  warning: component based item " + identifier + " has no components in the dump; using empty components");
                }
                componentData = NbtMap.EMPTY;
            }
            items.add(new VanillaItem(identifier, item.get("id").getAsInt(), item.get("version").getAsInt(), componentBased, componentData));
        }
        // The order carries no meaning (items are keyed by runtime id), but a capture lists them in BDS's network order
        // and CloudburstMC/Data and Geyser by identifier: sort, so every source imports to the same file
        items.sort(java.util.Comparator.comparing(VanillaItem::identifier));
        return items;
    }

    private static List<BlockPropertyData> readDataDrivenBlocks(Path file) throws IOException {
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

    static Map<String, List<String>> readItemTags(Path file) throws IOException {
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

    /**
     * A line of {@code versions.txt}: {@code <name> <protocol> [preview]}.
     */
    record IndexEntry(String name, int protocol, boolean preview) {
    }

    static List<IndexEntry> readIndex(Path directory) throws IOException {
        Path index = directory.resolve("versions.txt");
        List<IndexEntry> entries = new ArrayList<>();
        if (Files.exists(index)) {
            for (String line : Files.readAllLines(index)) {
                line = line.strip();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    String[] parts = line.split("\\s+");
                    entries.add(new IndexEntry(parts[0], Integer.parseInt(parts[1]), parts.length > 2 && parts[2].equals("preview")));
                }
            }
        }
        entries.sort(java.util.Comparator.comparingInt(IndexEntry::protocol));
        return entries;
    }

    private void updateIndex(DataVersion version, boolean preview) throws IOException {
        Map<String, IndexEntry> versions = new TreeMap<>();
        readIndex(output).forEach(entry -> versions.put(entry.name(), entry));
        versions.put(version.name(), new IndexEntry(version.name(), version.protocolVersion(), preview));
        List<String> lines = new ArrayList<>();
        lines.add("# <data version> <first protocol version> - written by the generator");
        versions.values().stream()
            .sorted(java.util.Comparator.comparingInt(IndexEntry::protocol))
            .forEach(entry -> lines.add(entry.name() + " " + entry.protocol() + (entry.preview() ? " preview" : "")));
        Files.writeString(output.resolve("versions.txt"), String.join("\n", lines) + "\n");
    }

    static JsonElement readJson(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader);
        }
    }
}
