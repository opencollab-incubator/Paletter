package org.geysermc.paletter.item;

import org.geysermc.paletter.Origin;
import org.geysermc.paletter.internal.CanonicalItems;
import org.geysermc.paletter.internal.item.ItemInterner;
import org.geysermc.paletter.internal.item.ItemVariant;
import org.geysermc.paletter.internal.item.ResolvedItem;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
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
import org.geysermc.paletter.block.BlockRegistry;
import org.geysermc.paletter.internal.block.BlockResolution;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.data.VanillaItem;
import org.geysermc.paletter.internal.component.ItemCompiler;
import org.geysermc.paletter.internal.component.WireContext;
import org.geysermc.paletter.item.component.BlockPlacer;
import org.geysermc.paletter.item.component.ItemComponent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The compiled item palette of one {@link org.geysermc.paletter.Palette}. It is the item {@link DefinitionRegistry}
 * for the codec, and answers both directions of item translation:
 * <ul>
 *     <li>{@link #item(int)} / {@link #resolve(ItemVariant)}: what to send for a canonical id or a variant (written
 *     against the newest Bedrock version);</li>
 *     <li>{@link #match(ItemData)}: which canonical id a stack the client sent is;</li>
 *     <li>{@link #encode} and {@link #decode}: whole stacks, see {@link org.geysermc.paletter.Palette}.</li>
 * </ul>
 * It also knows which block each block item is sent with, so consumers never have to work that out from the
 * creative inventory themselves.
 *
 * @since 1.0.0
 */
public final class ItemRegistry implements DefinitionRegistry<ItemDefinition> {
    private static final NbtMap NO_BLOCK = NbtMap.builder().putString("name", "paletter:no_block").build();

    private final @Nullable ItemRegistry parent;
    private final DataVersion version;
    private final BlockRegistry blocks;
    /** The definitions this registry adds or overrides; all of them for a registry without parent. */
    private final Int2ObjectMap<RegisteredItem> byRuntimeId;
    private final Map<String, RegisteredItem> byIdentifier;
    private volatile @Nullable List<RegisteredItem> definitions;
    private final Map<String, NbtMap> blockOverrides;
    private final Set<String> customBlockItems;
    private final Map<String, Set<String>> itemBlocks;
    /** Default blocks that differ from the parent's (a null value: none any more); all of them without parent. */
    private final Map<String, RegisteredBlockState> defaultBlocks;
    private final Set<String> multiStateBlockItems;
    private final Map<String, ItemFallback> fallbacks;
    private final @Nullable ItemFallback defaultFallback;
    private final CanonicalItems canonical;
    /** The tags this registry changes, with all their items; all tags without parent. */
    private final Map<String, List<String>> tags;
    private volatile @Nullable Map<String, List<String>> mergedTags;
    private final List<CustomItem> customItems;
    private final int nextRuntimeId;
    private final Map<ItemVariant, Optional<ResolvedItem>> resolutions = new ConcurrentHashMap<>();
    private volatile @Nullable CanonicalTable canonicalTable;

    private ItemRegistry(Builder builder, Map<String, RegisteredBlockState> defaultBlocks, Set<String> multiStateBlockItems) {
        this.parent = builder.parent;
        this.version = builder.version;
        this.blocks = builder.blocks;
        this.byRuntimeId = builder.byRuntimeId;
        this.byIdentifier = builder.byIdentifier;
        this.blockOverrides = Map.copyOf(builder.blockOverrides);
        this.customBlockItems = Set.copyOf(builder.customBlockItems);
        this.itemBlocks = builder.itemBlocks;
        this.defaultBlocks = Collections.unmodifiableMap(defaultBlocks);
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
    public static @NonNull Builder vanilla(@NonNull VanillaData data, @NonNull BlockRegistry blocks, @NonNull CanonicalItems canonical) {
        Builder builder = new Builder(data.version(), blocks, data.fallbacks().items(), canonical);
        data.blockItems().forEach((block, item) -> builder.itemBlocks.computeIfAbsent(item, i -> new TreeSet<>()).add(block));
        for (VanillaItem item : data.items()) {
            ItemVersion itemVersion = ItemVersion.from(item.version());
            builder.put(ItemInterner.intern(item.identifier(), item.runtimeId(), itemVersion, item.componentBased(), item.componentData(),
                () -> new RegisteredItem(item.identifier(), item.runtimeId(), itemVersion, item.componentBased(), item.componentData(),
                    Origin.VANILLA, List.of(), null)));
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
    public @NonNull Builder extend(@NonNull BlockRegistry blocks, @NonNull CanonicalItems canonical) {
        return new Builder(this, blocks, canonical);
    }

    public @NonNull DataVersion version() {
        return version;
    }

    public @NonNull BlockRegistry blocks() {
        return blocks;
    }

    /**
     * @return the canonical item ids this palette resolves
     */
@Internal
    public @NonNull CanonicalItems canonical() {
        return canonical;
    }

    // ---- Definitions ----

    @Override
    public @Nullable RegisteredItem getDefinition(int runtimeId) {
        RegisteredItem item = byRuntimeId.get(runtimeId);
        return item != null || parent == null ? item : parent.getDefinition(runtimeId);
    }

    @Override
    public @Nullable RegisteredItem getDefinition(@NonNull String identifier) {
        RegisteredItem item = byIdentifier.get(identifier);
        return item != null || parent == null ? item : parent.getDefinition(identifier);
    }

    /**
     * @return the item with this identifier, or null; same as {@link #getDefinition(String)}
     */
    public @Nullable RegisteredItem item(@NonNull String identifier) {
        return getDefinition(identifier);
    }

    @Override
    public boolean isRegistered(@NonNull ItemDefinition definition) {
        return getDefinition(definition.getRuntimeId()) == definition;
    }

    /**
     * @return all definitions in runtime id order. This is what the item component packet carries
     */
    public @NonNull List<RegisteredItem> definitions() {
        List<RegisteredItem> result = definitions;
        if (result == null) {
            synchronized (this) {
                result = definitions;
                if (result == null) {
                    List<RegisteredItem> merged;
                    if (parent == null) {
                        merged = new ArrayList<>(byRuntimeId.values());
                    } else {
                        Int2ObjectMap<RegisteredItem> all = new Int2ObjectOpenHashMap<>();
                        for (RegisteredItem item : parent.definitions()) {
                            all.put(item.getRuntimeId(), item);
                        }
                        all.putAll(byRuntimeId);
                        merged = new ArrayList<>(all.values());
                    }
                    merged.sort(Comparator.comparingInt(RegisteredItem::getRuntimeId));
                    definitions = result = List.copyOf(merged);
                }
            }
        }
        return result;
    }

    /**
     * @return all items in runtime id order; same as {@link #definitions()}
     */
    public @NonNull Collection<RegisteredItem> items() {
        return definitions();
    }

    /**
     * @return how many definitions this registry holds itself: all of them for a shared palette, only the added and
     * overridden ones for a session palette extending another
     */
    @Internal
    public int ownSize() {
        return byRuntimeId.size();
    }

    private @Nullable RegisteredBlockState defaultBlockOf(String identifier) {
        ItemRegistry registry = this;
        while (registry != null) {
            if (registry.defaultBlocks.containsKey(identifier)) {
                return registry.defaultBlocks.get(identifier);
            }
            registry = registry.parent;
        }
        return null;
    }

    public @NonNull List<CustomItem> customItems() {
        return customItems;
    }

    public @NonNull ItemComponentPacket toPacket() {
        ItemComponentPacket packet = new ItemComponentPacket();
        packet.getItems().addAll(definitions());
        return packet;
    }

    /**
     * @return item tags, tag to item identifiers: vanilla tags plus tags of custom items and layers
     */
    public @NonNull Map<String, List<String>> tags() {
        if (parent == null) {
            return tags;
        }
        Map<String, List<String>> result = mergedTags;
        if (result == null) {
            Map<String, List<String>> merged = new HashMap<>(parent.tags());
            merged.putAll(tags);
            mergedTags = result = Collections.unmodifiableMap(merged);
        }
        return result;
    }

    // ---- Blocks of block items ----

    /**
     * The block an item variant is sent with in this version: the single owner of that decision. For an item that
     * places blocks, a block state of the variant wins (adapted to this version), otherwise the item's block, which is
     * decided in this order:
     * <ol>
     *     <li>an override of a layer ({@code PaletteLayer.Builder#blockItem});</li>
     *     <li>the bundled block to item relation of the data ({@code VanillaData#blockItems}): the block whose first
     *     creative stack the item has, else the block named like the item, else the first block that places it
     *     (sorted by identifier), in its default state;</li>
     *     <li>a block state of the item's creative stacks (items the relation does not know);</li>
     *     <li>for data without the relation: a block named like the item, in its default state;</li>
     *     <li>the block of a custom block item.</li>
     * </ol>
     *
     * @param variant a variant, written against the newest Bedrock version like {@link #resolve(ItemVariant)}
     * @return the block, or null if the item is not a block item here (or does not exist and has no fallback)
     * @since 1.0.0
     */
    public @Nullable RegisteredBlockState blockOf(@NonNull BedrockItemVariant variant) {
        ResolvedItem resolved = resolve(variant.toItemVariant());
        return resolved == null ? null : resolved.block();
    }

    /**
     * What this version sends instead of a variant: its fallback, that is the replacement of a {@link ItemFallback} (also
     * a forced one), or the default fallback ({@code minecraft:unknown}) for a variant the version lacks. A lookup in the
     * canonical table, cheap enough to ask for every item at startup (unlike {@code Palette#explain}).
     *
     * @param variant a variant, written against the newest Bedrock version
     * @return the variant that is sent in its place (the aux of the fallback's variant, else the aux of the replaced
     *         item), or null if the variant is sent as itself, or nothing is sent for it at all (see
     *         {@link #isFallback})
     * @since 1.0.0
     */
    public @Nullable BedrockItemVariant fallbackOf(@NonNull BedrockItemVariant variant) {
        int id = variant.canonicalIn(canonical);
        ResolvedItem resolved = id != -1 ? item(id) : resolve(variant.toItemVariant());
        if (resolved == null || !resolved.fallback()) {
            return null;
        }
        return BedrockItemType.of(resolved.definition().getIdentifier()).variantOf(resolved.damage());
    }

    /**
     * @return true if this version sends the variant as another item, see {@link #fallbackOf}; false if it is sent as
     *         itself, and for a variant nothing is sent for
     * @since 1.0.0
     */
    public boolean isFallback(@NonNull BedrockItemVariant variant) {
        int id = variant.canonicalIn(canonical);
        ResolvedItem resolved = id != -1 ? item(id) : resolve(variant.toItemVariant());
        return resolved != null && resolved.fallback();
    }

    /**
     * @return the block this item is sent with by default, or null if it is not a block item; see {@link #blockOf}
     */
    public @Nullable RegisteredBlockState defaultBlock(@NonNull ItemDefinition definition) {
        return defaultBlockOf(definition.getIdentifier());
    }

    public boolean isBlockItem(@NonNull ItemDefinition definition) {
        return defaultBlockOf(definition.getIdentifier()) != null;
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
    @Internal
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
        RegisteredItem item = forced != null && forced.force() ? null : getDefinition(variant.identifier());
        boolean fallback = false;
        int damage = variant.damage();
        if (item == null) {
            ItemFallback target = fallbacks.get(variant.identifier());
            item = target == null ? null : getDefinition(target.target().identifier());
            if (item == null) {
                return null;
            }
            if (target.variant() != null) {
                damage = target.variant().aux();
            }
            fallback = true;
        }
        RegisteredBlockState block = defaultBlockOf(item.getIdentifier());
        if (variant.blockState() != null && !fallback && block != null) {
            BlockResolution resolution = blocks.resolve(variant.blockState());
            if (resolution.block() != null) {
                block = resolution.block();
            }
        }
        return new ResolvedItem(item, damage, block, fallback);
    }

    // ---- Canonical ids ----

    /**
     * What to send for a canonical id: {@link #resolve(ItemVariant)} of its variant. An array lookup; the table is
     * built on first use.
     *
     * @return the resolved item, or null if the item does not exist in this version and has no fallback
     * @throws IndexOutOfBoundsException if the id is not one of {@link #canonical()}
     */
@Internal
    public @Nullable ResolvedItem item(int canonical) {
        return canonicalTable().items[canonical];
    }

    /**
     * Identifies a stack the client sent: its runtime id is looked up in a hash table, then the few variants of that
     * item are told apart by damage. Does not allocate.
     * <p>
     * Order: a variant with this damage, then the item's base variant.
     * A variant that only reached this version through a fallback never shadows the item's own variants, and never
     * serves as a base variant. A stack that exists here only as the fallback of <em>several</em> canonical ids (e.g. the
     * shared {@code minecraft:unknown} of the default fallback) is not matched: any of them would be an arbitrary claim.
     * A fallback reached by exactly one canonical id still matches it.
     *
     * @return the canonical id, or -1 for air, an unknown definition, an item that is no variant of the newest version,
     *         or a stack shared by the fallbacks of several canonical ids
     */
@Internal
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
        boolean damageMatched = false;
        int damageMatch = -1;
        int base = -1;
        for (Candidate candidate : candidates) {
            if (candidate.damage == damage) {
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
     * What to send for a stack: its item resolved for this version (fallbacks applied), the aux value, count and NBT,
     * and the block the stack places: its own block state if it has one and the item places blocks here, else the item's
     * default block.
     *
     * @return the item data, or {@link ItemData#AIR} if the item does not exist in this version and has no fallback
     */
    @Internal
    public @NonNull ItemData encode(@NonNull BedrockItemStack stack) {
        ResolvedItem resolved = resolve(stack.variant().toItemVariant());
        if (resolved == null) {
            return ItemData.AIR;
        }
        RegisteredBlockState block = resolved.block();
        if (stack.blockState() != null && !resolved.fallback() && block != null) {
            RegisteredBlockState own = blocks.resolve(stack.blockState().toNbt()).block();
            if (own != null) {
                block = own;
            }
        }
        return ItemData.builder()
            .definition(resolved.definition())
            .damage(resolved.damage())
            .count(stack.count())
            .tag(stack.nbt())
            .blockDefinition(block)
            .build();
    }

    /**
     * Identifies a stack the client sent, see {@link #match(ItemData)}. The block state is set only if the stack's
     * block differs from the item's default block.
     *
     * @return the stack, or null for air or a stack that is no variant of the newest version
     */
    @Internal
    public @Nullable BedrockItemStack decode(@NonNull ItemData data) {
        int canonicalId = match(data);
        if (canonicalId == -1) {
            return null;
        }
        BedrockBlockState state = null;
        BlockDefinition block = data.getBlockDefinition();
        if (block != null) {
            RegisteredBlockState expected = item(canonicalId).block();
            int blockId = blocks.canonical(block);
            if (blockId != -1 && (expected == null || expected.getRuntimeId() != block.getRuntimeId())) {
                state = blocks.canonical().handle(blockId);
            }
        }
        return new BedrockItemStack(canonical.handle(canonicalId), Math.max(1, data.getCount()), data.getTag(), state);
    }

    /**
     * @return the canonical variants that do not exist in this version and have no fallback of their own: they use the
     *         default fallback ({@link #defaultFallback()}), or {@link #item(int)} is null without one
     */
@Internal
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
@Internal
    public void prepareCanonical() {
        canonicalTable();
    }

    private CanonicalTable canonicalTable() {
        CanonicalTable result = canonicalTable;
        if (result == null) {
            synchronized (this) {
                result = canonicalTable;
                if (result == null) {
                    canonicalTable = result = buildCanonicalTable(null);
                }
            }
        }
        return result;
    }

    /**
     * @param base the parent's table when its entries are still valid here: the entries below its size are reused,
     *             only the canonical ids added since are built
     */
    private CanonicalTable buildCanonicalTable(@Nullable CanonicalTable base) {
        int size = canonical.size();
        int from = base == null ? 0 : base.items.length;
        ResolvedItem[] items = base == null ? new ResolvedItem[size] : Arrays.copyOf(base.items, size);
        List<ItemVariant> missing = new ArrayList<>(base == null ? List.of() : base.missing);
        Set<String> unresolved = new HashSet<>(base == null ? Set.of() : base.unresolved);
        for (int i = from; i < size; i++) {
            ItemVariant variant = canonical.variant(i);
            // Not through the resolve cache: the table holds the results already
            items[i] = resolveUncached(variant);
            if (items[i] == null) {
                missing.add(variant);
                RegisteredItem fallback = defaultFallback == null ? null : getDefinition(defaultFallback.target().identifier());
                if (fallback != null) {
                    items[i] = new ResolvedItem(fallback, defaultFallback.variant() == null ? 0 : defaultFallback.variant().aux(), defaultBlockOf(fallback.getIdentifier()), true);
                }
            }
            if (items[i] == null || items[i].fallback()) {
                // What could change this entry if an item of that identifier were added
                unresolved.add(variant.identifier());
                ItemFallback target = fallbacks.get(variant.identifier());
                if (target != null) {
                    unresolved.add(target.target().identifier());
                }
                if (defaultFallback != null) {
                    unresolved.add(defaultFallback.target().identifier());
                }
            }
        }
        Int2ObjectMap<IntList> indices = base == null ? new Int2ObjectOpenHashMap<>() : new Int2ObjectOpenHashMap<>(base.indices);
        Int2ObjectMap<Candidate[]> byRuntimeId = base == null ? new Int2ObjectOpenHashMap<>() : new Int2ObjectOpenHashMap<>(base.byRuntimeId);
        Set<Integer> touched = new HashSet<>();
        for (int i = from; i < size; i++) {
            if (items[i] == null) {
                continue;
            }
            int runtimeId = items[i].definition().getRuntimeId();
            IntList list = indices.get(runtimeId);
            if (touched.add(runtimeId)) {
                // Copy before writing: the parent's lists are shared
                list = list == null ? new IntArrayList(1) : new IntArrayList(list);
                indices.put(runtimeId, list);
            }
            list.add(i);
        }
        if (base == null) {
            for (Int2ObjectMap.Entry<IntList> entry : indices.int2ObjectEntrySet()) {
                byRuntimeId.put(entry.getIntKey(), candidates(entry.getValue(), items));
            }
            ((Int2ObjectOpenHashMap<Candidate[]>) byRuntimeId).trim();
        } else {
            for (int runtimeId : touched) {
                byRuntimeId.put(runtimeId, candidates(indices.get(runtimeId), items));
            }
        }
        return new CanonicalTable(items, byRuntimeId, indices, List.copyOf(missing), Set.copyOf(unresolved));
    }

    /** The candidates of one runtime id: direct variants first, so a fallback never shadows an item's own variant. */
    private Candidate[] candidates(IntList indices, ResolvedItem[] items) {
        List<Candidate> list = new ArrayList<>(1);
        for (int pass = 0; pass < 2; pass++) {
            boolean fallback = pass == 1;
            for (int n = 0; n < indices.size(); n++) {
                int i = indices.getInt(n);
                ResolvedItem item = items[i];
                if (item.fallback() != fallback) {
                    continue;
                }
                int taken = -1;
                for (int c = 0; c < list.size(); c++) {
                    if (list.get(c).damage == item.damage()) {
                        taken = c;
                    }
                }
                if (taken == -1) {
                    list.add(new Candidate(item.damage(), i, fallback));
                } else if (fallback && list.get(taken).fallback) {
                    // Shared by the fallbacks of several canonical ids: claiming any of them would be arbitrary
                    list.set(taken, new Candidate(item.damage(), -1, true));
                }
            }
        }
        return list.toArray(Candidate[]::new);
    }

    private record Candidate(int damage, int canonical, boolean fallback) {
    }

    private record CanonicalTable(ResolvedItem[] items, Int2ObjectMap<Candidate[]> byRuntimeId, Int2ObjectMap<IntList> indices,
                                  List<ItemVariant> missing, Set<String> unresolved) {
    }

    /**
     * Builds a creative stack for this version: the item itself, never a fallback.
     *
     * @return the item data, or null if the item does not exist in this version
     */
    public @Nullable ItemData toItemData(@NonNull BedrockItemStack stack) {
        ResolvedItem resolved = resolve(stack.variant().toItemVariant());
        if (resolved == null || resolved.fallback()) {
            return null;
        }
        return encode(stack);
    }

    @Override
    public String toString() {
        return "ItemRegistry{" + version + ", " + definitions().size() + " items, " + customItems.size() + " custom}";
    }

    /**
     * Builds an item palette. Obtained from {@link #vanilla} or {@link #extend}; used by the palette compiler.
     */
    @Internal
    public static final class Builder {
        private final @Nullable ItemRegistry parent;
        private final DataVersion version;
        private final BlockRegistry blocks;
        private final Int2ObjectMap<RegisteredItem> byRuntimeId;
        private final Map<String, RegisteredItem> byIdentifier;
        private final Map<String, NbtMap> blockOverrides;
        private final Set<String> customBlockItems;
        /** The bundled block to item relation, inverted: item to the blocks that it places, sorted. Empty for data without it. */
        private final Map<String, Set<String>> itemBlocks;
        private final Map<String, ItemFallback> fallbacks;
        private @Nullable ItemFallback defaultFallback;
        private final CanonicalItems canonical;
        private final Map<String, Set<String>> tags = new LinkedHashMap<>();
        private final List<CustomItem> customItems;
        private int nextRuntimeId;
        /** Identifiers the layer is about to register: they exist for components compiled before their registration. */
        private final Set<String> declared = new HashSet<>();

        private Builder(DataVersion version, BlockRegistry blocks, Map<String, ItemFallback> fallbacks, CanonicalItems canonical) {
            this.parent = null;
            this.version = version;
            this.blocks = blocks;
            this.byRuntimeId = new Int2ObjectOpenHashMap<>();
            this.byIdentifier = new HashMap<>();
            this.blockOverrides = new HashMap<>();
            this.customBlockItems = new HashSet<>();
            this.itemBlocks = new HashMap<>();
            this.fallbacks = new HashMap<>(fallbacks);
            this.defaultFallback = ItemFallback.UNKNOWN;
            this.canonical = canonical;
            this.customItems = new ArrayList<>();
        }

        private Builder(ItemRegistry parent, BlockRegistry blocks, CanonicalItems canonical) {
            this.parent = parent;
            this.version = parent.version;
            this.blocks = blocks;
            this.byRuntimeId = new Int2ObjectOpenHashMap<>();
            this.byIdentifier = new HashMap<>();
            this.blockOverrides = new HashMap<>(parent.blockOverrides);
            this.customBlockItems = new HashSet<>(parent.customBlockItems);
            this.itemBlocks = parent.itemBlocks;
            this.fallbacks = new HashMap<>(parent.fallbacks);
            this.defaultFallback = parent.defaultFallback;
            this.canonical = canonical;
            this.customItems = new ArrayList<>(parent.customItems);
            this.nextRuntimeId = parent.nextRuntimeId;
        }

        private void put(RegisteredItem item) {
            byRuntimeId.put(item.getRuntimeId(), item);
            byIdentifier.put(item.getIdentifier(), item);
            nextRuntimeId = Math.max(nextRuntimeId, item.getRuntimeId() + 1);
        }

        /**
         * Announces an item the layer is about to register, so components compiled earlier can refer to it
         * (registration order must not decide whether an item exists).
         */
        public void declare(@NonNull String identifier) {
            declared.add(identifier);
        }

        public @Nullable RegisteredItem get(@NonNull String identifier) {
            RegisteredItem item = byIdentifier.get(identifier);
            return item != null || parent == null ? item : parent.getDefinition(identifier);
        }

        /** The items of a tag so far: the parent's, plus what this builder added. */
        private Set<String> tagItems(String tag) {
            return tags.computeIfAbsent(tag, t -> new LinkedHashSet<>(parent == null ? List.of() : parent.tags().getOrDefault(t, List.of())));
        }

        /**
         * @return false if the item does not exist in this version, so there was nothing to override
         */
        public boolean override(@NonNull String identifier, @NonNull Consumer<ItemOverride> modifier, @NonNull PaletteReport report,
                                @NonNull String source) {
            RegisteredItem original = get(identifier);
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

        public @NonNull RegisteredItem addCustom(@NonNull CustomItem custom, @NonNull PaletteReport report, @NonNull String source) {
            checkUnused(custom.identifier());
            int runtimeId = nextRuntimeId;
            NbtMap componentData = ItemCompiler.componentData(custom.identifier(), runtimeId, custom.components(), custom.menuCategory(),
                custom.creative(), context(report, source));
            RegisteredItem item = new RegisteredItem(custom.identifier(), runtimeId, ItemVersion.DATA_DRIVEN, true, componentData,
                Origin.custom(source), custom.components(), custom);
            put(item);
            customItems.add(custom);
            for (String tag : custom.tags()) {
                tagItems(tag).add(custom.identifier());
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
                    String name = identifier.indexOf(':') < 0 ? "minecraft:" + identifier : identifier;
                    return get(name) != null || declared.contains(name);
                }

                @Override
                public @Nullable NbtMap blockStates(@NonNull String identifier) {
                    RegisteredBlockState block = blocks.defaultState(identifier.indexOf(':') < 0 ? "minecraft:" + identifier : identifier);
                    return block == null ? null : block.states();
                }
            });
        }

        /**
         * Registers the item of a custom block, unless the block has none.
         */
        public @Nullable RegisteredItem addBlockItem(@NonNull CustomBlock block, @NonNull PaletteReport report, @NonNull String source) {
            return addBlockItem(block, block.item(), report, source);
        }

        /**
         * Registers an item for a custom block of this palette, e.g. one registered without item in an earlier layer.
         *
         * @return the item, or null for {@link CustomBlock.BlockItem#NONE}
         */
        public @Nullable RegisteredItem addBlockItem(@NonNull CustomBlock block, CustomBlock.@NonNull BlockItem blockItem,
                                                  @NonNull PaletteReport report, @NonNull String source) {
            RegisteredItem item;
            switch (blockItem) {
                case CustomBlock.BlockItem.None none -> {
                    return null;
                }
                case CustomBlock.BlockItem.Simple simple -> {
                    checkUnused(block.identifier());
                    item = new RegisteredItem(block.identifier(), nextRuntimeId, ItemVersion.NONE, false, null, Origin.custom(source), List.of(), null);
                }
                case CustomBlock.BlockItem.DataDriven dataDriven -> {
                    checkUnused(block.identifier());
                    List<ItemComponent> components = new ArrayList<>(dataDriven.components());
                    if (components.stream().noneMatch(c -> c instanceof BlockPlacer)) {
                        components.add(BlockPlacer.builder().block(block.identifier()).build());
                    }
                    int runtimeId = nextRuntimeId;
                    item = new RegisteredItem(block.identifier(), runtimeId, ItemVersion.DATA_DRIVEN, true,
                        ItemCompiler.componentData(block.identifier(), runtimeId, components, null, block.creative(),
                            context(report, source)), Origin.custom(source), components, null);
                }
            }
            put(item);
            customBlockItems.add(block.identifier());
            return item;
        }

        private void checkUnused(String identifier) {
            if (get(identifier) != null) {
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
            tagItems(tag).addAll(identifiers);
        }

        public @NonNull ItemRegistry build(@NonNull CreativeLayout layout, @NonNull PaletteReport report) {
            Map<String, Set<NbtMap>> creativeStates = new HashMap<>();
            Set<String> creativeWithoutBlock = new HashSet<>();
            layout.forEachStack(stack -> {
                String identifier = stack.type().identifier();
                if (stack.blockState() != null) {
                    creativeStates.computeIfAbsent(identifier, id -> new LinkedHashSet<>()).add(stack.blockState().toNbt());
                } else {
                    creativeWithoutBlock.add(identifier);
                }
            });

            Map<String, RegisteredBlockState> defaultBlocks = new HashMap<>();
            Set<String> multiState = new HashSet<>();
            Collection<RegisteredItem> all = byIdentifier.values();
            if (parent != null) {
                List<RegisteredItem> merged = new ArrayList<>();
                for (RegisteredItem item : parent.definitions()) {
                    if (!byIdentifier.containsKey(item.getIdentifier())) {
                        merged.add(item);
                    }
                }
                merged.addAll(byIdentifier.values());
                all = merged;
            }
            for (RegisteredItem item : all) {
                String identifier = item.getIdentifier();
                RegisteredBlockState block = null;
                NbtMap override = blockOverrides.get(identifier);
                Set<NbtMap> states = creativeStates.get(identifier);
                Set<String> placed = itemBlocks.get(identifier);
                if (override != null) {
                    if (override != NO_BLOCK) {
                        block = blocks.resolve(override).block();
                        if (block == null) {
                            report.warn("items", "Block override " + override + " of " + identifier + " does not exist in " + version);
                        }
                    }
                } else if (placed != null) {
                    block = bundledBlock(identifier, placed, states);
                    if (states != null && states.size() > 1) {
                        multiState.add(identifier);
                    }
                } else if (states != null) {
                    block = blocks.resolve(states.iterator().next()).block();
                    if (states.size() > 1) {
                        multiState.add(identifier);
                    }
                } else if (itemBlocks.isEmpty() && !creativeWithoutBlock.contains(identifier) && blocks.contains(identifier)) {
                    // Not in the creative inventory: assume an item named like a block places it
                    block = blocks.defaultState(identifier);
                } else if (customBlockItems.contains(identifier)) {
                    block = blocks.defaultState(identifier);
                }
                if (parent == null) {
                    if (block != null) {
                        defaultBlocks.put(identifier, block);
                    }
                } else if (block != parent.defaultBlockOf(identifier)) {
                    defaultBlocks.put(identifier, block);
                }
            }
            boolean reuse = parent != null && canReuseTable(defaultBlocks);
            ItemRegistry built = new ItemRegistry(this, defaultBlocks, multiState);
            if (reuse) {
                built.canonicalTable = built.buildCanonicalTable(parent.canonicalTable());
            }
            return built;
        }

        /**
         * @return true if the parent's canonical table is still right for its ids: nothing it resolves changed
         */
        private boolean canReuseTable(Map<String, RegisteredBlockState> changedBlocks) {
            if (blocks != parent.blocks || !canonical.extendsOrIs(parent.canonical)
                || !fallbacks.equals(parent.fallbacks) || !Objects.equals(defaultFallback, parent.defaultFallback)) {
                return false;
            }
            for (String identifier : changedBlocks.keySet()) {
                if (parent.getDefinition(identifier) != null) {
                    return false;
                }
            }
            Set<String> unresolved = parent.canonicalTable().unresolved;
            for (RegisteredItem item : byIdentifier.values()) {
                if (parent.getDefinition(item.getRuntimeId()) != null || parent.getDefinition(item.getIdentifier()) != null
                    || unresolved.contains(item.getIdentifier())) {
                    return false;
                }
            }
            return true;
        }

        private @Nullable RegisteredBlockState bundledBlock(String identifier, Set<String> placed, @Nullable Set<NbtMap> creativeStates) {
            if (creativeStates != null) {
                NbtMap first = creativeStates.iterator().next();
                if (placed.contains(BlockStates.name(first))) {
                    RegisteredBlockState block = blocks.resolve(first).block();
                    if (block != null) {
                        return block;
                    }
                }
            }
            String name = placed.contains(identifier) ? identifier : placed.iterator().next();
            return blocks.defaultState(name);
        }
    }
}
