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
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.block.BlockPalette;
import org.geysermc.paletter.block.BlockResolution;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.PaletteBlock;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.creative.CreativeStack;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.data.VanillaItem;
import org.geysermc.paletter.internal.component.ItemCompiler;
import org.geysermc.paletter.internal.component.WireContext;
import org.geysermc.paletter.item.component.BlockPlacer;
import org.geysermc.paletter.item.component.ItemComponent;

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
 *     <li>{@link #item(int)} / {@link #resolve(ItemVariant)}: what to send for a canonical id or a variant (written
 *     against the newest Bedrock version);</li>
 *     <li>{@link #match(ItemData)}: which canonical id a stack the client sent is.</li>
 * </ul>
 * It also knows which block each block item is sent with, so consumers never have to work that out from the
 * creative inventory themselves.
 *
 * @since 1.0.0
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
    private final @Nullable ItemFallback defaultFallback;
    private final CanonicalItems canonical;
    private final Map<String, List<String>> tags;
    private final List<CustomItem> customItems;
    private final int nextRuntimeId;
    private final Map<ItemVariant, Optional<ResolvedItem>> resolutions = new ConcurrentHashMap<>();
    private volatile @Nullable CanonicalTable canonicalTable;

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
        this.defaultFallback = builder.defaultFallback;
        this.canonical = builder.canonical;
        Map<String, List<String>> tags = new HashMap<>();
        builder.tags.forEach((tag, items) -> tags.put(tag, List.copyOf(items)));
        this.tags = Collections.unmodifiableMap(tags);
        this.customItems = List.copyOf(builder.customItems);
        this.nextRuntimeId = builder.nextRuntimeId;
    }

    /**
     * Starts the item palette of a version from its vanilla items.
     */
    @Internal
    public static @NonNull Builder vanilla(@NonNull VanillaData data, @NonNull BlockPalette blocks, @NonNull CanonicalItems canonical) {
        Builder builder = new Builder(data.version(), blocks, data.fallbacks().items(), canonical);
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
     * @param canonical the canonical ids of the new palette: this palette's, or these with custom items appended
     */
    @Internal
    public @NonNull Builder extend(@NonNull BlockPalette blocks, @NonNull CanonicalItems canonical) {
        return new Builder(this, blocks, canonical);
    }

    public @NonNull DataVersion version() {
        return version;
    }

    public @NonNull BlockPalette blocks() {
        return blocks;
    }

    /**
     * @return the canonical item ids this palette resolves
     */
    public @NonNull CanonicalItems canonical() {
        return canonical;
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
        CanonicalTable table = canonicalTable;
        if (table != null) {
            int canonicalId = canonical.index(variant);
            if (canonicalId != -1) {
                // index() treats an explicit default block state like none: one canonical id, one resolution
                return table.items[canonicalId];
            }
        }
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

    // ---- Canonical ids ----

    /**
     * What to send for a canonical id: {@link #resolve(ItemVariant)} of its variant. An array lookup; the table is
     * built on first use.
     *
     * @return the resolved item, or null if the item does not exist in this version and has no fallback
     * @throws IndexOutOfBoundsException if the id is not one of {@link #canonical()}
     */
    public @Nullable ResolvedItem item(int canonical) {
        return canonicalTable().items[canonical];
    }

    /**
     * Identifies a stack the client sent: its runtime id is looked up in a hash table, then the few variants of that
     * item are told apart by damage and, for block items, the block definition. The block definitions clients send
     * with other items are ignored. Does not allocate.
     * <p>
     * Order: a variant with this damage and block, then a variant with this damage, then the item's base variant.
     * A variant that only reached this version through a fallback never shadows the item's own variants, and never
     * serves as a base variant. A stack that exists here only as the fallback of <em>several</em> canonical ids (e.g. the
     * shared {@code minecraft:unknown} of the default fallback) is not matched: any of them would be an arbitrary claim.
     * A fallback reached by exactly one canonical id still matches it.
     *
     * @return the canonical id, or -1 for air, an unknown definition, an item that is no variant of the newest version,
     *         or a stack shared by the fallbacks of several canonical ids
     */
    public int match(@NonNull ItemData data) {
        ItemDefinition definition = data.getDefinition();
        if (definition == null || definition == ItemDefinition.AIR) {
            return -1;
        }
        Candidate[] candidates = canonicalTable().byRuntimeId.get(definition.getRuntimeId());
        if (candidates == null) {
            return -1;
        }
        int damage = data.getDamage();
        BlockDefinition block = data.getBlockDefinition();
        boolean damageMatched = false;
        int damageMatch = -1;
        int base = -1;
        for (Candidate candidate : candidates) {
            if (candidate.block != null) {
                if (candidate.damage == damage && block != null && block.getRuntimeId() == candidate.block.getRuntimeId()) {
                    return candidate.canonical;
                }
            } else if (candidate.damage == damage) {
                if (!damageMatched) {
                    damageMatched = true;
                    damageMatch = candidate.canonical;
                }
            } else if (candidate.damage == 0 && base == -1 && !candidate.fallback) {
                base = candidate.canonical;
            }
        }
        return damageMatched ? damageMatch : base;
    }

    /**
     * @return the canonical variants that do not exist in this version and have no fallback of their own: they use the
     *         default fallback ({@link #defaultFallback()}), or {@link #item(int)} is null without one
     */
    public @NonNull List<ItemVariant> missingCanonical() {
        return canonicalTable().missing;
    }

    /**
     * @return what canonical variants without an item or fallback of their own use, or null if they have no item
     */
    @Internal
    public @Nullable ItemFallback defaultFallback() {
        return defaultFallback;
    }


    /**
     * Builds the canonical tables now instead of on first use. Called by the palette compiler.
     */
    public void prepareCanonical() {
        canonicalTable();
    }

    private CanonicalTable canonicalTable() {
        CanonicalTable result = canonicalTable;
        if (result == null) {
            synchronized (this) {
                result = canonicalTable;
                if (result == null) {
                    canonicalTable = result = buildCanonicalTable();
                }
            }
        }
        return result;
    }

    private CanonicalTable buildCanonicalTable() {
        int size = canonical.size();
        ResolvedItem[] items = new ResolvedItem[size];
        List<ItemVariant> missing = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            // Not through the resolve cache: the table holds the results already
            items[i] = resolveUncached(canonical.variant(i));
            if (items[i] == null) {
                missing.add(canonical.variant(i));
                PaletteItem fallback = defaultFallback == null ? null : byIdentifier.get(defaultFallback.target());
                if (fallback != null) {
                    items[i] = new ResolvedItem(fallback, 0, defaultBlocks.get(fallback.getIdentifier()), true);
                }
            }
        }
        Int2ObjectOpenHashMap<List<Candidate>> candidates = new Int2ObjectOpenHashMap<>();
        // Direct variants first, so a fallback never shadows an item's own variant
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < size; i++) {
                ResolvedItem item = items[i];
                if (item == null || item.fallback() != (pass == 1)) {
                    continue;
                }
                ItemVariant variant = canonical.variant(i);
                PaletteBlock block = variant.blockState() != null && item.block() != defaultBlocks.get(item.definition().getIdentifier())
                    ? item.block() : null;
                List<Candidate> list = candidates.computeIfAbsent(item.definition().getRuntimeId(), id -> new ArrayList<>(1));
                boolean fallback = pass == 1;
                int taken = -1;
                for (int c = 0; c < list.size(); c++) {
                    if (list.get(c).damage == item.damage() && list.get(c).block == block) {
                        taken = c;
                    }
                }
                if (taken == -1) {
                    list.add(new Candidate(item.damage(), block, i, fallback));
                } else if (fallback && list.get(taken).fallback) {
                    // Shared by the fallbacks of several canonical ids: claiming any of them would be arbitrary
                    list.set(taken, new Candidate(item.damage(), block, -1, true));
                }
            }
        }
        Int2ObjectOpenHashMap<Candidate[]> byRuntimeId = new Int2ObjectOpenHashMap<>(candidates.size());
        candidates.int2ObjectEntrySet().forEach(entry -> byRuntimeId.put(entry.getIntKey(), entry.getValue().toArray(Candidate[]::new)));
        byRuntimeId.trim();
        return new CanonicalTable(items, byRuntimeId, List.copyOf(missing));
    }

    private record Candidate(int damage, @Nullable PaletteBlock block, int canonical, boolean fallback) {
    }

    private record CanonicalTable(ResolvedItem[] items, Int2ObjectMap<Candidate[]> byRuntimeId, List<ItemVariant> missing) {
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
    @Internal
    public static final class Builder {
        private final DataVersion version;
        private final BlockPalette blocks;
        private final Int2ObjectMap<PaletteItem> byRuntimeId;
        private final Map<String, PaletteItem> byIdentifier;
        private final Map<String, NbtMap> blockOverrides;
        private final Set<String> customBlockItems;
        private final Map<String, ItemFallback> fallbacks;
        private @Nullable ItemFallback defaultFallback;
        private final CanonicalItems canonical;
        private final Map<String, Set<String>> tags = new LinkedHashMap<>();
        private final List<CustomItem> customItems;
        private int nextRuntimeId;

        private Builder(DataVersion version, BlockPalette blocks, Map<String, ItemFallback> fallbacks, CanonicalItems canonical) {
            this.version = version;
            this.blocks = blocks;
            this.byRuntimeId = new Int2ObjectOpenHashMap<>();
            this.byIdentifier = new HashMap<>();
            this.blockOverrides = new HashMap<>();
            this.customBlockItems = new HashSet<>();
            this.fallbacks = new HashMap<>(fallbacks);
            this.defaultFallback = ItemFallback.UNKNOWN;
            this.canonical = canonical;
            this.customItems = new ArrayList<>();
        }

        private Builder(ItemPalette parent, BlockPalette blocks, CanonicalItems canonical) {
            this.version = parent.version;
            this.blocks = blocks;
            this.byRuntimeId = new Int2ObjectOpenHashMap<>(parent.byRuntimeId);
            this.byIdentifier = new HashMap<>(parent.byIdentifier);
            this.blockOverrides = new HashMap<>(parent.blockOverrides);
            this.customBlockItems = new HashSet<>(parent.customBlockItems);
            this.fallbacks = new HashMap<>(parent.fallbacks);
            this.defaultFallback = parent.defaultFallback;
            this.canonical = canonical;
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
        public boolean override(@NonNull String identifier, @NonNull Consumer<ItemOverride> modifier, @NonNull PaletteReport report,
                                @NonNull String source) {
            PaletteItem original = byIdentifier.get(identifier);
            if (original == null) {
                return false;
            }
            ItemOverride override = new ItemOverride(original);
            modifier.accept(override);
            if (override.changesDefinition()) {
                put(override.apply(context(report, source)));
            }
            if (override.setsBlock()) {
                NbtMap block = override.block();
                blockOverrides.put(identifier, block == null ? NO_BLOCK : block);
            }
            return true;
        }

        public @NonNull PaletteItem addCustom(@NonNull CustomItem custom, @NonNull PaletteReport report, @NonNull String source) {
            checkUnused(custom.identifier());
            int runtimeId = nextRuntimeId;
            NbtMap componentData = ItemCompiler.componentData(custom.identifier(), runtimeId, custom.components(), custom.menuCategory(),
                custom.creative(), context(report, source));
            PaletteItem item = new PaletteItem(custom.identifier(), runtimeId, ItemVersion.DATA_DRIVEN, true, componentData, true);
            put(item);
            customItems.add(custom);
            for (String tag : custom.tags()) {
                tags.computeIfAbsent(tag, t -> new LinkedHashSet<>()).add(custom.identifier());
            }
            return item;
        }

        /**
         * @return the context components of this palette compile in: its version, and its items and blocks for descriptors
         */
        private WireContext context(PaletteReport report, String source) {
            return WireContext.of(version, report, source, new WireContext.Registry() {
                @Override
                public boolean itemExists(@NonNull String identifier) {
                    return byIdentifier.containsKey(identifier.indexOf(':') < 0 ? "minecraft:" + identifier : identifier);
                }

                @Override
                public @Nullable NbtMap blockStates(@NonNull String identifier) {
                    PaletteBlock block = blocks.defaultState(identifier.indexOf(':') < 0 ? "minecraft:" + identifier : identifier);
                    return block == null ? null : block.states();
                }
            });
        }

        /**
         * Registers the item of a custom block, unless the block has none.
         */
        public @Nullable PaletteItem addBlockItem(@NonNull CustomBlock block, @NonNull PaletteReport report, @NonNull String source) {
            return addBlockItem(block, block.item(), report, source);
        }

        /**
         * Registers an item for a custom block of this palette, e.g. one registered without item in an earlier layer.
         *
         * @return the item, or null for {@link CustomBlock.BlockItem#NONE}
         */
        public @Nullable PaletteItem addBlockItem(@NonNull CustomBlock block, CustomBlock.@NonNull BlockItem blockItem,
                                                  @NonNull PaletteReport report, @NonNull String source) {
            PaletteItem item;
            switch (blockItem) {
                case CustomBlock.BlockItem.None none -> {
                    return null;
                }
                case CustomBlock.BlockItem.Simple simple -> {
                    checkUnused(block.identifier());
                    item = new PaletteItem(block.identifier(), nextRuntimeId, ItemVersion.NONE, false, null, true);
                }
                case CustomBlock.BlockItem.DataDriven dataDriven -> {
                    checkUnused(block.identifier());
                    List<ItemComponent> components = new ArrayList<>(dataDriven.components());
                    if (components.stream().noneMatch(c -> c instanceof BlockPlacer)) {
                        components.add(BlockPlacer.builder().block(block.identifier()).build());
                    }
                    int runtimeId = nextRuntimeId;
                    item = new PaletteItem(block.identifier(), runtimeId, ItemVersion.DATA_DRIVEN, true,
                        ItemCompiler.componentData(block.identifier(), runtimeId, components, null, block.creative(),
                            context(report, source)), true);
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

        /**
         * @param fallback what canonical variants without an item or fallback of their own use, or null for none
         */
        @Internal
        public void defaultFallback(@Nullable ItemFallback fallback) {
            this.defaultFallback = fallback;
        }

        public void fallback(@NonNull String identifier, @NonNull ItemFallback fallback) {
            fallbacks.put(identifier, fallback);
        }

        public void tag(@NonNull String tag, @NonNull List<String> identifiers) {
            tags.computeIfAbsent(tag, t -> new LinkedHashSet<>()).addAll(identifiers);
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
