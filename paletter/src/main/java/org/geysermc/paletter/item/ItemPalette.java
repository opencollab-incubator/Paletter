package org.geysermc.paletter.item;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.geysermc.paletter.PaletteException;
import org.geysermc.paletter.PaletteReport;
import org.geysermc.paletter.attach.AttachmentKey;
import org.geysermc.paletter.attach.AttachmentStore;
import org.geysermc.paletter.attach.Attachments;
import org.geysermc.paletter.block.BlockPalette;
import org.geysermc.paletter.block.BlockResolution;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.PaletteBlock;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.creative.CreativeStack;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.data.VanillaItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The compiled item palette of one {@link org.geysermc.paletter.Palette}. It is the item {@link DefinitionRegistry}
 * for the codec, and answers both directions of item translation:
 * <ul>
 *     <li>{@link #resolve(ItemVariant)}: what to send for a variant (written against the newest Bedrock version);</li>
 *     <li>{@link #match(ItemData)}: what the client sent, including whatever layers attached to it.</li>
 * </ul>
 * It also knows which block each block item is sent with, so consumers never have to work that out from the
 * creative inventory themselves.
 */
public final class ItemPalette implements DefinitionRegistry<ItemDefinition> {
    private static final NbtMap NO_BLOCK = NbtMap.builder().putString("name", "paletter:no_block").build();

    private final DataVersion version;
    private final BlockPalette blocks;
    private final Int2ObjectMap<PaletteItem> byRuntimeId;
    private final Map<String, PaletteItem> byIdentifier;
    private final List<PaletteItem> definitions;
    private final Map<String, NbtMap> blockOverrides;
    private final Set<String> customBlockItems;
    private final Map<String, PaletteBlock> defaultBlocks;
    private final Set<String> multiStateBlockItems;
    private final Map<String, ItemFallback> fallbacks;
    private final AttachmentStore<ItemVariant> attachments;
    private final Map<String, List<String>> tags;
    private final List<CustomItem> customItems;
    private final int nextRuntimeId;
    private final Map<ItemVariant, Optional<ResolvedItem>> resolutions = new ConcurrentHashMap<>();

    private ItemPalette(Builder builder, Map<String, PaletteBlock> defaultBlocks, Set<String> multiStateBlockItems) {
        this.version = builder.version;
        this.blocks = builder.blocks;
        this.byRuntimeId = builder.byRuntimeId;
        this.byIdentifier = builder.byIdentifier;
        List<PaletteItem> definitions = new ArrayList<>(builder.byRuntimeId.values());
        definitions.sort(Comparator.comparingInt(PaletteItem::getRuntimeId));
        this.definitions = List.copyOf(definitions);
        this.blockOverrides = Map.copyOf(builder.blockOverrides);
        this.customBlockItems = Set.copyOf(builder.customBlockItems);
        this.defaultBlocks = Map.copyOf(defaultBlocks);
        this.multiStateBlockItems = Set.copyOf(multiStateBlockItems);
        this.fallbacks = Map.copyOf(builder.fallbacks);
        this.attachments = builder.attachments;
        Map<String, List<String>> tags = new HashMap<>();
        builder.tags.forEach((tag, items) -> tags.put(tag, List.copyOf(items)));
        this.tags = Collections.unmodifiableMap(tags);
        this.customItems = List.copyOf(builder.customItems);
        this.nextRuntimeId = builder.nextRuntimeId;
    }

    /**
     * Starts the item palette of a version from its vanilla items.
     */
    public static @NonNull Builder vanilla(@NonNull VanillaData data, @NonNull BlockPalette blocks) {
        Builder builder = new Builder(data.version(), blocks, data.fallbacks().items());
        for (VanillaItem item : data.items()) {
            builder.put(new PaletteItem(item.identifier(), item.runtimeId(), ItemVersion.from(item.version()),
                item.componentBased(), item.componentData(), false));
        }
        data.itemTags().forEach((tag, items) -> builder.tags.put(tag, new LinkedHashSet<>(items)));
        return builder;
    }

    /**
     * Starts a palette extending this one. Unchanged definitions are shared, so they stay {@code ==} across palettes.
     *
     * @param blocks the block palette of the new palette; may differ from this one's if custom blocks were added
     */
    public @NonNull Builder extend(@NonNull BlockPalette blocks) {
        return new Builder(this, blocks);
    }

    public @NonNull DataVersion version() {
        return version;
    }

    public @NonNull BlockPalette blocks() {
        return blocks;
    }

    // ---- Definitions ----

    @Override
    public @Nullable PaletteItem getDefinition(int runtimeId) {
        return byRuntimeId.get(runtimeId);
    }

    @Override
    public @Nullable PaletteItem getDefinition(@NonNull String identifier) {
        return byIdentifier.get(identifier);
    }

    @Override
    public boolean isRegistered(@NonNull ItemDefinition definition) {
        return byRuntimeId.get(definition.getRuntimeId()) == definition;
    }

    /**
     * @return all definitions in runtime id order. This is what the item component packet carries
     */
    public @NonNull List<PaletteItem> definitions() {
        return definitions;
    }

    public @NonNull List<CustomItem> customItems() {
        return customItems;
    }

    public @NonNull ItemComponentPacket toPacket() {
        ItemComponentPacket packet = new ItemComponentPacket();
        packet.getItems().addAll(definitions);
        return packet;
    }

    /**
     * @return item tags, tag to item identifiers: vanilla tags plus tags of custom items and layers
     */
    public @NonNull Map<String, List<String>> tags() {
        return tags;
    }

    // ---- Blocks of block items ----

    /**
     * @return the block this item is sent with by default, or null if it is not a block item
     */
    public @Nullable PaletteBlock defaultBlock(@NonNull ItemDefinition definition) {
        return defaultBlocks.get(definition.getIdentifier());
    }

    public boolean isBlockItem(@NonNull ItemDefinition definition) {
        return defaultBlocks.containsKey(definition.getIdentifier());
    }

    /**
     * @return true if one item identifier places several different blocks, told apart only by the block state sent
     * with the item. Variants of such items should specify their block state
     */
    public boolean isMultiStateBlockItem(@NonNull String identifier) {
        return multiStateBlockItems.contains(identifier);
    }

    // ---- Translation ----

    /**
     * Resolves a variant, written against the newest Bedrock version, for this palette's version.
     * Items that do not exist in this version (or are forcibly replaced, e.g. because they are experimental there)
     * use their version fallback, if any. Results are cached.
     *
     * @return the resolved item, or null if the item does not exist and has no fallback
     */
    public @Nullable ResolvedItem resolve(@NonNull ItemVariant variant) {
        return resolutions.computeIfAbsent(variant, v -> Optional.ofNullable(resolveUncached(v))).orElse(null);
    }

    private @Nullable ResolvedItem resolveUncached(ItemVariant variant) {
        ItemFallback forced = fallbacks.get(variant.identifier());
        PaletteItem item = forced != null && forced.force() ? null : byIdentifier.get(variant.identifier());
        boolean fallback = false;
        if (item == null) {
            ItemFallback target = fallbacks.get(variant.identifier());
            item = target == null ? null : byIdentifier.get(target.target());
            if (item == null) {
                return null;
            }
            fallback = true;
        }
        PaletteBlock block = defaultBlocks.get(item.getIdentifier());
        if (variant.blockState() != null && !fallback && block != null) {
            BlockResolution resolution = blocks.resolve(variant.blockState());
            if (resolution.block() != null) {
                block = resolution.block();
            }
        }
        return new ResolvedItem(item, variant.damage(), block, fallback);
    }

    /**
     * Identifies a stack the client sent, and finds the values layers attached to it.
     * <p>
     * Attachments are looked up from the most to the least specific variant: identifier with damage and block state,
     * with damage only, with block state only, and finally the plain identifier. The block state is only considered
     * for block items, so the meaningless block definitions the client sends for other items do no harm.
     *
     * @return the match, or null for air or an unknown definition
     */
    public @Nullable ItemMatch match(@NonNull ItemData data) {
        ItemDefinition definition = data.getDefinition();
        if (definition == null || definition == ItemDefinition.AIR) {
            return null;
        }
        PaletteItem item = byRuntimeId.get(definition.getRuntimeId());
        if (item == null) {
            return null;
        }
        NbtMap state = null;
        BlockDefinition blockDefinition = data.getBlockDefinition();
        if (blockDefinition != null && defaultBlocks.containsKey(item.getIdentifier())) {
            PaletteBlock block = blocks.byRuntimeId(blockDefinition.getRuntimeId());
            if (block != null) {
                state = block.getState();
            }
        }
        ItemVariant variant = new ItemVariant(item.getIdentifier(), data.getDamage(), state);
        return match(item, variant);
    }

    /**
     * Like {@link #match(ItemData)}, for a variant.
     */
    public @Nullable ItemMatch match(@NonNull ItemVariant variant) {
        PaletteItem item = byIdentifier.get(variant.identifier());
        return item == null ? null : match(item, variant);
    }

    private ItemMatch match(PaletteItem item, ItemVariant variant) {
        String identifier = item.getIdentifier();
        ItemVariant[] candidates = variant.blockState() == null
            ? new ItemVariant[] {variant, ItemVariant.of(identifier)}
            : new ItemVariant[] {variant, variant.withBlockState(null), new ItemVariant(identifier, 0, variant.blockState()), ItemVariant.of(identifier)};
        for (ItemVariant candidate : candidates) {
            AttachmentStore.Values values = attachments.getIfPresent(candidate);
            if (values != null) {
                return new ItemMatch(item, variant, candidate, values);
            }
        }
        return new ItemMatch(item, variant, ItemVariant.of(identifier), Attachments.EMPTY);
    }

    public <T> @Nullable T attachment(@NonNull ItemData data, @NonNull AttachmentKey<T> key) {
        ItemMatch match = match(data);
        return match == null ? null : match.get(key);
    }

    /**
     * @return the values attached to exactly this variant (no less specific lookup)
     */
    public @NonNull Attachments attachments(@NonNull ItemVariant variant) {
        return attachments.get(variant);
    }

    /**
     * @return every variant that has attachments
     */
    public @NonNull Set<ItemVariant> attachedVariants() {
        return attachments.targets();
    }

    /**
     * Builds a creative stack for this version.
     *
     * @return the item data, or null if the item does not exist in this version
     */
    public @Nullable ItemData toItemData(@NonNull CreativeStack stack) {
        PaletteItem item = byIdentifier.get(stack.identifier());
        if (item == null) {
            return null;
        }
        PaletteBlock block = defaultBlocks.get(item.getIdentifier());
        if (stack.blockState() != null) {
            BlockResolution resolution = blocks.resolve(stack.blockState());
            if (resolution.kind() == BlockResolution.Kind.EXACT || resolution.kind() == BlockResolution.Kind.ADAPTED) {
                block = resolution.block();
            }
        }
        return ItemData.builder()
            .definition(item)
            .damage(stack.damage())
            .count(stack.count())
            .tag(stack.tag())
            .blockDefinition(block)
            .build();
    }

    @Override
    public String toString() {
        return "ItemPalette{" + version + ", " + definitions.size() + " items, " + customItems.size() + " custom}";
    }

    /**
     * Builds an item palette. Obtained from {@link #vanilla} or {@link #extend}; used by the palette compiler.
     */
    public static final class Builder {
        private final DataVersion version;
        private final BlockPalette blocks;
        private final Int2ObjectMap<PaletteItem> byRuntimeId;
        private final Map<String, PaletteItem> byIdentifier;
        private final Map<String, NbtMap> blockOverrides;
        private final Set<String> customBlockItems;
        private final Map<String, ItemFallback> fallbacks;
        private final AttachmentStore<ItemVariant> attachments;
        private final Map<String, Set<String>> tags = new LinkedHashMap<>();
        private final List<CustomItem> customItems;
        private int nextRuntimeId;

        private Builder(DataVersion version, BlockPalette blocks, Map<String, ItemFallback> fallbacks) {
            this.version = version;
            this.blocks = blocks;
            this.byRuntimeId = new Int2ObjectOpenHashMap<>();
            this.byIdentifier = new HashMap<>();
            this.blockOverrides = new HashMap<>();
            this.customBlockItems = new HashSet<>();
            this.fallbacks = new HashMap<>(fallbacks);
            this.attachments = new AttachmentStore<>();
            this.customItems = new ArrayList<>();
        }

        private Builder(ItemPalette parent, BlockPalette blocks) {
            this.version = parent.version;
            this.blocks = blocks;
            this.byRuntimeId = new Int2ObjectOpenHashMap<>(parent.byRuntimeId);
            this.byIdentifier = new HashMap<>(parent.byIdentifier);
            this.blockOverrides = new HashMap<>(parent.blockOverrides);
            this.customBlockItems = new HashSet<>(parent.customBlockItems);
            this.fallbacks = new HashMap<>(parent.fallbacks);
            this.attachments = parent.attachments.copy();
            parent.tags.forEach((tag, items) -> this.tags.put(tag, new LinkedHashSet<>(items)));
            this.customItems = new ArrayList<>(parent.customItems);
            this.nextRuntimeId = parent.nextRuntimeId;
        }

        private void put(PaletteItem item) {
            byRuntimeId.put(item.getRuntimeId(), item);
            byIdentifier.put(item.getIdentifier(), item);
            nextRuntimeId = Math.max(nextRuntimeId, item.getRuntimeId() + 1);
        }

        public @Nullable PaletteItem get(@NonNull String identifier) {
            return byIdentifier.get(identifier);
        }

        /**
         * @return false if the item does not exist in this version, so there was nothing to override
         */
        public boolean override(@NonNull String identifier, @NonNull Consumer<ItemOverride> modifier) {
            PaletteItem original = byIdentifier.get(identifier);
            if (original == null) {
                return false;
            }
            ItemOverride override = new ItemOverride(original);
            modifier.accept(override);
            if (override.changesDefinition()) {
                put(override.apply());
            }
            if (override.setsBlock()) {
                NbtMap block = override.block();
                blockOverrides.put(identifier, block == null ? NO_BLOCK : block);
            }
            return true;
        }

        public @NonNull PaletteItem addCustom(@NonNull CustomItem custom) {
            checkUnused(custom.identifier());
            int runtimeId = nextRuntimeId;
            NbtMap componentData = custom.components().toComponentData(custom.identifier(), runtimeId, custom.creative());
            PaletteItem item = new PaletteItem(custom.identifier(), runtimeId, ItemVersion.DATA_DRIVEN, true, componentData, true);
            put(item);
            customItems.add(custom);
            for (String tag : custom.components().tags()) {
                tags.computeIfAbsent(tag, t -> new LinkedHashSet<>()).add(custom.identifier());
            }
            return item;
        }

        /**
         * Registers the item of a custom block, unless the block has none.
         */
        public @Nullable PaletteItem addBlockItem(@NonNull CustomBlock block) {
            PaletteItem item;
            switch (block.item()) {
                case CustomBlock.BlockItem.None none -> {
                    return null;
                }
                case CustomBlock.BlockItem.Simple simple -> {
                    checkUnused(block.identifier());
                    item = new PaletteItem(block.identifier(), nextRuntimeId, ItemVersion.NONE, false, null, true);
                }
                case CustomBlock.BlockItem.DataDriven dataDriven -> {
                    checkUnused(block.identifier());
                    ItemComponents components = dataDriven.components();
                    // Without an icon of its own, the client renders the block
                    components = components.toBuilder()
                        .blockPlacer(block.identifier(), components.property("minecraft:icon") == null)
                        .build();
                    int runtimeId = nextRuntimeId;
                    item = new PaletteItem(block.identifier(), runtimeId, ItemVersion.DATA_DRIVEN, true,
                        components.toComponentData(block.identifier(), runtimeId, block.creative()), true);
                }
            }
            put(item);
            customBlockItems.add(block.identifier());
            return item;
        }

        private void checkUnused(String identifier) {
            if (byIdentifier.containsKey(identifier)) {
                throw new PaletteException("Item " + identifier + " already exists in " + version);
            }
        }

        public void fallback(@NonNull String identifier, @NonNull ItemFallback fallback) {
            fallbacks.put(identifier, fallback);
        }

        public void tag(@NonNull String tag, @NonNull List<String> identifiers) {
            tags.computeIfAbsent(tag, t -> new LinkedHashSet<>()).addAll(identifiers);
        }

        /**
         * Attaches a value. A variant whose item does not exist in this version is attached to its fallback instead,
         * as a weak value that never overrides or conflicts with direct attachments.
         *
         * @return false if the item does not exist and has no fallback
         */
        public boolean attach(@NonNull ItemVariant variant, @NonNull AttachmentKey<?> key, @NonNull Object value, @NonNull String source) {
            ItemFallback fallback = fallbacks.get(variant.identifier());
            if (byIdentifier.containsKey(variant.identifier()) && (fallback == null || !fallback.force())) {
                attachments.put(variant, key, value, false, source);
                return true;
            }
            if (fallback != null && byIdentifier.containsKey(fallback.target())) {
                attachments.put(ItemVariant.of(fallback.target()), key, value, true, source);
                return true;
            }
            return false;
        }

        public @NonNull ItemPalette build(@NonNull CreativeLayout layout, @NonNull PaletteReport report) {
            Map<String, Set<NbtMap>> creativeStates = new HashMap<>();
            Set<String> creativeWithoutBlock = new HashSet<>();
            layout.forEachStack(stack -> {
                if (stack.blockState() != null) {
                    creativeStates.computeIfAbsent(stack.identifier(), id -> new LinkedHashSet<>()).add(stack.blockState());
                } else {
                    creativeWithoutBlock.add(stack.identifier());
                }
            });

            Map<String, PaletteBlock> defaultBlocks = new HashMap<>();
            Set<String> multiState = new HashSet<>();
            for (PaletteItem item : byIdentifier.values()) {
                String identifier = item.getIdentifier();
                PaletteBlock block = null;
                NbtMap override = blockOverrides.get(identifier);
                Set<NbtMap> states = creativeStates.get(identifier);
                if (override != null) {
                    if (override != NO_BLOCK) {
                        block = blocks.resolve(override).block();
                        if (block == null) {
                            report.warn("items", "Block override " + override + " of " + identifier + " does not exist in " + version);
                        }
                    }
                } else if (states != null) {
                    block = blocks.resolve(states.iterator().next()).block();
                    if (states.size() > 1) {
                        multiState.add(identifier);
                    }
                } else if (!creativeWithoutBlock.contains(identifier) && blocks.contains(identifier)) {
                    // Not in the creative inventory: assume an item named like a block places it
                    block = blocks.defaultState(identifier);
                } else if (customBlockItems.contains(identifier)) {
                    block = blocks.defaultState(identifier);
                }
                if (block != null) {
                    defaultBlocks.put(identifier, block);
                }
            }
            return new ItemPalette(this, defaultBlocks, multiState);
        }
    }
}
