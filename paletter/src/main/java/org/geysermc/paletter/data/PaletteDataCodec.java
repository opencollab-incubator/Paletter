package org.geysermc.paletter.data;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
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
import org.geysermc.paletter.item.ItemFallback;

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
import java.util.TreeSet;

/**
 * The bundled data format: one gzipped big endian NBT compound per version. Written by the generator, read at runtime.
 * <pre>
 * format: int                      {@value #FORMAT}
 * name: string, protocol: int      the data version
 * blocks: [{name, states}]         vanilla block states in runtime id order
 * items: [{name, id, version, component_based, components?}]
 * data_driven_blocks: {name: properties}
 * creative: {construction|nature|equipment|items: [node]}
 *     node: {group: name, icon?: stack, stacks: [stack]} or a stack
 *     stack: {id, damage?, count?, block?: {name, states}, nbt?}
 * item_tags: {tag: [identifier]}
 * fallbacks: {blocks: {name: {target, clear_states, force}}, items: {name: {target, force}}, unmapped: [name]}
 * block_defaults: {name: states}   optional
 * </pre>
 */
public final class PaletteDataCodec {
    public static final int FORMAT = 1;

    private PaletteDataCodec() {
    }

    public static @NonNull VanillaData read(@NonNull InputStream stream) throws IOException {
        NbtMap root;
        try (NBTInputStream input = NbtUtils.createGZIPReader(stream)) {
            root = (NbtMap) input.readTag();
        }
        int format = root.getInt("format", -1);
        if (format != FORMAT) {
            throw new IOException("Unsupported Paletter data format " + format + ", expected " + FORMAT);
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

        Map<CreativeCategory, List<CreativeNode>> categories = new EnumMap<>(CreativeCategory.class);
        NbtMap creative = root.getCompound("creative", NbtMap.EMPTY);
        for (CreativeCategory category : CreativeCategory.values()) {
            List<CreativeNode> nodes = new ArrayList<>();
            for (NbtMap node : creative.getList(category.bedrockName(), NbtType.COMPOUND, List.of())) {
                if (node.containsKey("group")) {
                    NbtMap icon = node.getCompound("icon", null);
                    List<CreativeStack> stacks = new ArrayList<>();
                    for (NbtMap stack : node.getList("stacks", NbtType.COMPOUND)) {
                        stacks.add(readStack(stack, interner));
                    }
                    nodes.add(new CreativeGroup(node.getString("group"), icon == null ? null : readStack(icon, interner), stacks));
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
            blockFallbacks.put(name, new BlockFallback(fallback.getString("target"), fallback.getBoolean("clear_states"),
                fallback.getBoolean("force")));
        });
        Map<String, ItemFallback> itemFallbacks = new HashMap<>();
        fallbacksNbt.getCompound("items", NbtMap.EMPTY).forEach((name, value) -> {
            NbtMap fallback = (NbtMap) value;
            itemFallbacks.put(name, new ItemFallback(fallback.getString("target"), fallback.getBoolean("force")));
        });
        Set<String> unmapped = new HashSet<>(fallbacksNbt.getList("unmapped", NbtType.STRING, List.of()));

        Map<String, NbtMap> defaults = new HashMap<>();
        root.getCompound("block_defaults", NbtMap.EMPTY).forEach((name, states) ->
            defaults.put(name, BlockStates.of(name, interner.computeIfAbsent((NbtMap) states, s -> s))));

        return new VanillaData(version, blocks, items, dataDriven, CreativeLayout.of(categories), tags,
            new VersionFallbacks(blockFallbacks, itemFallbacks, unmapped), defaults);
    }

    public static void write(@NonNull VanillaData data, @NonNull OutputStream stream) throws IOException {
        NbtMapBuilder root = NbtMap.builder()
            .putInt("format", FORMAT)
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
            if (item.componentData() != null) {
                builder.putCompound("components", item.componentData());
            }
            items.add(builder.build());
        }
        root.putList("items", NbtType.COMPOUND, items);

        NbtMapBuilder dataDriven = NbtMap.builder();
        for (BlockPropertyData block : data.dataDrivenBlocks()) {
            dataDriven.putCompound(block.getName(), block.getProperties());
        }
        root.putCompound("data_driven_blocks", dataDriven.build());

        NbtMapBuilder creative = NbtMap.builder();
        for (CreativeCategory category : CreativeCategory.values()) {
            List<NbtMap> nodes = new ArrayList<>();
            for (CreativeNode node : data.creative().nodes(category)) {
                switch (node) {
                    case CreativeStack stack -> nodes.add(writeStack(stack));
                    case CreativeGroup group -> {
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
        data.itemTags().forEach((tag, identifiers) -> tags.putList(tag, NbtType.STRING, identifiers));
        root.putCompound("item_tags", tags.build());

        NbtMapBuilder blockFallbacks = NbtMap.builder();
        data.fallbacks().blocks().forEach((name, fallback) -> blockFallbacks.putCompound(name, NbtMap.builder()
            .putString("target", fallback.target())
            .putBoolean("clear_states", fallback.clearStates())
            .putBoolean("force", fallback.force())
            .build()));
        NbtMapBuilder itemFallbacks = NbtMap.builder();
        data.fallbacks().items().forEach((name, fallback) -> itemFallbacks.putCompound(name, NbtMap.builder()
            .putString("target", fallback.target())
            .putBoolean("force", fallback.force())
            .build()));
        root.putCompound("fallbacks", NbtMap.builder()
            .putCompound("blocks", blockFallbacks.build())
            .putCompound("items", itemFallbacks.build())
            .putList("unmapped", NbtType.STRING, new ArrayList<>(new TreeSet<>(data.fallbacks().unmapped())))
            .build());

        NbtMapBuilder defaults = NbtMap.builder();
        data.blockDefaults().forEach((name, state) -> defaults.putCompound(name, BlockStates.states(state)));
        root.putCompound("block_defaults", defaults.build());

        try (NBTOutputStream output = NbtUtils.createGZIPWriter(stream)) {
            output.writeTag(root.build());
        }
    }

    private static CreativeStack readStack(NbtMap nbt, Map<NbtMap, NbtMap> interner) {
        NbtMap block = nbt.getCompound("block", null);
        if (block != null) {
            block = BlockStates.of(block.getString("name"), interner.computeIfAbsent(block.getCompound("states"), s -> s));
        }
        return new CreativeStack(nbt.getString("id"), nbt.getInt("damage", 0), nbt.getInt("count", 1), block, nbt.getCompound("nbt", null));
    }

    private static NbtMap writeStack(CreativeStack stack) {
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
