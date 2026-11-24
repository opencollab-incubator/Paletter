package dev.opencollab.paletter.item;

import dev.opencollab.paletter.Origin;
import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.internal.behaviour.Behaviour;
import dev.opencollab.paletter.internal.CanonicalItems;
import dev.opencollab.paletter.internal.Lazy;
import dev.opencollab.paletter.internal.item.ItemInterner;
import dev.opencollab.paletter.internal.item.ItemVariant;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenCustomHashMap;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import dev.opencollab.paletter.PaletteException;
import dev.opencollab.paletter.internal.PaletteReportBuilder;
import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.block.BlockRegistry;
import dev.opencollab.paletter.internal.block.BlockResolution;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.creative.CreativeLayout;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import dev.opencollab.paletter.vanilla.ItemKey;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.data.VanillaItem;
import dev.opencollab.paletter.internal.component.ComponentIndex;
import dev.opencollab.paletter.internal.component.ItemCompiler;
import dev.opencollab.paletter.internal.component.WireContext;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * The compiled item palette of one {@link dev.opencollab.paletter.Palette}. It is the item {@link DefinitionRegistry}
 * for the codec, and answers both directions of item translation:
 * <ul>
 *     <li>{@link #item(int)} / {@link #resolve(ItemVariant)}: what to send for a canonical id or a variant (written
 *     against the newest Bedrock version);</li>
 *     <li>{@link #match(ItemData)}: which canonical id a stack the client sent is;</li>
 *     <li>{@link #encode} and {@link #decode}: whole stacks, see {@link dev.opencollab.paletter.Palette}.</li>
 * </ul>
 * It also knows which block each block item is sent with, so consumers never have to work that out from the
 * creative inventory themselves.
 *
 * @since 1.0.0
 */
@Internal
public final class ItemRegistry implements DefinitionRegistry<ItemDefinition> {
    private static final NbtMap NO_BLOCK = NbtMap.builder().putString("name", "paletter:no_block").build();

    private final @Nullable ItemRegistry parent;
    private final DataVersion version;
    private final BlockRegistry blocks;
    /** The definitions this registry adds or overrides; all of them for a registry without parent. */
    private final Int2ObjectMap<RegisteredItem> byRuntimeId;
    private final Map<String, RegisteredItem> byIdentifier;
    private final Lazy<List<RegisteredItem>> definitions;
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
    /** Of a registry with a parent only: the parent's tags with this registry's changes. */
    private final Lazy<Map<String, List<String>>> mergedTags;
    private final List<CustomItem> customItems;
    private final int nextRuntimeId;
    /** Runtime ids of the parent's items that this registry renumbered: the parent's item with such an id no longer exists here. */
    private final IntSet renumbered;
    /**
     * Of the registries a Paletter compiles ({@link #loadEagerly}): every definition ({@link #definitions()}, this
     * registry's and its parents') at {@code runtimeId - flatBase}, else null. Written once; {@link #flatBase} before it.
     */
    private volatile RegisteredItem @Nullable [] flat;
    private int flatBase;
    private final Lazy<CanonicalTable> canonicalTable;
    private volatile @Nullable ResolvedItem defaultResolved;
    /** The resolutions of variants of constants the bundled release lacks and this registry too, made on first use. */
    private final Lazy<Map<BedrockItemVariant, Optional<ResolvedItem>>> unavailableItems = new Lazy<>(ConcurrentHashMap::new);
    /** The palette that owns this registry, for the behaviour of what it resolves; null until the palette is made. */
    private volatile @Nullable Palette owner;

    /**
     * Internal: binds the palette that owns this registry, unless one is bound: a palette that shares the registry of
     * another (a copy with a report) has the same values. Called by the palette when it is made.
     *
     * @param palette the palette that owns this registry
     */
    @Internal
    public synchronized void bind(@NonNull Palette palette) {
        if (owner == null) {
            owner = palette;
        }
    }

    /**
     * Internal: returns the behaviour of the palette that owns this registry.
     *
     * @return the behaviour of the palette that owns this registry
     * @throws IllegalStateException if no palette owns it yet, which what a palette resolves never sees
     */
    @Internal
    public @NonNull Behaviour behaviour() {
        Palette palette = owner;
        if (palette == null) {
            throw new IllegalStateException("no palette owns this item registry");
        }
        return palette.behaviour();
    }

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
        this.renumbered = builder.renumbered;
        this.definitions = new Lazy<>(this::mergeDefinitions);
        this.mergedTags = new Lazy<>(() -> {
            Map<String, List<String>> merged = new HashMap<>(parent.tags());
            merged.putAll(this.tags);
            return Collections.unmodifiableMap(merged);
        });
        if (parent == null) {
            // Nothing to merge: the own tags are all
            mergedTags.preset(this.tags);
        }
        this.canonicalTable = new Lazy<>(() -> buildCanonicalTable(null));
    }

    /**
     * Starts the item palette of a version from its vanilla items.
     *
     * @param data the bundled data of the version
     * @param gated items of the version behind a disabled experiment toggle (not in {@code data}): registered with their
     *              runtime ids, as BDS registers them in a world without the toggle, but not by identifier, so they
     *              resolve like items the version lacks (through the fallbacks)
     * @param blocks the block palette of the version
     * @param canonical the canonical item ids the palette resolves
     * @return a builder holding the vanilla items, fallbacks and tags of the version
     */
    @Internal
    public static @NonNull Builder vanilla(@NonNull VanillaData data, @NonNull List<VanillaItem> gated, @NonNull BlockRegistry blocks,
                                           @NonNull CanonicalItems canonical) {
        Builder builder = new Builder(data.version(), blocks, data.fallbacks().items(), canonical);
        data.blockItems().forEach((block, item) -> builder.itemBlocks.computeIfAbsent(item, i -> new TreeSet<>()).add(block));
        // BDS sends an item with a block only if the item has a block of its own: not doors, signs, beds, seeds, redstone,
        // hoppers, campfires, ..., although they place one. The captured creative stacks show which: none of such an item's
        // stacks carries a block (the crafting results of the captures agree). Such an item places blocks, but is sent without one
        Set<String> withBlock = new HashSet<>();
        Set<String> withoutBlock = new HashSet<>();
        data.creative().forEachStack(stack -> (stack.blockState() != null ? withBlock : withoutBlock).add(stack.identifier()));
        // The stacks a toggle adds are not in the data's creative inventory: their capture with the toggle says which carry none
        data.experiments().values().forEach(content -> withoutBlock.addAll(content.creativeWithoutBlock()));
        withoutBlock.removeAll(withBlock);
        withoutBlock.forEach(item -> builder.itemBlocks.computeIfPresent(item, (i, placed) -> Set.of()));
        for (VanillaItem item : data.items()) {
            builder.put(registered(item));
        }
        for (VanillaItem item : gated) {
            builder.putGated(registered(item));
        }
        data.itemTags().forEach((tag, items) -> builder.tags.put(tag, new LinkedHashSet<>(items)));
        return builder;
    }

    private static RegisteredItem registered(VanillaItem item) {
        ItemVersion itemVersion = ItemVersion.from(item.version());
        return ItemInterner.intern(item.identifier(), item.runtimeId(), itemVersion, item.componentBased(), item.componentData(),
            () -> new RegisteredItem(item.identifier(), item.runtimeId(), itemVersion, item.componentBased(), item.componentData(),
                Origin.VANILLA, List.of(), null, null));
    }

    /**
     * Starts a palette extending this one. Unchanged definitions are shared, so they stay {@code ==} across palettes.
     *
     * @param blocks the block palette of the new palette; may differ from this one's if custom blocks were added
     * @param canonical the canonical ids of the new palette: this palette's, or these with custom items appended
     * @return a builder that starts from the items of this registry
     */
    @Internal
    public @NonNull Builder extend(@NonNull BlockRegistry blocks, @NonNull CanonicalItems canonical) {
        return new Builder(this, blocks, canonical);
    }

    /**
     * Internal: returns the data version of the registry.
     *
     * @return the data version
     */
    public @NonNull DataVersion version() {
        return version;
    }

    /**
     * Internal: returns the block registry the items are resolved against.
     *
     * @return the block registry
     */
    public @NonNull BlockRegistry blocks() {
        return blocks;
    }

    /**
     * Internal: returns the canonical item ids of the palette.
     *
     * @return the canonical item ids this palette resolves
     */
    @Internal
    public @NonNull CanonicalItems canonical() {
        return canonical;
    }

    // ---- Definitions ----

    @Override
    public @Nullable RegisteredItem getDefinition(int runtimeId) {
        RegisteredItem[] table = flat;
        if (table != null) {
            int slot = runtimeId - flatBase;
            return slot >= 0 && slot < table.length ? table[slot] : null;
        }
        RegisteredItem item = byRuntimeId.get(runtimeId);
        return item != null || parent == null || renumbered.contains(runtimeId) ? item : parent.getDefinition(runtimeId);
    }

    @Override
    public @Nullable RegisteredItem getDefinition(@NonNull String identifier) {
        RegisteredItem item = byIdentifier.get(identifier);
        return item != null || parent == null ? item : parent.getDefinition(identifier);
    }

    @Override
    public boolean isRegistered(@NonNull ItemDefinition definition) {
        return getDefinition(definition.getRuntimeId()) == definition;
    }

    /**
     * Internal: lists the item definitions.
     *
     * @return all definitions in runtime id order. This is what the item component packet carries
     */
    public @NonNull List<RegisteredItem> definitions() {
        return definitions.get();
    }

    private List<RegisteredItem> mergeDefinitions() {
        List<RegisteredItem> merged;
        if (parent == null) {
            merged = new ArrayList<>(byRuntimeId.values());
        } else {
            Int2ObjectMap<RegisteredItem> all = new Int2ObjectOpenHashMap<>();
            for (RegisteredItem item : parent.definitions()) {
                if (!renumbered.contains(item.getRuntimeId())) {
                    all.put(item.getRuntimeId(), item);
                }
            }
            all.putAll(byRuntimeId);
            merged = new ArrayList<>(all.values());
        }
        merged.sort(Comparator.comparingInt(RegisteredItem::getRuntimeId));
        return List.copyOf(merged);
    }

    /**
     * Internal: lists the item definitions.
     *
     * @return all items in runtime id order; same as {@link #definitions()}
     */
    public @NonNull Collection<RegisteredItem> items() {
        return definitions();
    }

    /**
     * Internal: counts the definitions this registry holds itself.
     *
     * @return how many definitions this registry holds itself: all of them for a shared palette, only the added and
     * overridden ones for a session palette extending another
     */
    @Internal
    public int ownSize() {
        return byRuntimeId.size();
    }

    /**
     * Internal: lists the definitions this registry holds itself, see {@link #ownSize()}.
     *
     * @return the definitions in runtime id order; {@link #definitions()} for a registry without parent
     */
    @Internal
    public @NonNull List<RegisteredItem> ownDefinitions() {
        if (parent == null) {
            return definitions();
        }
        List<RegisteredItem> own = new ArrayList<>(byRuntimeId.values());
        own.sort(Comparator.comparingInt(RegisteredItem::getRuntimeId));
        return List.copyOf(own);
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

    /**
     * Internal: lists the custom items registered in this palette.
     *
     * @return the custom items, including those of the registry this one extends
     */
    public @NonNull List<CustomItem> customItems() {
        return customItems;
    }

    /**
     * Internal: builds the item component packet.
     *
     * @return a new packet carrying all {@link #definitions()}
     */
    public @NonNull ItemComponentPacket toPacket() {
        ItemComponentPacket packet = new ItemComponentPacket();
        packet.getItems().addAll(definitions());
        return packet;
    }

    /**
     * Internal: lists the item tags.
     *
     * @return item tags, tag to item identifiers: vanilla tags plus tags of custom items and layers
     */
    public @NonNull Map<String, List<String>> tags() {
        if (parent == null) {
            return tags;
        }
        return mergedTags.get();
    }

    // ---- Blocks of block items ----

    /**
     * The block an item variant is sent with in this version: the single owner of that decision. For an item that
     * places blocks, a block state of the variant wins (adapted to this version), otherwise the item's block, which is
     * decided in this order:
     * <ol>
     *     <li>a block set by an item override of a layer;</li>
     *     <li>the bundled block to item relation of the data ({@code VanillaData#blockItems}): the block whose first
     *     creative stack the item has, else the block named like the item, else the first block that places it
     *     (sorted by identifier), in its default state. None for an item whose creative stacks in the data all lack a
     *     block: BDS sends such an item without one although it places a block (doors, signs, beds, seeds, ...);</li>
     *     <li>a block state of the item's creative stacks (items the relation does not know);</li>
     *     <li>for data without the relation: a block named like the item, in its default state;</li>
     *     <li>the block of a custom block item.</li>
     * </ol>
     *
     * @param key a variant (or a type, for its base variant), written against the newest Bedrock version like {@link #resolve(ItemVariant)}
     * @return the block, or null if the item is not a block item here (or does not exist and has no fallback)
     * @since 1.0.0
     */
    public @Nullable RegisteredBlockState blockOf(@NonNull ItemKey key) {
        ResolvedItem resolved = resolve(key);
        return resolved == null ? null : resolved.block();
    }

    /**
     * Internal: finds the item of a custom block of a layer of this registry.
     *
     * @param identifier the block identifier
     * @return the item, or null if the block has none
     */
    @Internal
    public @Nullable RegisteredItem customBlockItem(@NonNull String identifier) {
        return customBlockItems.contains(identifier) ? getDefinition(identifier) : null;
    }

    /**
     * Internal: finds the block an item is sent with by default; see {@link #blockOf}.
     *
     * @param definition the item
     * @return the block, or null if it is not a block item
     */
    @Internal
    public @Nullable RegisteredBlockState defaultBlock(@NonNull ItemDefinition definition) {
        return defaultBlockOf(definition.getIdentifier());
    }

    /**
     * Internal: tells whether an item is sent with a default block.
     *
     * @param definition the item
     * @return true if {@link #defaultBlock} gives a block for it
     */
    @Internal
    public boolean isBlockItem(@NonNull ItemDefinition definition) {
        return defaultBlockOf(definition.getIdentifier()) != null;
    }

    /**
     * Internal: tells whether an item places several blocks.
     *
     * @param identifier the item identifier
     * @return true if one item identifier places several different blocks, told apart only by the block state sent
     * with the item. Variants of such items should specify their block state
     */
    @Internal
    public boolean isMultiStateBlockItem(@NonNull String identifier) {
        return multiStateBlockItems.contains(identifier);
    }

    // ---- Translation ----

    /**
     * What this version sends for a variant: its resolution, cached per canonical id. A field read and an array load
     * for the variants of the newest version, it does not allocate; any other variant is resolved on every call.
     *
     * @param key a variant (or a type, for its base variant), written against the newest Bedrock version
     * @return the item, damage and block to send, and whether the item is a fallback; null if the item does not exist
     *         here and has no fallback
     * @since 1.0.0
     */
    public @Nullable ResolvedItem resolve(@NonNull ItemKey key) {
        BedrockItemVariant variant = key.variant();
        int id = variant.canonicalIn(canonical);
        if (id != -1) {
            return item(id);
        }
        BedrockItemType type = variant.type();
        // Only the interned variants of such a constant are kept (the base and the named ones): any other aux is the caller's
        return type.isCustom() || type.isAvailable() || type.variant(variant.aux()) != variant
            ? resolve(variant.toItemVariant()) : unavailable(variant);
    }

    /**
     * What this version sends for an interned variant of a constant the newest bundled release lacks and this
     * registry's canonical items too: resolved by identifier through the fallbacks once per variant and kept. The
     * constants are few, so this stays small.
     */
    private @Nullable ResolvedItem unavailable(BedrockItemVariant variant) {
        Map<BedrockItemVariant, Optional<ResolvedItem>> cache = unavailableItems.get();
        Optional<ResolvedItem> resolved = cache.get(variant);
        if (resolved == null) {
            resolved = Optional.ofNullable(resolve(variant.toItemVariant()));
            Optional<ResolvedItem> raced = cache.putIfAbsent(variant, resolved);
            if (raced != null) {
                resolved = raced;
            }
        }
        return resolved.orElse(null);
    }

    /**
     * Resolves a variant, written against the newest Bedrock version, for this palette's version.
     * Items that do not exist in this version (or are forcibly replaced, e.g. because they are experimental there)
     * use their version fallback, if any. Results are cached.
     *
     * @param variant the variant, written against the newest Bedrock version
     * @return the resolved item, or null if the item does not exist and has no fallback
     */
    @Internal
    public @Nullable ResolvedItem resolve(@NonNull ItemVariant variant) {
        CanonicalTable table = canonicalTable.peek();
        if (table != null) {
            int canonicalId = canonical.index(variant);
            if (canonicalId != -1) {
                // index() treats an explicit default block state like none: one canonical id, one resolution
                return table.items[canonicalId];
            }
        }
        // Not cached: the variants that get here are not canonical, so the caller chose them and a cache would grow with the input
        return resolveOrDefault(variant);
    }

    /**
     * The same as the canonical table does for a variant that has no item or fallback of its own: the default fallback,
     * so a variant that is not canonical (an identifier nobody registered) is sent as the same stand-in.
     */
    private @Nullable ResolvedItem resolveOrDefault(ItemVariant variant) {
        ResolvedItem resolved = resolveUncached(variant);
        return resolved != null ? resolved : defaultResolved();
    }

    private @Nullable ResolvedItem defaultResolved() {
        ResolvedItem result = defaultResolved;
        if (result == null && defaultFallback != null) {
            RegisteredItem fallback = getDefinition(defaultFallback.target().identifier());
            if (fallback != null) {
                defaultResolved = result = new ResolvedItem(fallback, defaultFallback.variant().aux(),
                    defaultBlockOf(fallback.getIdentifier()), true, this);
            }
        }
        return result;
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
            damage = target.variant().aux();
            fallback = true;
        }
        RegisteredBlockState block = defaultBlockOf(item.getIdentifier());
        if (variant.blockState() != null && !fallback && block != null) {
            BlockResolution resolution = blocks.resolve(variant.blockState());
            if (resolution.block() != null) {
                block = resolution.block();
            }
        }
        return new ResolvedItem(item, damage, block, fallback, this);
    }

    // ---- Canonical ids ----

    /**
     * What to send for a canonical id: {@link #resolve(ItemVariant)} of its variant. An array lookup; the table is
     * built on first use.
     *
     * @param canonical the canonical id, an index into {@link #canonical()}
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
     * @param data the item data the client sent
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
     * What to send for a stack: its item resolved for this version (fallbacks applied), the aux value (the stack's own
     * unless a fallback replaces the item), count, NBT, the adventure mode lists and blocking ticks, and the block the
     * stack places: its own block state if it has one and the item places blocks here, else the default block of the
     * item sent (of the fallback item, for a fallback).
     *
     * @param stack the stack to send
     * @return the item data, or {@link ItemData#AIR} if the item does not exist in this version and neither it nor the
     *         palette has a fallback
     */
    @Internal
    public @NonNull ItemData encode(@NonNull BedrockItemStack stack) {
        ResolvedItem resolved = resolve(stack.variant());
        if (resolved == null) {
            return ItemData.AIR;
        }
        RegisteredBlockState block = resolved.block();
        if (stack.blockState() != null && !resolved.fallback() && block != null) {
            RegisteredBlockState own = ownBlock(stack.blockState());
            if (own != null) {
                block = own;
            }
        }
        // The stack's own aux names no variant (see BedrockItemStack#aux): it goes with the item, not with a fallback
        int aux = !resolved.fallback() && stack.aux() != stack.variant().aux() ? stack.aux() : resolved.damage();
        ItemData.Builder data = ItemData.builder()
            .definition(resolved.definition())
            .damage(aux)
            .count(stack.count())
            .tag(stack.nbt())
            .blockingTicks(stack.blockingTicks())
            .blockDefinition(block);
        // Only when there are any: the builder's own empty arrays allocate nothing per stack
        if (!stack.canPlace().isEmpty()) {
            data.canPlace(stack.canPlace().toArray(String[]::new));
        }
        if (!stack.canBreak().isEmpty()) {
            data.canBreak(stack.canBreak().toArray(String[]::new));
        }
        return data.build();
    }

    /**
     * The block of a stack's own block state. A state of this palette's canonical blocks that has a block here is an
     * array lookup; anything else is resolved from its NBT, which builds and hashes the state every time. (The lookup
     * of a state without a block of its own would give the default fallback, where the NBT resolves to none.)
     *
     * @return the block, or null if the state resolves to none
     */
    private @Nullable RegisteredBlockState ownBlock(BedrockBlockState state) {
        int canonicalId = state.canonicalIn(blocks.canonical());
        if (canonicalId != -1 && !blocks.isMissing(canonicalId)) {
            return blocks.block(canonicalId);
        }
        return blocks.resolve(state.toNbt()).block();
    }

    /**
     * Identifies a stack the client sent, see {@link #match(ItemData)}. The block state is set only if the stack's
     * block differs from the item's default block. The variant is always one of the canonical variants: if the item
     * matched but not its aux value, the variant is the item's base variant and the aux the client sent is kept in
     * {@link BedrockItemStack#aux()}, so client input never makes a variant of its own.
     *
     * @param data the item data the client sent
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
        BedrockItemVariant variant = canonical.handle(canonicalId);
        BedrockItemStack.Builder stack = BedrockItemStack.builder(variant);
        if (data.getDamage() != item(canonicalId).damage()) {
            // Only the item matched: its base variant stood in for an aux value no variant has (a legacy damage value, a
            // number nobody listed). Keep the value on the stack instead of losing it or making a variant for it
            stack.variant(variant.type().variant()).aux(data.getDamage());
        }
        return stack.count(Math.max(1, data.getCount()))
            .nbt(data.getTag())
            .blockState(state)
            .canPlace(list(data.getCanPlace()))
            .canBreak(list(data.getCanBreak()))
            .blockingTicks(Math.max(0, data.getBlockingTicks()))
            .build();
    }

    private static List<String> list(String @Nullable [] identifiers) {
        if (identifiers == null || identifiers.length == 0) {
            return List.of();
        }
        List<String> list = new ArrayList<>(identifiers.length);
        for (String identifier : identifiers) {
            if (identifier != null) {
                list.add(identifier);
            }
        }
        return list;
    }

    /**
     * Internal: lists the canonical variants that this version lacks.
     *
     * @return the canonical variants that do not exist in this version and have no fallback of their own: they use the
     *         default fallback ({@link #defaultFallback()}), or {@link #item(int)} is null without one
     */
    @Internal
    public @NonNull List<ItemVariant> missingCanonical() {
        return canonicalTable().missing;
    }

    /**
     * Internal: returns the default fallback.
     *
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

    /**
     * Builds now everything a lookup would build on first use: the canonical tables, the merged definitions and tags,
     * the default fallback's resolution. Called for the palettes a {@code Paletter} compiles.
     */
    @Internal
    public void loadEagerly() {
        canonicalTable();
        List<RegisteredItem> all = definitions();
        tags();
        unavailableItems.get();
        defaultResolved();
        // These registries are shared by every session of a version: a flat table by runtime id costs a few KB once
        // and makes a lookup one array read instead of a hash probe per layer. Only when the ids are dense enough.
        if (flat == null && !all.isEmpty()) {
            int first = all.getFirst().getRuntimeId();
            int span = all.getLast().getRuntimeId() - first + 1;
            if (span <= 4 * all.size() + 4096) {
                RegisteredItem[] table = new RegisteredItem[span];
                for (RegisteredItem item : all) {
                    table[item.getRuntimeId() - first] = item;
                }
                flatBase = first;
                flat = table;
            }
        }
    }

    private CanonicalTable canonicalTable() {
        return canonicalTable.get();
    }

    private static boolean sameResolution(@Nullable ResolvedItem a, @Nullable ResolvedItem b) {
        return a == b || a != null && b != null && a.sameAs(b);
    }

    /**
     * @return true if the entries of the parent's table that name a block state resolve to the same here: with custom
     * blocks added, a state that did not resolve before may do now. Entries without a state do not depend on the blocks
     */
    private boolean resolvesBlockStatesAsBefore(CanonicalTable base) {
        if (blocks == parent.blocks) {
            return true;
        }
        for (int i = 0; i < base.items.length; i++) {
            ItemVariant variant = canonical.variant(i);
            if (variant.blockState() != null && !sameResolution(resolveOrDefault(variant), base.items[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param base the parent's table when its entries are still valid here: the entries below its size are reused,
     *             only the canonical ids added since are built
     */
    private CanonicalTable buildCanonicalTable(@Nullable CanonicalTable base) {
        int size = canonical.size();
        int from = base == null ? 0 : base.items.length;
        ResolvedItem[] items = base == null ? new ResolvedItem[size] : Arrays.copyOf(base.items, size);
        // Of the ids built here only
        List<ItemVariant> missing = new ArrayList<>();
        Set<String> unresolved = new HashSet<>();
        for (int i = from; i < size; i++) {
            ItemVariant variant = canonical.variant(i);
            // Not through the resolve cache: the table holds the results already
            items[i] = resolveUncached(variant);
            if (items[i] == null) {
                missing.add(variant);
                items[i] = defaultResolved();
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
        // Over the parent's maps: only the runtime ids of the new ids are held here
        Layered<IntList> indices = new Layered<>(base == null ? null : base.indices);
        Layered<Candidate[]> byRuntimeId = new Layered<>(base == null ? null : base.byRuntimeId);
        for (int i = from; i < size; i++) {
            if (items[i] == null) {
                continue;
            }
            int runtimeId = items[i].definition().getRuntimeId();
            IntList list = indices.own.get(runtimeId);
            if (list == null) {
                // Copy before writing: the parent's lists are shared
                IntList earlier = base == null ? null : base.indices.get(runtimeId);
                list = earlier == null ? new IntArrayList(1) : new IntArrayList(earlier);
                indices.own.put(runtimeId, list);
            }
            list.add(i);
        }
        for (Int2ObjectMap.Entry<IntList> entry : indices.own.int2ObjectEntrySet()) {
            byRuntimeId.own.put(entry.getIntKey(), candidates(entry.getValue(), items));
        }
        indices = indices.flattenedPast(MAX_LAYERS);
        byRuntimeId = byRuntimeId.flattenedPast(MAX_LAYERS);
        indices.trim();
        byRuntimeId.trim();
        return new CanonicalTable(items, byRuntimeId, indices, base == null ? List.copyOf(missing) : joined(base.missing, missing),
            base == null ? Set.copyOf(unresolved) : joined(base.unresolved, unresolved));
    }

    /** @return the entries of the base and the added ones, the base itself if nothing is added */
    private static <T> List<T> joined(List<T> base, List<T> added) {
        if (added.isEmpty()) {
            return base;
        }
        List<T> all = new ArrayList<>(base);
        all.addAll(added);
        return List.copyOf(all);
    }

    private static <T> Set<T> joined(Set<T> base, Set<T> added) {
        if (base.containsAll(added)) {
            return base;
        }
        Set<T> all = new HashSet<>(base);
        all.addAll(added);
        return Set.copyOf(all);
    }

    /** How many maps a lookup walks at most: deeper chains of extensions are merged into one map. */
    private static final int MAX_LAYERS = 4;

    /** An int keyed map over another one: what a layer holds shadows the base's. Not changed once built, so reads need no lock. */
    private static final class Layered<T> {
        private final @Nullable Layered<T> base;
        private final Int2ObjectOpenHashMap<T> own = new Int2ObjectOpenHashMap<>();

        Layered(@Nullable Layered<T> base) {
            this.base = base;
        }

        /** The number of maps a lookup may walk, this one included. */
        private int depth() {
            int depth = 0;
            for (Layered<T> layer = this; layer != null; layer = layer.base) {
                depth++;
            }
            return depth;
        }

        /** @return this, or one map with everything this and its bases hold if that is more than {@code maxDepth} maps */
        Layered<T> flattenedPast(int maxDepth) {
            if (depth() <= maxDepth) {
                return this;
            }
            List<Layered<T>> chain = new ArrayList<>();
            for (Layered<T> layer = this; layer != null; layer = layer.base) {
                chain.add(layer);
            }
            Layered<T> flat = new Layered<>(null);
            // The base first, so what an upper layer holds shadows it
            for (int i = chain.size() - 1; i >= 0; i--) {
                flat.own.putAll(chain.get(i).own);
            }
            return flat;
        }

        @Nullable T get(int key) {
            for (Layered<T> layer = this; layer != null; layer = layer.base) {
                T value = layer.own.get(key);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        void trim() {
            own.trim();
        }
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

    private record CanonicalTable(ResolvedItem[] items, Layered<Candidate[]> byRuntimeId, Layered<IntList> indices,
                                  List<ItemVariant> missing, Set<String> unresolved) {
    }

    /**
     * Builds a creative stack for this version: the item itself, never a fallback.
     *
     * @param stack the creative stack
     * @return the item data, or null if the item does not exist in this version
     */
    @Internal
    public @Nullable ItemData toItemData(@NonNull BedrockItemStack stack) {
        ResolvedItem resolved = resolve(stack.variant());
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
        /**
         * The bundled block to item relation, inverted: item to the blocks that it places, sorted. Empty for data without it.
         * An empty set: the item places blocks, but BDS sends it without a block (see {@link ItemRegistry#vanilla}).
         */
        private final Map<String, Set<String>> itemBlocks;
        private final Map<String, ItemFallback> fallbacks;
        private @Nullable ItemFallback defaultFallback;
        private final CanonicalItems canonical;
        private final Map<String, Set<String>> tags = new LinkedHashMap<>();
        private final List<CustomItem> customItems;
        private int nextRuntimeId;
        /** Old runtime ids of the parent's plain block items that took the id of their block's new {@code block_id}. */
        private final IntSet renumbered = new IntOpenHashSet();
        /** Identifiers of those items. */
        private final Set<String> renumberedBlockItems = new HashSet<>();
        /** Identifiers the layer is about to register: they exist for components compiled before their registration. */
        private final Set<String> declared = new HashSet<>();
        /**
         * Identifiers this builder registered, overrode, or named a block after (even a block without an item). Only these items,
         * plus those with a block override and the custom block items, can get a default block that differs from the parent's.
         */
        private final Set<String> touched = new HashSet<>();
        private boolean creativeStacksOfParent;
        private @Nullable Set<String> layoutIdentifiers;

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
            renumberBlockItems(parent);
        }

        /**
         * Custom blocks are numbered again when a layer adds blocks, and the item of a plain block item follows its block's id.
         */
        private void renumberBlockItems(ItemRegistry parent) {
            if (blocks == parent.blocks) {
                return;
            }
            for (String identifier : parent.customBlockItems) {
                RegisteredItem item = parent.getDefinition(identifier);
                // A plain block item has its block's id, also after an override made it component based
                if (item == null || item.getRuntimeId() != ComponentIndex.BLOCK_ITEM_ID_PLUS_BLOCK_ID - parent.blocks.customBlockId(identifier)) {
                    continue;
                }
                int runtimeId = plainBlockItemId(identifier);
                if (runtimeId != item.getRuntimeId()) {
                    renumbered.add(item.getRuntimeId());
                    renumberedBlockItems.add(identifier);
                    NbtMap componentData = item.getComponentData();
                    if (componentData != null && componentData.containsKey("id")) {
                        componentData = componentData.toBuilder().putInt("id", runtimeId).build();
                    }
                    put(new RegisteredItem(identifier, runtimeId, item.getVersion(), item.isComponentBased(), componentData, item.origin(),
                        item.components(), item.customItem(), item.type()));
                }
            }
        }

        /** BDS gives the plain item of a custom block the id {@code 255 - block_id}: negative, as block ids start at 10000. */
        private int plainBlockItemId(String block) {
            return ComponentIndex.BLOCK_ITEM_ID_PLUS_BLOCK_ID - blocks.customBlockId(block);
        }

        /**
         * Internal: lists the custom blocks whose plain item was renumbered.
         *
         * @return the blocks whose plain item got a new runtime id because this builder's blocks number the parent's blocks differently
         */
        @Internal
        public @NonNull Set<String> renumberedBlockItems() {
            return Collections.unmodifiableSet(renumberedBlockItems);
        }

        private void put(RegisteredItem item) {
            byRuntimeId.put(item.getRuntimeId(), item);
            byIdentifier.put(item.getIdentifier(), item);
            touched.add(item.getIdentifier());
            // Plain block items have negative ids: they never move the next sequential id
            nextRuntimeId = Math.max(nextRuntimeId, item.getRuntimeId() + 1);
        }

        /** An item behind a disabled experiment toggle: in the registry by runtime id only, see {@link ItemRegistry#vanilla}. */
        private void putGated(RegisteredItem item) {
            byRuntimeId.put(item.getRuntimeId(), item);
            nextRuntimeId = Math.max(nextRuntimeId, item.getRuntimeId() + 1);
        }

        /**
         * Announces an item the layer is about to register, so components compiled earlier can refer to it
         * (registration order must not decide whether an item exists).
         *
         * @param identifier the identifier of the item that will be registered
         */
        public void declare(@NonNull String identifier) {
            declared.add(identifier);
        }

        /**
         * Internal: looks up an item of this builder or of the registry it extends.
         *
         * @param identifier the item identifier
         * @return the item, or null if there is none
         */
        public @Nullable RegisteredItem get(@NonNull String identifier) {
            RegisteredItem item = byIdentifier.get(identifier);
            return item != null || parent == null ? item : parent.getDefinition(identifier);
        }

        /** The items of a tag so far: the parent's, plus what this builder added. */
        private Set<String> tagItems(String tag) {
            return tags.computeIfAbsent(tag, t -> new LinkedHashSet<>(parent == null ? List.of() : parent.tags().getOrDefault(t, List.of())));
        }

        /**
         * Internal: applies an item override of a layer.
         *
         * @param identifier the identifier of the item to override
         * @param modifier receives the override to change
         * @param report where the compilation of the components reports problems
         * @param source the id of the layer, used as the origin of what is reported
         * @return false if the item does not exist in this version, so there was nothing to override
         */
        public boolean override(@NonNull String identifier, @NonNull Consumer<ItemOverride> modifier, @NonNull PaletteReportBuilder report,
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

        /**
         * Registers a custom item with the next runtime id.
         *
         * @param custom the custom item
         * @param report where the compilation of the components reports problems
         * @param source the id of the layer, used as the origin of the item
         * @return the registered item
         * @throws PaletteException if an item with the identifier exists already
         */
        public @NonNull RegisteredItem addCustom(@NonNull CustomItem custom, @NonNull PaletteReportBuilder report, @NonNull String source) {
            checkUnused(custom.identifier());
            int runtimeId = nextRuntimeId;
            NbtMap componentData = ItemCompiler.componentData(custom.identifier(), runtimeId, custom.components(), custom.menuCategory(),
                context(report, source));
            RegisteredItem item = new RegisteredItem(custom.identifier(), runtimeId, ItemVersion.DATA_DRIVEN, true, componentData,
                Origin.custom(source), custom.components(), custom, custom.type());
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
        private WireContext context(PaletteReportBuilder report, String source) {
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
         *
         * @param block the custom block
         * @param report where the compilation of the components reports problems
         * @param source the id of the layer, used as the origin of the item
         * @return the item, or null for {@link CustomBlock.BlockItem#NONE}
         * @throws PaletteException if an item with the identifier of the block exists already
         */
        public @Nullable RegisteredItem addBlockItem(@NonNull CustomBlock block, @NonNull PaletteReportBuilder report, @NonNull String source) {
            // Also without an item: a block named like an item of the parent gives it a default block, where there is no relation
            touched.add(block.identifier());
            if (block.item() == CustomBlock.BlockItem.NONE) {
                return null;
            }
            checkUnused(block.identifier());
            RegisteredItem item = new RegisteredItem(block.identifier(), plainBlockItemId(block.identifier()), ItemVersion.NONE, false, null,
                Origin.custom(source), List.of(), null, block.itemType());
            put(item);
            customBlockItems.add(block.identifier());
            return item;
        }

        /**
         * Registers a custom item that replaces the item of a custom block ({@code block_placer} with
         * {@code replace_block_item}): an ordinary data driven item with the next sequential id, which is the block's item.
         *
         * @param custom the custom item
         * @param report where the compilation of the components reports problems
         * @param source the id of the layer, used as the origin of the item
         * @return the registered item
         * @throws PaletteException if an item with the identifier exists already
         */
        public @NonNull RegisteredItem addBlockItemReplacement(@NonNull CustomItem custom, @NonNull PaletteReportBuilder report,
                                                               @NonNull String source) {
            RegisteredItem item = addCustom(custom, report, source);
            customBlockItems.add(custom.identifier());
            return item;
        }

        private void checkUnused(String identifier) {
            if (get(identifier) != null) {
                throw new PaletteException("Item " + identifier + " already exists in " + version, null, identifier, null);
            }
        }

        /**
         * Internal: sets the default fallback.
         *
         * @param fallback what canonical variants without an item or fallback of their own use, or null for none
         */
        @Internal
        public void defaultFallback(@Nullable ItemFallback fallback) {
            this.defaultFallback = fallback;
        }

        /**
         * Internal: sets the fallback of an item identifier, replacing an earlier one.
         *
         * @param identifier the identifier of the item the fallback replaces
         * @param fallback the fallback
         */
        public void fallback(@NonNull String identifier, @NonNull ItemFallback fallback) {
            fallbacks.put(identifier, fallback);
        }

        /**
         * Internal: adds items to an item tag, which is created if it does not exist.
         *
         * @param tag the tag name
         * @param identifiers the identifiers of the items to add
         */
        public void tag(@NonNull String tag, @NonNull List<String> identifiers) {
            tagItems(tag).addAll(identifiers);
        }

        /**
         * Says that the layout {@link #build} gets has the creative stacks of the parent's items as the parent's layout does,
         * apart from the items this builder registers or overrides (a layer without creative edits): the default blocks of the
         * other items are the parent's, and are not decided again. Without it every item is decided again. The layout is
         * walked for the changed items.
         */
        public void keepsCreativeStacksOfParent() {
            creativeStacksOfParent = true;
            layoutIdentifiers = null;
        }

        /**
         * Like {@link #keepsCreativeStacksOfParent()} for a layout that is the parent's itself, whose identifiers are known: the
         * layout is not walked at all if it mentions none of the changed items.
         *
         * @param mentioned the identifiers the parent's layout mentions
         */
        public void keepsCreativeStacksOfParentLayout(@NonNull Set<String> mentioned) {
            creativeStacksOfParent = true;
            layoutIdentifiers = mentioned;
        }

        /**
         * Builds the registry.
         *
         * @param layout the creative layout of the palette, to decide the default blocks of items from
         * @param report where problems are reported
         * @return the registry
         */
        public @NonNull ItemRegistry build(@NonNull CreativeLayout layout, @NonNull PaletteReportBuilder report) {
            // The items to decide the default block of: all of them, or only those that can differ from the parent's. Those are the
            // ones this builder touched, and every item with a block override or a custom block item, because their default
            // block is looked up among blocks this builder may have changed.
            Set<String> changed = null;
            if (parent != null && creativeStacksOfParent) {
                changed = new HashSet<>(touched);
                changed.addAll(blockOverrides.keySet());
                changed.addAll(customBlockItems);
            }
            Set<String> decide = changed;
            Map<String, Set<NbtMap>> creativeStates = new HashMap<>();
            Set<String> creativeWithoutBlock = new HashSet<>();
            if (decide == null || layoutIdentifiers == null || decide.stream().anyMatch(layoutIdentifiers::contains)) {
                layout.forEachStack(stack -> {
                    String identifier = stack.type().identifier();
                    if (decide != null && !decide.contains(identifier)) {
                        return;
                    }
                    if (stack.blockState() != null) {
                        // Kept states: compared by their values, without iterating them (BlockStates.hash)
                        creativeStates.computeIfAbsent(identifier, id -> Collections.newSetFromMap(
                            new Object2ObjectLinkedOpenCustomHashMap<>(BlockStates.STRATEGY))).add(stack.blockState().toNbt());
                    } else {
                        creativeWithoutBlock.add(identifier);
                    }
                });
            }

            Map<String, RegisteredBlockState> defaultBlocks = new HashMap<>();
            Set<String> multiState = new HashSet<>();
            // Decided in the order the full path walks them, which is the order of the report's warnings: the parent's items
            // first (by runtime id), then this builder's own
            Collection<String> identifiers = new LinkedHashSet<>();
            if (decide != null) {
                // The others keep the parent's default block, and the parent's say about several block states
                decide.stream().filter(identifier -> !byIdentifier.containsKey(identifier))
                    .map(parent::getDefinition).filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(RegisteredItem::getRuntimeId))
                    .forEach(item -> identifiers.add(item.getIdentifier()));
                byIdentifier.keySet().stream().filter(decide::contains).forEach(identifiers::add);
                parent.multiStateBlockItems.stream().filter(identifier -> !decide.contains(identifier)).forEach(multiState::add);
            } else {
                if (parent != null) {
                    for (RegisteredItem item : parent.definitions()) {
                        // Not the gated items: registered by runtime id only, they never resolve
                        if (!byIdentifier.containsKey(item.getIdentifier()) && parent.getDefinition(item.getIdentifier()) == item) {
                            identifiers.add(item.getIdentifier());
                        }
                    }
                }
                identifiers.addAll(byIdentifier.keySet());
            }
            for (String identifier : identifiers) {
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
                    // No blocks: the item places blocks, but BDS sends it without one
                    block = placed.isEmpty() ? null : bundledBlock(identifier, placed, states);
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
            if (reuse && built.resolvesBlockStatesAsBefore(parent.canonicalTable())) {
                built.canonicalTable.preset(built.buildCanonicalTable(parent.canonicalTable()));
            }
            return built;
        }

        /**
         * @return true if the parent's canonical table is still right for its ids: nothing it resolves changed
         */
        private boolean canReuseTable(Map<String, RegisteredBlockState> changedBlocks) {
            if (!blocks.extendsWithBlocks(parent.blocks) || !canonical.extendsOrIs(parent.canonical)
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
