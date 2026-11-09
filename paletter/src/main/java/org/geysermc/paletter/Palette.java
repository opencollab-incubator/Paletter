package org.geysermc.paletter;

import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.internal.Canonicals;
import org.geysermc.paletter.internal.Forwarding;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.geysermc.paletter.block.BlockNetworkIds;
import org.geysermc.paletter.block.BlockRegistry;
import org.geysermc.paletter.internal.block.BlockResolution;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.internal.CanonicalBlocks;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.creative.CreativeContent;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.creative.CreativeOrdering;
import org.geysermc.paletter.creative.ItemMatcher;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.CanonicalItems;
import org.geysermc.paletter.item.ItemRegistry;
import org.geysermc.paletter.internal.item.ItemVariant;
import org.geysermc.paletter.internal.item.ResolvedItem;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Everything a Bedrock client of one version needs to know about items and blocks: vanilla data plus a stack of
 * {@link PaletteLayer}s, compiled. Immutable and thread safe; share it between all sessions it applies to.
 * <p>
 * Sending it to a client:
 * <pre>{@code
 * palette.applyTo(session.getCodecHelper());     // item and block definitions for the codec
 * palette.applyTo(startGamePacket);              // block properties and the block network id mode
 * session.sendPacket(palette.itemComponentPacket());
 * session.sendPacket(palette.creativeContentPacket());
 * }</pre>
 * Translating content uses handles, version independent and interned objects ({@link BedrockBlockState},
 * {@link BedrockItemVariant}): {@link #block(NbtMap)} and {@link #item(String, int)} find them once, then
 * {@link #runtimeId(BedrockBlockState)} and {@link #block(BedrockBlockState)} for blocks, {@link #item(BedrockItemVariant)}
 * for items. {@link #encode} and {@link #decode} translate whole stacks at the protocol boundary. They are field reads and array lookups that do not allocate. The ids behind
 * the handles are not API.
 *
 * @since 1.0.0
 */
public final class Palette {
    private final VanillaData data;
    private final List<PaletteLayer> layers;
    private final BlockRegistry blocks;
    private final ItemRegistry items;
    private final CreativeLayout creativeLayout;
    private final CreativeContent creative;
    private final PaletteReport report;
    private final CreativeOrdering ordering;
    private final Map<PaletteLayer, WeakReference<Palette>> extensions = new WeakHashMap<>();

    Palette(VanillaData data, List<PaletteLayer> layers, BlockRegistry blocks, ItemRegistry items,
            CreativeLayout creativeLayout, CreativeContent creative, PaletteReport report, CreativeOrdering ordering) {
        this.data = data;
        this.layers = List.copyOf(layers);
        this.blocks = blocks;
        this.items = items;
        this.creativeLayout = creativeLayout;
        this.creative = creative;
        this.report = report;
        this.ordering = ordering;
    }

    /**
     * @return the creative ordering of the Paletter that compiled this palette; extensions use it too
     */
    CreativeOrdering ordering() {
        return ordering;
    }

    /**
     * @return this palette with another report, sharing everything else
     */
    Palette withReport(PaletteReport report) {
        return new Palette(data, layers, blocks, items, creativeLayout, creative, report, ordering);
    }

    public @NonNull DataVersion version() {
        return data.version();
    }

    @Internal
    public @NonNull VanillaData vanillaData() {
        return data;
    }

    /**
     * @return the layers this palette was compiled from, in order
     */
    public @NonNull List<PaletteLayer> layers() {
        return layers;
    }

    public @NonNull BlockRegistry blocks() {
        return blocks;
    }

    public @NonNull ItemRegistry items() {
        return items;
    }

    /**
     * @return how block runtime ids are assigned in this palette
     */
    public @NonNull BlockNetworkIds blockNetworkIds() {
        return blocks.networkIds();
    }

    // ---- Content handles ----

    /**
     * @param state a block state of any bundled version (mapped forward as in {@link Paletter#block(NbtMap)}) or of a
     *              custom block of this palette's layers, as {@code {name, states}}; extra keys like {@code version}
     *              are ignored
     * @return its handle, or null if there is no equivalent (see {@link #explainUnknown(NbtMap)})
     * @since 1.0.0
     */
    public @Nullable BedrockBlockState block(@NonNull NbtMap state) {
        CanonicalBlocks canonical = blocks.canonical();
        return canonical.handle(Canonicals.block(canonical, state));
    }

    /**
     * @param identifier an item identifier of any bundled version (mapped forward as in {@link Paletter#item(String, int)}) or
     *                   of a custom item of this palette's layers
     * @param aux the aux (damage) value
     * @return its handle, or null if there is no equivalent (see {@link #explainUnknown(String, int)})
     * @since 1.0.0
     */
    public @Nullable BedrockItemVariant item(@NonNull String identifier, int aux) {
        return items.canonical().handle(Canonicals.item(items.canonical(), ItemVariant.of(identifier, aux)));
    }

    /**
     * @return why a block state has no handle in this palette, or null if it has one
     * @since 1.0.0
     */
    public @Nullable String explainUnknown(@NonNull NbtMap state) {
        Forwarding forwarding = Canonicals.forwardBlock(blocks.canonical(), state);
        return forwarding.known() ? null : forwarding.detail();
    }

    /**
     * @return why an item has no handle in this palette, or null if it has one
     * @since 1.0.0
     */
    public @Nullable String explainUnknown(@NonNull String identifier, int aux) {
        Forwarding forwarding = Canonicals.forwardItem(items.canonical(), ItemVariant.of(identifier, aux));
        return forwarding.known() ? null : forwarding.detail();
    }

    /**
     * @return every item variant this palette knows, in no particular order: the vanilla ones of the newest data, the
     *         custom items and variants of its layers
     * @since 1.0.0
     */
    public @NonNull List<BedrockItemVariant> itemVariants() {
        CanonicalItems canonical = items.canonical();
        List<BedrockItemVariant> variants = new ArrayList<>(canonical.size());
        for (int id = 0; id < canonical.size(); id++) {
            variants.add(canonical.handle(id));
        }
        return Collections.unmodifiableList(variants);
    }

    /**
     * @return the block to send for a state; resolved for this version, air if nothing fits. A field read and an array
     *         load, it does not allocate
     * @throws IllegalArgumentException if the state is not in this palette (a custom block of a layer it does not have)
     * @since 1.0.0
     */
    public @NonNull RegisteredBlockState block(@NonNull BedrockBlockState state) {
        return blocks.block(requireCanonical(state));
    }

    /**
     * @return the runtime id to send for a state, in this palette's {@link #blockNetworkIds()} mode. A field read and an
     *         array load, it does not allocate
     * @throws IllegalArgumentException if the state is not in this palette (a custom block of a layer it does not have)
     * @since 1.0.0
     */
    public int runtimeId(@NonNull BedrockBlockState state) {
        return blocks.runtimeId(requireCanonical(state));
    }

    private int requireCanonical(BedrockBlockState state) {
        int canonical = state.canonicalIn(blocks.canonical());
        if (canonical == -1) {
            throw new IllegalArgumentException(state + " is not in " + this);
        }
        return canonical;
    }

    /**
     * The block an item variant is sent with in this palette's version, in its default state: Paletter alone decides it,
     * see {@link ItemRegistry#blockOf}. A stack may carry a block state of its own, see {@link #encode}.
     *
     * @return the block, or null if the item is not a block item (or does not exist in this version and has no fallback)
     * @since 1.0.0
     */
    public @Nullable RegisteredBlockState blockOf(@NonNull BedrockItemVariant variant) {
        return items.blockOf(variant);
    }

    /**
     * @return the item of a type as registered in this palette, or null if it has none (an item of a layer this palette
     *         does not have, or one that does not exist in this version; fallbacks are not applied, see {@link #encode})
     * @since 1.0.0
     */
    public @Nullable RegisteredItem item(@NonNull BedrockItemType type) {
        return items.item(type.identifier());
    }

    /**
     * @return the item of a variant as registered in this palette, like {@link #item(BedrockItemType)}
     * @since 1.0.0
     */
    public @Nullable RegisteredItem item(@NonNull BedrockItemVariant variant) {
        return item(variant.type());
    }

    // ---- Stacks ----

    /**
     * Encodes a stack for this palette's version: the item with the aux value, count and NBT, and the block the stack
     * places (its own block state if it has one and the item places blocks, else the item's default block). An item
     * this version does not have is replaced according to the fallback policy.
     *
     * @return the item data, or {@link ItemData#AIR} if there is no item at all (not in this version, no fallback, or not
     *         in this palette)
     * @since 1.0.0
     */
    public @NonNull ItemData encode(@NonNull BedrockItemStack stack) {
        return items.encode(stack);
    }

    /**
     * Decodes a stack the client sent. The block state is set only if the stack's block differs from the item's default
     * block. The stack of a fallback shared by several variants, or of an item that is not a variant of the newest
     * version, is not recognized.
     *
     * @return the stack, or null for air or an item this palette cannot identify
     * @since 1.0.0
     */
    public @Nullable BedrockItemStack decode(@NonNull ItemData data) {
        return items.decode(data);
    }

    // ---- Debugging ----

    /**
     * Explains what this palette does with a block state: canonical, runtime and hashed id, how it was resolved for
     * this version and which state is sent. Slow, for debugging only.
     *
     * @since 1.0.0
     */
    public @NonNull BlockExplanation explain(@NonNull NbtMap state) {
        NbtMap normalized = BlockStates.normalize(state);
        int canonical = Canonicals.block(blocks.canonical(), normalized);
        BlockResolution resolution = blocks.resolve(normalized);
        BlockExplanation.Kind kind = resolution.block() != null ? BlockExplanation.Kind.valueOf(resolution.kind().name())
            : blocks.defaultFallback() != null ? BlockExplanation.Kind.DEFAULT : BlockExplanation.Kind.MISSING;
        RegisteredBlockState sent = kind == BlockExplanation.Kind.MISSING ? null
            : canonical != -1 ? blocks.block(canonical) : resolution.block();
        List<String> chain = new ArrayList<>(List.of(BlockStates.name(normalized)));
        if (sent != null && !sent.identifier().equals(chain.getFirst())) {
            chain.add(sent.identifier());
        }
        return new BlockExplanation(normalized, blocks.canonical().handle(canonical), kind, List.copyOf(chain), sent == null ? null : sent.getState(),
            sent == null ? -1 : sent.getRuntimeId(), sent == null ? -1 : BlockNetworkIds.hash(sent.getState()));
    }

    /**
     * Like {@link #explain(NbtMap)}, for a vanilla state.
     *
     * @since 1.0.0
     */
    public @NonNull BlockExplanation explain(@NonNull BedrockBlockState state) {
        return explain(state.toNbt());
    }

    /**
     * Explains what this palette does with an item: canonical and runtime id, how it was resolved for this
     * version, the block it is sent with and its place in the creative inventory. Slow, for debugging only.
     *
     * @since 1.0.0
     */
    public @NonNull ItemExplanation explain(@NonNull String identifier, int aux, @Nullable NbtMap blockState) {
        ItemVariant variant = ItemVariant.of(identifier, aux);
        int canonical = Canonicals.item(items.canonical(), variant);
        ResolvedItem direct = items.resolve(variant);
        ResolvedItem sent = canonical != -1 ? items.item(canonical) : direct;
        ItemExplanation.Kind kind;
        if (direct != null) {
            kind = direct.fallback() ? ItemExplanation.Kind.FALLBACK : ItemExplanation.Kind.EXACT;
        } else {
            kind = sent != null ? ItemExplanation.Kind.DEFAULT : ItemExplanation.Kind.MISSING;
        }
        List<String> chain = new ArrayList<>(List.of(identifier));
        String item = sent == null ? null : sent.definition().getIdentifier();
        if (item != null && !item.equals(identifier)) {
            chain.add(item);
        }
        RegisteredBlockState block = sent == null ? null : sent.block();
        if (blockState != null && sent != null && !sent.fallback() && block != null) {
            RegisteredBlockState own = blocks.resolve(blockState).block();
            if (own != null) {
                block = own;
            }
        }
        ItemMatcher matcher = ItemMatcher.id(identifier).and(ItemMatcher.damage(aux));
        if (blockState != null) {
            matcher = matcher.and(ItemMatcher.blockState(blockState));
        }
        return new ItemExplanation(identifier, aux, blockState == null ? null : BlockStates.normalize(blockState),
            items.canonical().handle(canonical), kind, List.copyOf(chain), item,
            sent == null ? -1 : sent.definition().getRuntimeId(), sent == null ? 0 : sent.damage(),
            block == null ? null : BlockExplanation.describe(block.getState()), block == null ? -1 : block.getRuntimeId(),
            creativeLayout().contains(matcher), creativeLayout().groupOf(matcher));
    }

    /**
     * Like {@link #explain(String, int, NbtMap)}, without a block state.
     *
     * @since 1.0.0
     */
    public @NonNull ItemExplanation explain(@NonNull String identifier, int aux) {
        return explain(identifier, aux, null);
    }

    /**
     * Like {@link #explain(String, int)}, for a variant.
     *
     * @since 1.0.0
     */
    public @NonNull ItemExplanation explain(@NonNull BedrockItemVariant variant) {
        return explain(variant.type().identifier(), variant.aux(), null);
    }

    /**
     * @return the version independent creative layout after all layers
     */
    public @NonNull CreativeLayout creativeLayout() {
        return creativeLayout;
    }

    /**
     * @return the creative inventory as sent to this version's clients
     */
    public @NonNull CreativeContent creative() {
        return creative;
    }

    /**
     * @return what happened while compiling this palette (including its parents)
     */
    public @NonNull PaletteReport report() {
        return report;
    }

    /**
     * Compiles one more layer on top of this palette, e.g. for one session. Results are cached per layer
     * instance for as long as the layer is referenced.
     */
    public @NonNull Palette extend(@NonNull PaletteLayer layer) {
        synchronized (extensions) {
            WeakReference<Palette> existing = extensions.get(layer);
            Palette palette = existing == null ? null : existing.get();
            if (palette != null) {
                return palette;
            }
        }
        Palette extended = PaletteCompiler.extend(this, layer, true);
        synchronized (extensions) {
            WeakReference<Palette> raced = extensions.get(layer);
            Palette palette = raced == null ? null : raced.get();
            if (palette != null) {
                return palette;
            }
            // Weak values: the extended palette references the layer, so a strong value would keep the key alive forever
            extensions.put(layer, new WeakReference<>(extended));
            return extended;
        }
    }

    /**
     * Sets the item and block definition registries of a connection's codec.
     */
    public void applyTo(@NonNull BedrockCodecHelper helper) {
        helper.setItemDefinitions(items);
        helper.setBlockDefinitions(blocks);
    }

    /**
     * Adds the block properties (vanilla data driven and custom blocks) to a start game packet, and sets
     * {@code blockNetworkIdsHashed} to match {@link #blockNetworkIds()}.
     */
    public void applyTo(@NonNull StartGamePacket packet) {
        packet.getBlockProperties().addAll(blocks.blockProperties());
        packet.setBlockNetworkIdsHashed(blocks.networkIds() == BlockNetworkIds.HASHED);
    }

    public @NonNull List<BlockPropertyData> blockProperties() {
        return blocks.blockProperties();
    }

    public @NonNull ItemComponentPacket itemComponentPacket() {
        return items.toPacket();
    }

    public @NonNull CreativeContentPacket creativeContentPacket() {
        return creative.toPacket();
    }

    @Override
    public String toString() {
        List<String> ids = new ArrayList<>();
        layers.forEach(layer -> ids.add(layer.id()));
        return "Palette{" + version() + ", " + blockNetworkIds() + ", layers=" + ids + ", " + blocks.size() + " block states, "
            + items.definitions().size() + " items, " + creative.size() + " creative entries}";
    }
}
