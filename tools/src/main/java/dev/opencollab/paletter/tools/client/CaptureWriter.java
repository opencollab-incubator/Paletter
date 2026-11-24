package dev.opencollab.paletter.tools.client;

import dev.opencollab.paletter.tools.bds.CapturePlatform;
import dev.opencollab.paletter.tools.data.Nbt;
import dev.opencollab.paletter.tools.data.Importer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.ExperimentData;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemGroup;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import dev.opencollab.paletter.internal.data.VanillaData;

import java.io.IOException;
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
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Writes a capture in the layout of CloudburstMC/Data that the generator's importer reads, plus the raw wire data.
 * <p>
 * The wire does not carry the vanilla block palette: the client has it built in. {@code block_palette.nbt} is
 * therefore the reference palette (a bundled Paletter version) plus the states of every block the server sent block
 * properties for that the reference lacks, and every block runtime id the server did send (creative items) is checked
 * against the hashes of that palette, which is only possible because BDS sends hashed ids.
 */
public final class CaptureWriter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final CaptureClient.Result result;
    private final VanillaData reference;
    private final String bdsVersion;
    private final String platform;
    private final Map<Integer, NbtMap> statesByHash = new LinkedHashMap<>();
    private final List<NbtMap> palette = new ArrayList<>();
    private final Set<Integer> unresolvedHashes = new LinkedHashSet<>();
    /** The item of the first creative stack that carried each unresolved block id, so a report can name the block. */
    private final Map<Integer, String> unresolvedItems = new LinkedHashMap<>();
    private int resolvedHashes;
    /**
     * Blocks with an {@code enabled_states} value the trait state table has no states for yet, to those values: their states
     * are incomplete here, so their creative block ids are kept apart ({@link #pendingHashes}); the mapping inference checks
     * them against the states it derives ({@code TraitStateTable#verify}).
     */
    private final Map<String, Set<String>> unknownTraitStates = new TreeMap<>();
    private final Set<Integer> pendingHashes = new LinkedHashSet<>();
    /**
     * Blocks with a state whose hash a state of another block has: BDS gives one of them another id (docs/wire-notes.md, hash
     * collisions), so their creative block ids are kept apart ({@link #collidingHashes}) and not resolved to a state.
     */
    private final Set<String> collidingBlocks = new TreeSet<>();
    private final Set<Integer> collidingHashes = new LinkedHashSet<>();
    /**
     * Blocks the reference marks as experiment content but has no states for (its data was imported from a world without that
     * experiment: 26.40 has no states for the {@code y_2026_drop_3} wool slabs and stairs). In a world with the experiment on
     * BDS sends creative block ids for them that nothing here can resolve or disprove, so they are kept apart
     * ({@link #experimentHashes}), unverified.
     */
    private final Set<String> statelessExperimentBlocks;
    private final Set<Integer> experimentHashes = new LinkedHashSet<>();
    private final Set<String> experimentBlocksSent = new TreeSet<>();
    /** Whether the capture ran {@code /give}: then {@link GiveCapture#FILE} is written, with the unanswered blocks unverified. */
    private boolean giveRequested;
    /** The protocol the client announced and that of the codec that decoded the packets; 0 until {@link #withProtocol}. */
    private int protocol;
    private int codecProtocol;

    public CaptureWriter(CaptureClient.Result result, VanillaData reference, String bdsVersion) {
        this(result, reference, bdsVersion, CapturePlatform.host());
    }

    /** @param platform the OS the server runs on ({@link CapturePlatform}), recorded in {@code capture.json} */
    public CaptureWriter(CaptureClient.Result result, VanillaData reference, String bdsVersion, String platform) {
        this.result = result;
        this.reference = reference;
        this.bdsVersion = bdsVersion;
        this.platform = platform;
        Set<String> referenceBlocks = new LinkedHashSet<>();
        for (NbtMap state : reference.blockStates()) {
            palette.add(state);
            referenceBlocks.add(state.getString("name"));
        }
        statelessExperimentBlocks = experimentBlocksWithoutStates(reference);
        for (BlockPropertyData block : result.startGame.getBlockProperties()) {
            if (!referenceBlocks.contains(block.getName())) {
                Set<String> unknown = new TreeSet<>();
                palette.addAll(BlockHashes.customStates(block.getName(), block.getProperties(), unknown));
                if (!unknown.isEmpty()) {
                    unknownTraitStates.put(block.getName(), unknown);
                }
            }
        }
        for (NbtMap state : palette) {
            NbtMap previous = statesByHash.putIfAbsent(BlockHashes.hash(state), state);
            if (previous != null) {
                collidingBlocks.add(previous.getString("name"));
                collidingBlocks.add(state.getString("name"));
            }
        }
        // Which of them BDS gave the hash is what the capture shows (HashCollisionCaptureTest): resolve none of their ids
        statesByHash.values().removeIf(state -> collidingBlocks.contains(state.getString("name")));
    }

    /**
     * Records the protocol in {@code capture.json}, and {@code codecProtocol} next to it when another codec decoded the
     * packets than the protocol's own (the library has none for it yet, so the decoding may be wrong).
     */
    public CaptureWriter withProtocol(int protocol, int codecProtocol) {
        this.protocol = protocol;
        this.codecProtocol = codecProtocol;
        return this;
    }

    /** Adds {@code protocol}, and {@code codecProtocol} only when it differs: a native codec leaves it out. */
    public static void addProtocol(JsonObject json, int protocol, int codecProtocol) {
        json.addProperty("protocol", protocol);
        if (codecProtocol != protocol) {
            json.addProperty("codecProtocol", codecProtocol);
        }
    }

    public CaptureWriter withGives() {
        giveRequested = true;
        return this;
    }

    public void write(Path directory, Path customDirectory) throws IOException {
        Files.createDirectories(directory);
        StartGamePacket startGame = result.startGame;

        // Importer layout
        Nbt.writeGzip(directory.resolve("block_palette.nbt"), NbtMap.builder()
            .putList("blocks", NbtType.COMPOUND, palette).build());
        JsonArray itemStates = new JsonArray();
        NbtMapBuilder components = NbtMap.builder();
        for (ItemDefinition item : result.items) {
            itemStates.add(itemState(item.getIdentifier(), item.getRuntimeId(), item.getVersion().ordinal(), item.isComponentBased()));
            if (item.getComponentData() != null && !item.getComponentData().isEmpty()) {
                components.putCompound(item.getIdentifier(), item.getComponentData());
            }
        }
        writeItemStates(directory, itemStates);
        Nbt.writeGzip(directory.resolve("item_components.nbt"), components.build());
        writeCreativeItems(directory, creative());
        NbtMapBuilder dataDriven = NbtMap.builder();
        for (BlockPropertyData block : startGame.getBlockProperties()) {
            dataDriven.putCompound(block.getName(), block.getProperties());
        }
        Nbt.writeGzip(directory.resolve("data_driven_blocks.nbt"), dataDriven.build());

        // Raw wire data, in wire order and unmodified
        Nbt.writeGzip(directory.resolve("raw_start_game_block_properties.nbt"), blockPropertiesList(startGame.getBlockProperties()));
        Nbt.writeGzip(directory.resolve("raw_item_registry.nbt"), itemRegistryList(result.items));
        if (result.entityIdentifiers != null) {
            Nbt.writeGzip(directory.resolve("raw_entity_identifiers.nbt"), result.entityIdentifiers.getIdentifiers());
        }
        if (result.voxelShapes != null) {
            VoxelShapeCapture.write(directory.resolve(VoxelShapeCapture.FILE), result.voxelShapes, bdsVersion);
        }
        Map<Integer, String> itemNames = new LinkedHashMap<>();
        for (ItemDefinition item : result.items) {
            itemNames.put(item.getRuntimeId(), item.getIdentifier());
        }
        new RecipeCapture(itemNames, statesByHash::get).write(directory, result.crafting, result.trim, bdsVersion);
        Files.writeString(directory.resolve("capture.json"), GSON.toJson(summary()));
        if (giveRequested) {
            Files.writeString(directory.resolve(GiveCapture.FILE), GiveCapture.render(bdsVersion, reference.blockItems(), result.gives, !result.givesAborted));
        }

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
        Nbt.writeGzip(directory.resolve("custom_block_properties.nbt"), blockNbt);
        Nbt.writeGzip(directory.resolve("custom_item_registry.nbt"), itemNbt);
        StringBuilder blockText = new StringBuilder();
        for (BlockPropertyData block : blocks) {
            blockText.append("# ").append(block.getName()).append('\n').append(Nbt.snbt(block.getProperties())).append("\n\n");
        }
        Files.writeString(directory.resolve("custom_block_properties.snbt"), blockText);
        StringBuilder itemText = new StringBuilder();
        for (ItemDefinition item : items) {
            itemText.append("# ").append(item.getIdentifier()).append(" id=").append(item.getRuntimeId())
                .append(" version=").append(item.getVersion()).append(" componentBased=").append(item.isComponentBased())
                .append('\n').append(Nbt.snbt(item.getComponentData() == null ? NbtMap.EMPTY : item.getComponentData()))
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
            List<NbtMap> states = BlockHashes.customStates(block.getName(), block.getProperties(), new TreeSet<>());
            customStates.add(block.getName(), customState(states, unknownTraitStates.get(block.getName())));
        }
        Files.writeString(directory.resolve("custom_block_states.json"), GSON.toJson(customStates));
    }

    /**
     * One block's entry of {@code custom_block_states.json}. For a block with trait states the table does not know
     * ({@code unknown} not null) the states, the default hash and the default state are known to be wrong, so they are left out.
     */
    static JsonObject customState(List<NbtMap> states, Set<String> unknown) {
        JsonObject entry = new JsonObject();
        if (unknown != null) {
            JsonArray values = new JsonArray();
            unknown.forEach(values::add);
            entry.add("traitStatesUnknown", values);
            return entry;
        }
        entry.addProperty("states", states.size());
        entry.addProperty("defaultHash", Integer.toUnsignedString(BlockHashes.hash(states.getFirst())));
        entry.addProperty("default", Nbt.snbt(states.getFirst().getCompound("states")).replaceAll("\\s+", " "));
        return entry;
    }

    /** An entry of {@code runtime_item_states.json} (the layout of CloudburstMC/Data, also written by {@code Export}). */
    public static JsonObject itemState(String name, int runtimeId, int version, boolean componentBased) {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("id", runtimeId);
        json.addProperty("version", version);
        json.addProperty("componentBased", componentBased);
        return json;
    }

    public static void writeItemStates(Path directory, JsonArray itemStates) throws IOException {
        Files.writeString(directory.resolve("runtime_item_states.json"), GSON.toJson(itemStates));
    }

    /** {@code creative_items.json}: {@code groups} and {@code items}. */
    public static void writeCreativeItems(Path directory, JsonObject creative) throws IOException {
        Files.writeString(directory.resolve("creative_items.json"), GSON.toJson(creative));
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
                json.addProperty("block_state_b64", Nbt.writeLeBase64(state));
            } else if (unknownTraitStates.containsKey(item.getDefinition().getIdentifier())) {
                pendingHashes.add(block.getRuntimeId());
            } else if (collidingBlocks.contains(item.getDefinition().getIdentifier())) {
                collidingHashes.add(block.getRuntimeId());
            } else if (statelessExperimentBlocks.contains(item.getDefinition().getIdentifier())) {
                experimentHashes.add(block.getRuntimeId());
                experimentBlocksSent.add(item.getDefinition().getIdentifier());
            } else {
                unresolvedHashes.add(block.getRuntimeId());
                unresolvedItems.putIfAbsent(block.getRuntimeId(), item.getDefinition().getIdentifier());
            }
        }
        if (item.getTag() != null) {
            json.addProperty("nbt_b64", Nbt.writeLeBase64(item.getTag()));
        }
        return json;
    }

    private JsonObject summary() {
        StartGamePacket startGame = result.startGame;
        JsonObject json = new JsonObject();
        json.addProperty("bdsVersion", bdsVersion);
        json.addProperty(CapturePlatform.KEY, platform);
        if (protocol != 0) {
            addProtocol(json, protocol, codecProtocol);
        }
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
        if (!unresolvedItems.isEmpty()) {
            JsonArray items = new JsonArray();
            unresolvedBlocks().forEach(items::add);
            json.add("creativeBlockIdsUnresolvedItems", items);
        }
        if (!collidingBlocks.isEmpty()) {
            JsonArray blocks = new JsonArray();
            collidingBlocks.forEach(blocks::add);
            json.add("hashCollisionBlocks", blocks);
            JsonArray ids = new JsonArray();
            collidingHashes.forEach(hash -> ids.add(Integer.toUnsignedString(hash)));
            json.add("creativeBlockIdsOfHashCollisions", ids);
        }
        if (!experimentHashes.isEmpty()) {
            JsonArray blocks = new JsonArray();
            experimentBlocksSent.forEach(blocks::add);
            json.add("experimentBlocksWithoutStates", blocks);
            JsonArray ids = new JsonArray();
            experimentHashes.forEach(hash -> ids.add(Integer.toUnsignedString(hash)));
            json.add("creativeBlockIdsOfExperimentBlocks", ids);
        }
        if (!unknownTraitStates.isEmpty()) {
            JsonArray pending = new JsonArray();
            pendingHashes.forEach(hash -> pending.add(Integer.toUnsignedString(hash)));
            json.add("creativeBlockIdsOfUnknownTraitStates", pending);
            JsonArray values = new JsonArray();
            traitStatesUnknown().forEach(values::add);
            json.add("traitStatesUnknown", values);
        }
        if (result.packStack != null) {
            JsonArray packs = new JsonArray();
            result.packStack.getBehaviorPacks().forEach(pack -> packs.add("behavior " + pack.getPackId() + " " + pack.getPackVersion()));
            result.packStack.getResourcePacks().forEach(pack -> packs.add("resource " + pack.getPackId() + " " + pack.getPackVersion()));
            json.add("packStack", packs);
            JsonArray packExperiments = new JsonArray();
            result.packStack.getExperiments().forEach(experiment -> packExperiments.add(experiment.getName() + "=" + experiment.isEnabled()));
            json.add("packStackExperiments", packExperiments);
        }
        JsonObject counts = new JsonObject();
        result.packetCounts.forEach(counts::addProperty);
        json.add("packets", counts);
        // What BDS sent, in order, with the client's own sends; not what a client requires (unverified). Captures made
        // before this field was added do not have it.
        addPacketOrder(json, result);
        return json;
    }

    static void addPacketOrder(JsonObject json, CaptureClient.Result result) {
        JsonArray order = new JsonArray();
        result.packetOrder.forEach(order::add);
        json.add("packetOrder", order);
        json.addProperty("startGameIndex", result.startGameIndex);
    }

    int resolvedHashes() {
        return resolvedHashes;
    }

    /** @return each unresolved creative block id as {@code <item of its creative stack> <id>}, in the order they were sent */
    public List<String> unresolvedBlocks() {
        List<String> blocks = new ArrayList<>();
        unresolvedItems.forEach((hash, item) -> blocks.add(item + " " + Integer.toUnsignedString(hash)));
        return blocks;
    }

    public int unresolvedHashes() {
        return unresolvedHashes.size();
    }

    /** The blocks {@code reference} marks as experiment content that it has no states for, sorted. */
    public static Set<String> experimentBlocksWithoutStates(VanillaData reference) {
        Set<String> withStates = new java.util.HashSet<>();
        reference.blockStates().forEach(state -> withStates.add(state.getString("name")));
        Set<String> blocks = new TreeSet<>();
        reference.experiments().values().forEach(content -> content.blocks().stream().filter(block -> !withStates.contains(block))
            .forEach(blocks::add));
        return blocks;
    }

    /** @return the experiment blocks without reference states whose creative block ids were kept apart, sorted */
    List<String> experimentBlocksUnverified() {
        return List.copyOf(experimentBlocksSent);
    }

    /** @return the {@code <trait> <value>}s BDS sent that the trait state table has no states for yet, sorted */
    List<String> traitStatesUnknown() {
        Set<String> values = new TreeSet<>();
        unknownTraitStates.values().forEach(values::addAll);
        return List.copyOf(values);
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

}
