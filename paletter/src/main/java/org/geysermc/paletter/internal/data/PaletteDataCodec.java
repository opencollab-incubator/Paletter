package org.geysermc.paletter.internal.data;

import org.geysermc.paletter.data.DataVersion;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.block.BlockFallback;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.internal.creative.RawGroup;
import org.geysermc.paletter.internal.creative.RawLayout;
import org.geysermc.paletter.internal.creative.RawNode;
import org.geysermc.paletter.internal.creative.RawStack;
import org.geysermc.paletter.item.ItemFallback;
import org.geysermc.paletter.vanilla.BedrockBlockType;
import org.geysermc.paletter.vanilla.BedrockItemType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The bundled data format: one gzipped big endian NBT compound per version. Written by the generator, read at runtime.
 * <pre>
 * format: int                      {@value #FORMAT} (1 is still read: it also has the items' components and data_driven_blocks)
 * name: string, protocol: int      the data version
 * blocks: [{name, states}]         vanilla block states in runtime id order
 * items: [{name, id, version, component_based}]
 *                                  (format 1: components?, and data_driven_blocks: {name: properties}; format 2 keeps
 *                                  both in properties.nbt, see PropertyStore)
 * creative: {construction|nature|equipment|items: [node]}
 *     node: {group: name, icon?: stack, stacks: [stack]} or a stack
 *     stack: {id, damage?, count?, block?: {name, states}, nbt?}
 * item_tags: {tag: [identifier]}
 * fallbacks: {blocks: {name: {target, clear_states, force}}, items: {name: {target, force}}, unmapped: [name]}
 *                                  optional, and not part of the bundled data (consumers define fallbacks); older
 *                                  files and custom data sources may still carry it
 * block_defaults: {name: states}   optional
 * experiments: {toggle: {items: [name], blocks: [name], creative: [id or id:damage]}}
 *                                  optional; only written when the version was captured with experiments
 * block_items: {block: item}       optional (data before it has none): the item that places each block
 * </pre>
 */
@Internal
public final class PaletteDataCodec {
    public static final int FORMAT = 2;
    /** The format before properties moved to their own file; still read, and written on request. */
    public static final int FORMAT_1 = 1;

    private PaletteDataCodec() {
    }

    public static @NonNull VanillaData read(@NonNull InputStream stream) throws IOException {
        NbtMap root;
        try (NBTInputStream input = NbtUtils.createGZIPReader(stream)) {
            root = (NbtMap) input.readTag();
        }
        int format = root.getInt("format", -1);
        if (format != FORMAT && format != FORMAT_1) {
            throw new IOException("Unsupported Paletter data format " + format + ", expected " + FORMAT_1 + " or " + FORMAT);
        }
        DataVersion version = new DataVersion(root.getString("name"), root.getInt("protocol"));

        // States repeat a lot between blocks (e.g. every log has the same pillar_axis compound); share instances
        Map<NbtMap, NbtMap> interner = new HashMap<>();
        List<NbtMap> blocks = new ArrayList<>();
        for (NbtMap block : root.getList("blocks", NbtType.COMPOUND)) {
            NbtMap states = interner.computeIfAbsent(block.getCompound("states"), s -> s);
            blocks.add(BlockStates.of(block.getString("name"), states));
        }

        List<VanillaItem> items = new ArrayList<>();
        for (NbtMap item : root.getList("items", NbtType.COMPOUND)) {
            items.add(new VanillaItem(item.getString("name"), item.getInt("id"), item.getInt("version"),
                item.getBoolean("component_based"), item.getCompound("components", null)));
        }

        List<BlockPropertyData> dataDriven = new ArrayList<>();
        root.getCompound("data_driven_blocks", NbtMap.EMPTY).forEach((name, properties) ->
            dataDriven.add(new BlockPropertyData(name, (NbtMap) properties)));

        Map<CreativeCategory, List<RawNode>> categories = new EnumMap<>(CreativeCategory.class);
        NbtMap creative = root.getCompound("creative", NbtMap.EMPTY);
        for (CreativeCategory category : CreativeCategory.values()) {
            List<RawNode> nodes = new ArrayList<>();
            for (NbtMap node : creative.getList(category.bedrockName(), NbtType.COMPOUND, List.of())) {
                if (node.containsKey("group")) {
                    NbtMap icon = node.getCompound("icon", null);
                    List<RawStack> stacks = new ArrayList<>();
                    for (NbtMap stack : node.getList("stacks", NbtType.COMPOUND)) {
                        stacks.add(readStack(stack, interner));
                    }
                    nodes.add(new RawGroup(node.getString("group"), icon == null ? null : readStack(icon, interner), stacks));
                } else {
                    nodes.add(readStack(node, interner));
                }
            }
            categories.put(category, nodes);
        }

        Map<String, List<String>> tags = new LinkedHashMap<>();
        root.getCompound("item_tags", NbtMap.EMPTY).forEach((tag, list) -> {
            List<String> identifiers = new ArrayList<>();
            for (Object identifier : (List<?>) list) {
                identifiers.add((String) identifier);
            }
            tags.put(tag, identifiers);
        });

        NbtMap fallbacksNbt = root.getCompound("fallbacks", NbtMap.EMPTY);
        Map<String, BlockFallback> blockFallbacks = new HashMap<>();
        fallbacksNbt.getCompound("blocks", NbtMap.EMPTY).forEach((name, value) -> {
            NbtMap fallback = (NbtMap) value;
            BedrockBlockType target = BedrockBlockType.of(fallback.getString("target"));
            blockFallbacks.put(name, new BlockFallback(target, fallback.getBoolean("clear_states") ? target.defaultState() : null,
                fallback.getBoolean("force")));
        });
        Map<String, ItemFallback> itemFallbacks = new HashMap<>();
        fallbacksNbt.getCompound("items", NbtMap.EMPTY).forEach((name, value) -> {
            NbtMap fallback = (NbtMap) value;
            itemFallbacks.put(name, new ItemFallback(BedrockItemType.of(fallback.getString("target")), null, fallback.getBoolean("force")));
        });
        Set<String> unmapped = new HashSet<>(fallbacksNbt.getList("unmapped", NbtType.STRING, List.of()));

        Map<String, NbtMap> defaults = new HashMap<>();
        root.getCompound("block_defaults", NbtMap.EMPTY).forEach((name, states) ->
            defaults.put(name, BlockStates.of(name, interner.computeIfAbsent((NbtMap) states, s -> s))));

        Map<String, ExperimentContent> experiments = new HashMap<>();
        root.getCompound("experiments", NbtMap.EMPTY).forEach((toggle, value) -> {
            NbtMap content = (NbtMap) value;
            experiments.put(toggle, new ExperimentContent(content.getList("items", NbtType.STRING, List.of()),
                content.getList("blocks", NbtType.STRING, List.of()), content.getList("creative", NbtType.STRING, List.of())));
        });

        Map<String, String> blockItems = new HashMap<>();
        root.getCompound("block_items", NbtMap.EMPTY).forEach((block, item) -> blockItems.put(block, (String) item));

        return new VanillaData(version, blocks, items, dataDriven, RawLayout.of(categories), tags,
            new VersionFallbacks(blockFallbacks, itemFallbacks, unmapped), defaults, experiments, blockItems);
    }

    /**
     * Writes the data. Maps are written sorted by key, so the same data always produces the same bytes.
     */
    public static void write(@NonNull VanillaData data, @NonNull OutputStream stream) throws IOException {
        write(data, stream, FORMAT);
    }

    /**
     * Writes the data in {@link #FORMAT} or the older {@link #FORMAT_1}. Format 2 leaves out the item components and
     * the data driven blocks of {@code data}: they belong in the {@link PropertyStore}.
     */
    public static void write(@NonNull VanillaData data, @NonNull OutputStream stream, int format) throws IOException {
        if (format != FORMAT && format != FORMAT_1) {
            throw new IllegalArgumentException("Unsupported Paletter data format " + format);
        }
        NbtMapBuilder root = NbtMap.builder()
            .putInt("format", format)
            .putString("name", data.version().name())
            .putInt("protocol", data.version().protocolVersion());

        root.putList("blocks", NbtType.COMPOUND, data.blockStates());

        List<NbtMap> items = new ArrayList<>(data.items().size());
        for (VanillaItem item : data.items()) {
            NbtMapBuilder builder = NbtMap.builder()
                .putString("name", item.identifier())
                .putInt("id", item.runtimeId())
                .putInt("version", item.version())
                .putBoolean("component_based", item.componentBased());
            if (format == FORMAT_1 && item.componentData() != null) {
                builder.putCompound("components", item.componentData());
            }
            items.add(builder.build());
        }
        root.putList("items", NbtType.COMPOUND, items);

        if (format == FORMAT_1) {
            NbtMapBuilder dataDriven = NbtMap.builder();
            for (BlockPropertyData block : data.dataDrivenBlocks()) {
                dataDriven.putCompound(block.getName(), block.getProperties());
            }
            root.putCompound("data_driven_blocks", dataDriven.build());
        }

        NbtMapBuilder creative = NbtMap.builder();
        for (CreativeCategory category : CreativeCategory.values()) {
            List<NbtMap> nodes = new ArrayList<>();
            for (RawNode node : data.creative().nodes(category)) {
                switch (node) {
                    case RawStack stack -> nodes.add(writeStack(stack));
                    case RawGroup group -> {
                        NbtMapBuilder builder = NbtMap.builder().putString("group", group.name());
                        if (group.icon() != null) {
                            builder.putCompound("icon", writeStack(group.icon()));
                        }
                        builder.putList("stacks", NbtType.COMPOUND, group.stacks().stream().map(PaletteDataCodec::writeStack).toList());
                        nodes.add(builder.build());
                    }
                }
            }
            creative.putList(category.bedrockName(), NbtType.COMPOUND, nodes);
        }
        root.putCompound("creative", creative.build());

        NbtMapBuilder tags = NbtMap.builder();
        new TreeMap<>(data.itemTags()).forEach((tag, identifiers) -> tags.putList(tag, NbtType.STRING, identifiers));
        root.putCompound("item_tags", tags.build());

        // Bundled data has no fallbacks (consumers define them); only data that carries some writes them
        if (!data.fallbacks().blocks().isEmpty() || !data.fallbacks().items().isEmpty() || !data.fallbacks().unmapped().isEmpty()) {
            writeFallbacks(data, root);
        }

        NbtMapBuilder defaults = NbtMap.builder();
        new TreeMap<>(data.blockDefaults()).forEach((name, state) -> defaults.putCompound(name, BlockStates.states(state)));
        root.putCompound("block_defaults", defaults.build());

        writeRest(data, root);

        try (NBTOutputStream output = NbtUtils.createGZIPWriter(stream)) {
            output.writeTag(root.build());
        }
    }

    private static void writeFallbacks(VanillaData data, NbtMapBuilder root) {
        NbtMapBuilder blockFallbacks = NbtMap.builder();
        new TreeMap<>(data.fallbacks().blocks()).forEach((name, fallback) -> blockFallbacks.putCompound(name, NbtMap.builder()
            .putString("target", fallback.target().identifier())
            .putBoolean("clear_states", fallback.state() != null)
            .putBoolean("force", fallback.force())
            .build()));
        NbtMapBuilder itemFallbacks = NbtMap.builder();
        new TreeMap<>(data.fallbacks().items()).forEach((name, fallback) -> itemFallbacks.putCompound(name, NbtMap.builder()
            .putString("target", fallback.target().identifier())
            .putBoolean("force", fallback.force())
            .build()));
        root.putCompound("fallbacks", NbtMap.builder()
            .putCompound("blocks", blockFallbacks.build())
            .putCompound("items", itemFallbacks.build())
            .putList("unmapped", NbtType.STRING, new ArrayList<>(new TreeSet<>(data.fallbacks().unmapped())))
            .build());
    }

    private static void writeRest(VanillaData data, NbtMapBuilder root) {
        // Only when present, so versions captured without experiments keep their bytes
        if (!data.experiments().isEmpty()) {
            NbtMapBuilder experiments = NbtMap.builder();
            new TreeMap<>(data.experiments()).forEach((toggle, content) -> experiments.putCompound(toggle, NbtMap.builder()
                .putList("items", NbtType.STRING, new ArrayList<>(new TreeSet<>(content.items())))
                .putList("blocks", NbtType.STRING, new ArrayList<>(new TreeSet<>(content.blocks())))
                .putList("creative", NbtType.STRING, new ArrayList<>(new TreeSet<>(content.creative())))
                .build()));
            root.putCompound("experiments", experiments.build());
        }

        if (!data.blockItems().isEmpty()) {
            NbtMapBuilder blockItems = NbtMap.builder();
            new TreeMap<>(data.blockItems()).forEach(blockItems::putString);
            root.putCompound("block_items", blockItems.build());
        }
    }

    private static RawStack readStack(NbtMap nbt, Map<NbtMap, NbtMap> interner) {
        NbtMap block = nbt.getCompound("block", null);
        if (block != null) {
            block = BlockStates.of(block.getString("name"), interner.computeIfAbsent(block.getCompound("states"), s -> s));
        }
        return new RawStack(nbt.getString("id"), nbt.getInt("damage", 0), nbt.getInt("count", 1), block, nbt.getCompound("nbt", null));
    }

    private static NbtMap writeStack(RawStack stack) {
        NbtMapBuilder builder = NbtMap.builder().putString("id", stack.identifier());
        if (stack.damage() != 0) {
            builder.putInt("damage", stack.damage());
        }
        if (stack.count() != 1) {
            builder.putInt("count", stack.count());
        }
        if (stack.blockState() != null) {
            builder.putCompound("block", stack.blockState());
        }
        if (stack.tag() != null) {
            builder.putCompound("nbt", stack.tag());
        }
        return builder.build();
    }
}
