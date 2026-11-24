package dev.opencollab.paletter.tools.data;

import dev.opencollab.paletter.tools.BuildNumber;
import dev.opencollab.paletter.tools.bds.BdsDump;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.Json;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.internal.creative.RawGroup;
import dev.opencollab.paletter.internal.creative.RawLayout;
import dev.opencollab.paletter.internal.creative.RawNode;
import dev.opencollab.paletter.internal.creative.RawStack;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.data.VanillaItem;
import dev.opencollab.paletter.internal.data.VersionFallbacks;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
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
public final class Importer {
    private final Path output;

    public Importer(Path output) {
        this.output = output;
    }

    public void run(Path source, String name, int protocol, Path itemTagsFile, Path bdsDump) throws IOException {
        run(source, name, protocol, itemTagsFile, bdsDump, false);
    }

    /**
     * @param preview mark the version as a preview in {@code versions.txt}: consumers only see it when they opt in
     */
    public void run(Path source, String name, int protocol, Path itemTagsFile, Path bdsDump, boolean preview) throws IOException {
        DataVersion version = DataVersion.of(name, protocol);
        System.out.println("Importing " + version + " from " + source);

        Source read = readSource(source, name);
        List<NbtMap> blocks = read.blocks();
        List<VanillaItem> items = read.items();
        List<BlockPropertyData> dataDriven = read.dataDriven() == null ? List.of() : read.dataDriven();
        RawLayout creative = read.creative();

        Map<String, List<String>> tags = Map.of();
        Map<String, NbtMap> defaults = Map.of();
        if (bdsDump != null) {
            BdsDump dump = BdsDump.read(bdsDump);
            if (!BuildNumber.isReported(dump.version()) || !BuildNumber.parseReported(dump.version()).inLine(name)) {
                System.out.println("  warning: the dump is of BDS " + dump.version() + ", not of " + name);
            }
            tags = dump.itemTags();
            defaults = defaults(dump, blocks);
        }
        if (itemTagsFile != null) {
            tags = readItemTags(itemTagsFile);
        }

        Map<String, String> blockItems = bdsDump == null ? Map.of() : blockItems(BdsDump.read(bdsDump), blocks, items);
        VanillaData data = new VanillaData(version, blocks, items, dataDriven, creative, tags, VersionFallbacks.NONE, defaults, Map.of(), blockItems);
        // The version file has no components; properties.nbt gets them, next to those of the other versions
        BundledFiles.write(output, data);
        Integer codecProtocol = Files.isDirectory(source) ? codecProtocol(source) : null;
        if (codecProtocol != null) {
            System.out.println("  the capture was decoded with the " + codecProtocol + " codec: recorded in versions.txt");
        }
        updateIndex(version, preview, codecProtocol);
        System.out.printf("Wrote %s: %d block states, %d items, %d data driven blocks, %d creative stacks, %d item tags, %d block defaults, %d blocks with an item%n",
            version, blocks.size(), items.size(), dataDriven.size(), creative.stacks().count(), tags.size(),
            defaults.size(), blockItems.size());
    }

    /**
     * The block to item relation of the dump, reduced to blocks of the palette and items of the item list (the Script
     * API knows content the network does not send, e.g. experimental blocks the client never gets).
     */
    public static Map<String, String> blockItems(BdsDump dump, List<NbtMap> blocks, List<VanillaItem> items) {
        Set<String> blockNames = new TreeSet<>();
        blocks.forEach(state -> blockNames.add(BlockStates.name(state)));
        Set<String> itemNames = new TreeSet<>();
        items.forEach(item -> itemNames.add(item.identifier()));
        Map<String, String> result = new TreeMap<>();
        List<String> dropped = new ArrayList<>();
        dump.blockItems().forEach((block, item) -> {
            if (blockNames.contains(block) && itemNames.contains(item)) {
                result.put(block, item);
            } else {
                dropped.add(block + " -> " + item);
            }
        });
        if (!dropped.isEmpty()) {
            System.out.println("  warning: " + dropped.size() + " block to item entries are not in the palette or item list: " + dropped);
        }
        System.out.println("  " + result.size() + " blocks have an item");
        return result;
    }

    /**
     * Block defaults of the dump, reduced to network states. Only defaults that differ from a block's first palette
     * state are kept: the first state is the default of every block without an entry, so storing it is redundant.
     */
    public static Map<String, NbtMap> defaults(BdsDump dump, List<NbtMap> blocks) {
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
                  List<BlockPropertyData> dataDriven, RawLayout creative) {
    }

    /** Reads a source directory (CloudburstMC/Data or Geyser's versioned layout) without writing anything. */
    static Source readSource(Path source, String name) throws IOException {
        List<NbtMap> rawBlocks = readRawBlocks(file(source, "block_palette.nbt", name));
        List<NbtMap> blocks = rawBlocks.stream().map(BlockStates::normalize).toList();
        NbtMap components = Nbt.readGzip(file(source, "item_components.nbt", name));
        List<VanillaItem> items = readItems(file(source, "runtime_item_states.json", name), components);
        Path dataDrivenFile = file(source, "data_driven_blocks.nbt", name);
        List<BlockPropertyData> dataDriven = Files.exists(dataDrivenFile) ? readDataDrivenBlocks(dataDrivenFile) : null;
        RawLayout creative = readCreative(file(source, "creative_items.json", name));
        return new Source(rawBlocks, blocks, items, components, dataDriven, creative);
    }

    /**
     * The identifiers of a source directory, for the baseline of the constants ({@code codegen.constants.Baseline}).
     *
     * @return {@code blocks} and {@code items} to their identifiers, sorted and without duplicates
     */
    public static Map<String, List<String>> identifiers(Path source, String name) throws IOException {
        Source read = readSource(source, name);
        return Map.of(
            "blocks", read.blocks().stream().map(state -> state.getString("name")).distinct().sorted().toList(),
            "items", read.items().stream().map(VanillaItem::identifier).distinct().sorted().toList());
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
        return List.copyOf(Nbt.readGzip(file).getList("blocks", NbtType.COMPOUND));
    }

    private static List<VanillaItem> readItems(Path statesFile, NbtMap components) throws IOException {
        List<VanillaItem> items = new ArrayList<>();
        for (JsonElement element : Json.element(statesFile).getAsJsonArray()) {
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
        {
            NbtMap root = Nbt.readGzip(file);
            List<BlockPropertyData> blocks = new ArrayList<>();
            root.forEach((name, properties) -> blocks.add(new BlockPropertyData(name, (NbtMap) properties)));
            return blocks;
        }
    }

    /**
     * The dump stores the creative inventory in wire format: a flat list of groups (unnamed ones being runs of
     * loose stacks) and items pointing at group indexes. Converts it to the layout model.
     */
    private static RawLayout readCreative(Path file) throws IOException {
        JsonObject root = Json.object(file);
        JsonArray groups = root.getAsJsonArray("groups");
        List<List<RawStack>> stacksByGroup = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            stacksByGroup.add(new ArrayList<>());
        }
        for (JsonElement element : root.getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            int groupId = item.has("groupId") ? item.get("groupId").getAsInt() : 0;
            stacksByGroup.get(groupId).add(readStack(item));
        }

        Map<CreativeCategory, List<RawNode>> categories = new EnumMap<>(CreativeCategory.class);
        for (int i = 0; i < groups.size(); i++) {
            JsonObject group = groups.get(i).getAsJsonObject();
            CreativeCategory category = CreativeCategory.fromBedrockName(group.get("category").getAsString());
            List<RawNode> nodes = categories.computeIfAbsent(category, c -> new ArrayList<>());
            String name = group.get("name").getAsString();
            if (name.isEmpty()) {
                nodes.addAll(stacksByGroup.get(i));
            } else {
                JsonObject icon = group.getAsJsonObject("icon");
                RawStack iconStack = icon == null || icon.get("id").getAsString().equals("minecraft:air") ? null : readStack(icon);
                nodes.add(new RawGroup(name, iconStack, stacksByGroup.get(i)));
            }
        }
        return RawLayout.of(categories);
    }

    private static RawStack readStack(JsonObject json) throws IOException {
        NbtMap block = json.has("block_state_b64") ? BlockStates.normalize(Nbt.readLeBase64(json.get("block_state_b64").getAsString())) : null;
        NbtMap tag = json.has("nbt_b64") ? Nbt.readLeBase64(json.get("nbt_b64").getAsString()) : null;
        return new RawStack(json.get("id").getAsString(),
            json.has("damage") ? json.get("damage").getAsInt() : 0,
            json.has("count") ? json.get("count").getAsInt() : 1,
            block, tag);
    }


    public static Map<String, List<String>> readItemTags(Path file) throws IOException {
        Map<String, List<String>> tags = new TreeMap<>();
        Json.object(file).entrySet().forEach(entry -> {
            List<String> items = new ArrayList<>();
            entry.getValue().getAsJsonArray().forEach(item -> items.add(item.getAsString()));
            tags.put(entry.getKey(), items);
        });
        return tags;
    }

    /**
     * A line of {@code versions.txt}: {@code <name> <protocol> [preview] [codec=<protocol>]}.
     *
     * @param codecProtocol the protocol of the codec that decoded the capture the version was imported from, where that
     *                      was another protocol's; null for the version's own
     */
    public record IndexEntry(String name, int protocol, boolean preview, Integer codecProtocol) {
        public IndexEntry(String name, int protocol, boolean preview) {
            this(name, protocol, preview, null);
        }

        /** The line in {@code versions.txt}. */
        String line() {
            return name + " " + protocol + (preview ? " preview" : "") + (codecProtocol != null ? " codec=" + codecProtocol : "");
        }

        /**
         * @throws IllegalArgumentException for a line with fewer fields, a number that is none, or a field it does not know
         */
        static IndexEntry parse(String line) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length < 2) {
                throw new IllegalArgumentException("versions.txt: expected `<name> <protocol> [preview] [codec=<protocol>]`, got `" + line + "`");
            }
            boolean preview = false;
            Integer codec = null;
            for (int i = 2; i < parts.length; i++) {
                if (parts[i].equals("preview") && !preview) {
                    preview = true;
                } else if (parts[i].startsWith("codec=") && codec == null) {
                    codec = Integer.parseInt(parts[i].substring("codec=".length()));
                } else {
                    throw new IllegalArgumentException("versions.txt: unknown or repeated field `" + parts[i] + "` in `" + line + "`");
                }
            }
            return new IndexEntry(parts[0], Integer.parseInt(parts[1]), preview, codec);
        }
    }

    public static List<IndexEntry> readIndex(Path directory) throws IOException {
        Path index = directory.resolve("versions.txt");
        List<IndexEntry> entries = new ArrayList<>();
        if (Files.exists(index)) {
            for (String line : Files.readAllLines(index)) {
                line = line.strip();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    entries.add(IndexEntry.parse(line));
                }
            }
        }
        entries.sort(java.util.Comparator.comparingInt(IndexEntry::protocol));
        return entries;
    }

    private void updateIndex(DataVersion version, boolean preview, Integer codecProtocol) throws IOException {
        Map<String, IndexEntry> versions = new TreeMap<>();
        readIndex(output).forEach(entry -> versions.put(entry.name(), entry));
        versions.put(version.name(), new IndexEntry(version.name(), version.protocolVersion(), preview, codecProtocol));
        writeIndex(output, versions.values());
    }

    /**
     * The protocol of the codec that decoded a capture, where that was not the capture's own: {@code codecProtocol} of
     * the {@code capture.json} the capture client writes next to the captured files (left out for its own codec).
     *
     * @param source the captured files, as {@code import --source} takes them
     * @return the codec's protocol, or null if the capture was decoded with its own codec or the source has no
     *         {@code capture.json} (a palette dump of another origin)
     */
    public static Integer codecProtocol(Path source) throws IOException {
        Path capture = source.resolve("capture.json");
        if (!Files.isRegularFile(capture)) {
            return null;
        }
        JsonObject json = Json.object(capture);
        return json.has("codecProtocol") ? json.get("codecProtocol").getAsInt() : null;
    }

    /**
     * Removes a bundled version: its line in {@code versions.txt} and its data file.
     */
    public static void remove(Path directory, String name) throws IOException {
        writeIndex(directory, readIndex(directory).stream().filter(entry -> !entry.name().equals(name)).toList());
        Files.deleteIfExists(directory.resolve(name + ".nbt"));
    }

    private static void writeIndex(Path directory, java.util.Collection<IndexEntry> entries) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# <data version> <first protocol version> [preview] [codec=<protocol of the codec that decoded the capture, if another's>]."
            + " Generated by `import` / `update` (:tools:run); do not edit by hand.");
        entries.stream()
            .sorted(java.util.Comparator.comparingInt(IndexEntry::protocol))
            .forEach(entry -> lines.add(entry.line()));
        Files.writeString(directory.resolve("versions.txt"), String.join("\n", lines) + "\n");
    }
}
