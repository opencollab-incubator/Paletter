package org.geysermc.paletter.capture;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.ExperimentData;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemGroup;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.geysermc.paletter.data.VanillaData;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes a capture in the layout of CloudburstMC/Data that the generator's importer reads, plus the raw wire data.
 * <p>
 * The wire does not carry the vanilla block palette: the client has it built in. {@code block_palette.nbt} is
 * therefore the reference palette (a bundled Paletter version) plus the states of every block the server sent block
 * properties for that the reference lacks, and every block runtime id the server did send (creative items) is checked
 * against the hashes of that palette, which is only possible because BDS sends hashed ids.
 */
final class CaptureWriter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final CaptureClient.Result result;
    private final VanillaData reference;
    private final String bdsVersion;
    private final Map<Integer, NbtMap> statesByHash = new LinkedHashMap<>();
    private final List<NbtMap> palette = new ArrayList<>();
    private final Set<Integer> unresolvedHashes = new LinkedHashSet<>();
    private int resolvedHashes;

    CaptureWriter(CaptureClient.Result result, VanillaData reference, String bdsVersion) {
        this.result = result;
        this.reference = reference;
        this.bdsVersion = bdsVersion;
        Set<String> referenceBlocks = new LinkedHashSet<>();
        for (NbtMap state : reference.blockStates()) {
            palette.add(state);
            referenceBlocks.add(state.getString("name"));
        }
        for (BlockPropertyData block : result.startGame.getBlockProperties()) {
            if (!referenceBlocks.contains(block.getName())) {
                palette.addAll(BlockHashes.customStates(block.getName(), block.getProperties()));
            }
        }
        for (NbtMap state : palette) {
            NbtMap previous = statesByHash.put(BlockHashes.hash(state), state);
            if (previous != null) {
                throw new IllegalStateException("Block hash collision: " + previous + " and " + state);
            }
        }
    }

    void write(Path directory, Path customDirectory) throws IOException {
        Files.createDirectories(directory);
        StartGamePacket startGame = result.startGame;

        // Importer layout
        writeGzip(directory.resolve("block_palette.nbt"), NbtMap.builder()
            .putList("blocks", NbtType.COMPOUND, palette).build());
        JsonArray itemStates = new JsonArray();
        NbtMapBuilder components = NbtMap.builder();
        for (ItemDefinition item : result.items) {
            JsonObject json = new JsonObject();
            json.addProperty("name", item.getIdentifier());
            json.addProperty("id", item.getRuntimeId());
            json.addProperty("version", item.getVersion().ordinal());
            json.addProperty("componentBased", item.isComponentBased());
            itemStates.add(json);
            if (item.getComponentData() != null && !item.getComponentData().isEmpty()) {
                components.putCompound(item.getIdentifier(), item.getComponentData());
            }
        }
        Files.writeString(directory.resolve("runtime_item_states.json"), GSON.toJson(itemStates));
        writeGzip(directory.resolve("item_components.nbt"), components.build());
        Files.writeString(directory.resolve("creative_items.json"), GSON.toJson(creative()));
        NbtMapBuilder dataDriven = NbtMap.builder();
        for (BlockPropertyData block : startGame.getBlockProperties()) {
            dataDriven.putCompound(block.getName(), block.getProperties());
        }
        writeGzip(directory.resolve("data_driven_blocks.nbt"), dataDriven.build());

        // Raw wire data, in wire order and unmodified
        writeGzip(directory.resolve("raw_start_game_block_properties.nbt"), blockPropertiesList(startGame.getBlockProperties()));
        writeGzip(directory.resolve("raw_item_registry.nbt"), itemRegistryList(result.items));
        if (result.entityIdentifiers != null) {
            writeGzip(directory.resolve("raw_entity_identifiers.nbt"), result.entityIdentifiers.getIdentifiers());
        }
        Files.writeString(directory.resolve("capture.json"), GSON.toJson(summary()));

        if (customDirectory != null) {
            writeCustom(customDirectory);
        }
    }

    /** Only the content the server added (anything not in the minecraft namespace), as NBT and as readable SNBT. */
    private void writeCustom(Path directory) throws IOException {
        Files.createDirectories(directory);
        List<BlockPropertyData> blocks = result.startGame.getBlockProperties().stream()
            .filter(block -> !block.getName().startsWith("minecraft:")).toList();
        List<ItemDefinition> items = result.items.stream()
            .filter(item -> !item.getIdentifier().startsWith("minecraft:")).toList();
        NbtMap blockNbt = blockPropertiesList(blocks);
        NbtMap itemNbt = itemRegistryList(items);
        writeGzip(directory.resolve("custom_block_properties.nbt"), blockNbt);
        writeGzip(directory.resolve("custom_item_registry.nbt"), itemNbt);
        StringBuilder blockText = new StringBuilder();
        for (BlockPropertyData block : blocks) {
            blockText.append("# ").append(block.getName()).append('\n').append(Snbt.write(block.getProperties())).append("\n\n");
        }
        Files.writeString(directory.resolve("custom_block_properties.snbt"), blockText);
        StringBuilder itemText = new StringBuilder();
        for (ItemDefinition item : items) {
            itemText.append("# ").append(item.getIdentifier()).append(" id=").append(item.getRuntimeId())
                .append(" version=").append(item.getVersion()).append(" componentBased=").append(item.isComponentBased())
                .append('\n').append(Snbt.write(item.getComponentData() == null ? NbtMap.EMPTY : item.getComponentData()))
                .append("\n\n");
        }
        Files.writeString(directory.resolve("custom_item_registry.snbt"), itemText);

        JsonObject creative = creative();
        JsonArray customStacks = new JsonArray();
        creative.getAsJsonArray("items").forEach(element -> {
            if (!element.getAsJsonObject().get("id").getAsString().startsWith("minecraft:")) {
                customStacks.add(element);
            }
        });
        JsonObject customCreative = new JsonObject();
        JsonArray groups = new JsonArray();
        Set<Integer> usedGroups = new LinkedHashSet<>();
        customStacks.forEach(element -> usedGroups.add(element.getAsJsonObject().get("groupId").getAsInt()));
        for (int group : usedGroups) {
            JsonObject entry = creative.getAsJsonArray("groups").get(group).getAsJsonObject().deepCopy();
            entry.addProperty("index", group);
            groups.add(entry);
        }
        customCreative.add("groups", groups);
        customCreative.add("items", customStacks);
        Files.writeString(directory.resolve("custom_creative_items.json"), GSON.toJson(customCreative));

        // Per block: how many states the client derives (properties and traits) and the default (first) one with its
        // network hash; every hash the server sent in the creative inventory is checked in capture.json
        JsonObject customStates = new JsonObject();
        for (BlockPropertyData block : blocks) {
            List<NbtMap> states = BlockHashes.customStates(block.getName(), block.getProperties());
            JsonObject entry = new JsonObject();
            entry.addProperty("states", states.size());
            entry.addProperty("defaultHash", Integer.toUnsignedString(BlockHashes.hash(states.getFirst())));
            entry.addProperty("default", Snbt.write(states.getFirst().getCompound("states")).replaceAll("\\s+", " "));
            customStates.add(block.getName(), entry);
        }
        Files.writeString(directory.resolve("custom_block_states.json"), GSON.toJson(customStates));
    }

    private JsonObject creative() throws IOException {
        JsonObject root = new JsonObject();
        JsonArray groups = new JsonArray();
        for (CreativeItemGroup group : result.creative.getGroups()) {
            JsonObject json = new JsonObject();
            json.addProperty("name", group.getName());
            json.addProperty("category", group.getCategory().name().toLowerCase(Locale.ROOT));
            json.add("icon", stack(group.getIcon()));
            groups.add(json);
        }
        root.add("groups", groups);
        JsonArray items = new JsonArray();
        for (CreativeItemData entry : result.creative.getContents()) {
            JsonObject json = stack(entry.getItem());
            json.addProperty("groupId", entry.getGroupId());
            items.add(json);
        }
        root.add("items", items);
        return root;
    }

    private JsonObject stack(ItemData item) throws IOException {
        JsonObject json = new JsonObject();
        if (item == null || item.getDefinition() == null || item.getDefinition().getRuntimeId() == 0) {
            json.addProperty("id", "minecraft:air");
            return json;
        }
        json.addProperty("id", item.getDefinition().getIdentifier());
        if (item.getDamage() != 0) {
            json.addProperty("damage", item.getDamage());
        }
        if (item.getCount() != 1) {
            json.addProperty("count", item.getCount());
        }
        BlockDefinition block = item.getBlockDefinition();
        if (block != null && block.getRuntimeId() != 0) {
            NbtMap state = statesByHash.get(block.getRuntimeId());
            json.addProperty("block_runtime_id", Integer.toUnsignedString(block.getRuntimeId()));
            if (state != null) {
                resolvedHashes++;
                json.addProperty("block_state_b64", base64(state));
            } else {
                unresolvedHashes.add(block.getRuntimeId());
            }
        }
        if (item.getTag() != null) {
            json.addProperty("nbt_b64", base64(item.getTag()));
        }
        return json;
    }

    private JsonObject summary() {
        StartGamePacket startGame = result.startGame;
        JsonObject json = new JsonObject();
        json.addProperty("bdsVersion", bdsVersion);
        json.addProperty("serverEngine", startGame.getServerEngine());
        json.addProperty("vanillaVersion", startGame.getVanillaVersion());
        json.addProperty("blockNetworkIdsHashed", startGame.isBlockNetworkIdsHashed());
        json.addProperty("blockRegistryChecksum", Long.toUnsignedString(startGame.getBlockRegistryChecksum()));
        JsonArray experiments = new JsonArray();
        for (ExperimentData experiment : startGame.getExperiments()) {
            experiments.add(experiment.getName() + "=" + experiment.isEnabled());
        }
        json.add("experiments", experiments);
        json.addProperty("referencePalette", reference.version().toString());
        json.addProperty("paletteStates", palette.size());
        json.addProperty("startGameBlockProperties", startGame.getBlockProperties().size());
        json.addProperty("items", result.items.size());
        json.addProperty("creativeGroups", result.creative.getGroups().size());
        json.addProperty("creativeItems", result.creative.getContents().size());
        json.addProperty("creativeBlockIdsResolved", resolvedHashes);
        JsonArray unresolved = new JsonArray();
        unresolvedHashes.forEach(hash -> unresolved.add(Integer.toUnsignedString(hash)));
        json.add("creativeBlockIdsUnresolved", unresolved);
        if (result.packStack != null) {
            JsonArray packs = new JsonArray();
            result.packStack.getBehaviorPacks().forEach(pack -> packs.add("behavior " + pack.getPackId() + " " + pack.getPackVersion()));
            result.packStack.getResourcePacks().forEach(pack -> packs.add("resource " + pack.getPackId() + " " + pack.getPackVersion()));
            json.add("packStack", packs);
        }
        JsonObject counts = new JsonObject();
        result.packetCounts.forEach(counts::addProperty);
        json.add("packets", counts);
        return json;
    }

    int unresolvedHashes() {
        return unresolvedHashes.size();
    }

    private static NbtMap blockPropertiesList(List<BlockPropertyData> blocks) {
        List<NbtMap> list = new ArrayList<>();
        for (BlockPropertyData block : blocks) {
            list.add(NbtMap.builder().putString("name", block.getName()).putCompound("properties", block.getProperties()).build());
        }
        return NbtMap.builder().putList("blocks", NbtType.COMPOUND, list).build();
    }

    private static NbtMap itemRegistryList(List<ItemDefinition> items) {
        List<NbtMap> list = new ArrayList<>();
        for (ItemDefinition item : items) {
            list.add(NbtMap.builder()
                .putString("name", item.getIdentifier())
                .putShort("id", (short) item.getRuntimeId())
                .putBoolean("componentBased", item.isComponentBased())
                .putInt("version", item.getVersion().ordinal())
                .putCompound("data", item.getComponentData() == null ? NbtMap.EMPTY : item.getComponentData())
                .build());
        }
        return NbtMap.builder().putList("items", NbtType.COMPOUND, list).build();
    }

    private static void writeGzip(Path file, NbtMap tag) throws IOException {
        try (OutputStream stream = Files.newOutputStream(file); NBTOutputStream output = NbtUtils.createGZIPWriter(stream)) {
            output.writeTag(tag);
        }
    }

    private static String base64(NbtMap tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (NBTOutputStream output = NbtUtils.createWriterLE(bytes)) {
            output.writeTag(tag);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
