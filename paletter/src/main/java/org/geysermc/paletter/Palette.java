package org.geysermc.paletter;

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
import org.geysermc.paletter.block.BlockPalette;
import org.geysermc.paletter.block.BlockResolution;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.block.CanonicalBlocks;
import org.geysermc.paletter.block.PaletteBlock;
import org.geysermc.paletter.creative.CreativeContent;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.creative.ItemMatcher;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.item.CanonicalItems;
import org.geysermc.paletter.item.ItemPalette;
import org.geysermc.paletter.item.ItemVariant;
import org.geysermc.paletter.item.ResolvedItem;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
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
 * Translating content uses canonical ids, version independent dense ints (see {@link CanonicalBlocks} and
 * {@link CanonicalItems}): {@link #runtimeId(int)} and {@link #block(int)} for blocks, {@link #item(int)} and
 * {@link #match(ItemData)} for items. All are array or hash lookups that do not allocate.
 *
 * @since 1.0.0
 */
public final class Palette {
    private final VanillaData data;
    private final List<PaletteLayer> layers;
    private final BlockPalette blocks;
    private final ItemPalette items;
    private final CreativeLayout creativeLayout;
    private final CreativeContent creative;
    private final PaletteReport report;
    private final Map<PaletteLayer, WeakReference<Palette>> extensions = new WeakHashMap<>();

    Palette(VanillaData data, List<PaletteLayer> layers, BlockPalette blocks, ItemPalette items,
            CreativeLayout creativeLayout, CreativeContent creative, PaletteReport report) {
        this.data = data;
        this.layers = List.copyOf(layers);
        this.blocks = blocks;
        this.items = items;
        this.creativeLayout = creativeLayout;
        this.creative = creative;
        this.report = report;
    }

    /**
     * @return this palette with another report, sharing everything else
     */
    Palette withReport(PaletteReport report) {
        return new Palette(data, layers, blocks, items, creativeLayout, creative, report);
    }

    public @NonNull DataVersion version() {
        return data.version();
    }

    public @NonNull VanillaData vanillaData() {
        return data;
    }

    /**
     * @return the layers this palette was compiled from, in order
     */
    public @NonNull List<PaletteLayer> layers() {
        return layers;
    }

    public @NonNull BlockPalette blocks() {
        return blocks;
    }

    public @NonNull ItemPalette items() {
        return items;
    }

    /**
     * @return how block runtime ids are assigned in this palette
     */
    public @NonNull BlockNetworkIds blockNetworkIds() {
        return blocks.networkIds();
    }

    // ---- Canonical ids ----

    public @NonNull CanonicalBlocks canonicalBlocks() {
        return blocks.canonical();
    }

    /**
     * @return the canonical id of a block state of the newest version (or of a custom block of this palette's layers),
     * or -1. Extra keys like {@code version} are ignored
     */
    public int canonicalBlock(@NonNull NbtMap state) {
        return blocks.canonical().index(state);
    }

    public int canonicalBlockCount() {
        return blocks.canonical().size();
    }

    /**
     * @return the block to send for a canonical id; resolved for this version, air if nothing fits. An array lookup
     */
    public @NonNull PaletteBlock block(int canonical) {
        return blocks.block(canonical);
    }

    /**
     * @return the runtime id to send for a canonical id, in this palette's {@link #blockNetworkIds()} mode. An array lookup
     */
    public int runtimeId(int canonical) {
        return blocks.runtimeId(canonical);
    }

    /**
     * @return the canonical id of a vanilla or custom block state in this palette, or -1 if it has none (a custom block
     *         of a layer this palette does not have). For vanilla states in palettes of {@link Paletter#bundled()} this
     *         is {@link Paletter#canonicalBlock(BedrockBlockState)}, without a hash lookup
     * @since 1.0.0
     */
    public int canonicalBlock(@NonNull BedrockBlockState state) {
        return state.canonicalIn(blocks.canonical());
    }

    /**
     * @return the block to send for a state, like {@link #block(int)}
     * @throws IllegalArgumentException if the state has no canonical id in this palette
     * @since 1.0.0
     */
    public @NonNull PaletteBlock block(@NonNull BedrockBlockState state) {
        return blocks.block(requireCanonical(state));
    }

    /**
     * @return the runtime id to send for a state, like {@link #runtimeId(int)}
     * @throws IllegalArgumentException if the state has no canonical id in this palette
     * @since 1.0.0
     */
    public int runtimeId(@NonNull BedrockBlockState state) {
        return blocks.runtimeId(requireCanonical(state));
    }

    private int requireCanonical(BedrockBlockState state) {
        int canonical = state.canonicalIn(blocks.canonical());
        if (canonical == -1) {
            throw new IllegalArgumentException(state + " has no canonical id in " + this);
        }
        return canonical;
    }

    public @NonNull CanonicalItems canonicalItems() {
        return items.canonical();
    }

    /**
     * @return the canonical id of an item variant of the newest version (or a custom item of this palette's layers), or -1
     */
    public int canonicalItem(@NonNull ItemVariant variant) {
        return items.canonical().index(variant);
    }

    /**
     * @return the canonical id of the base variant of an item type in this palette, or -1
     * @since 1.0.0
     */
    public int canonicalItem(@NonNull BedrockItemType type) {
        return type.canonicalIn(items.canonical());
    }

    /**
     * @return the canonical id of a variant in this palette, or -1
     * @since 1.0.0
     */
    public int canonicalItem(@NonNull BedrockItemVariant variant) {
        return variant.canonicalIn(items.canonical());
    }

    public int canonicalItemCount() {
        return items.canonical().size();
    }

    /**
     * @return what to send for a canonical item id, or null if the item does not exist in this version and has no fallback
     */
    public @Nullable ResolvedItem item(int canonical) {
        return items.item(canonical);
    }

    /**
     * @return what to send for the base variant of an item type, or null if it has no canonical id here, or does not
     *         exist in this version and has no fallback
     * @since 1.0.0
     */
    public @Nullable ResolvedItem item(@NonNull BedrockItemType type) {
        int canonical = canonicalItem(type);
        return canonical == -1 ? null : items.item(canonical);
    }

    /**
     * @return what to send for a variant, or null if it has no canonical id here (see
     *         {@link PaletteLayer.Builder#variant(BedrockItemVariant)}), or does not exist in this version and has no
     *         fallback
     * @since 1.0.0
     */
    public @Nullable ResolvedItem item(@NonNull BedrockItemVariant variant) {
        int canonical = canonicalItem(variant);
        return canonical == -1 ? null : items.item(canonical);
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
        int canonical = canonicalBlock(normalized);
        BlockResolution resolution = blocks.resolve(normalized);
        BlockResolution.Kind kind = resolution.block() != null ? resolution.kind()
            : blocks.defaultFallback() != null ? BlockResolution.Kind.DEFAULT : BlockResolution.Kind.MISSING;
        PaletteBlock sent = kind == BlockResolution.Kind.MISSING ? null
            : canonical != -1 ? blocks.block(canonical) : resolution.block();
        List<String> chain = new ArrayList<>(List.of(BlockStates.name(normalized)));
        if (sent != null && !sent.identifier().equals(chain.getFirst())) {
            chain.add(sent.identifier());
        }
        return new BlockExplanation(normalized, canonical, kind, List.copyOf(chain), sent == null ? null : sent.getState(),
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
     * Explains what this palette does with an item variant: canonical and runtime id, how it was resolved for this
     * version, the block it is sent with and its place in the creative inventory. Slow, for debugging only.
     *
     * @since 1.0.0
     */
    public @NonNull ItemExplanation explain(@NonNull ItemVariant variant) {
        int canonical = canonicalItem(variant);
        ResolvedItem direct = items.resolve(variant);
        ResolvedItem sent = canonical != -1 ? items.item(canonical) : direct;
        BlockResolution.Kind kind;
        if (direct != null) {
            kind = direct.fallback() ? BlockResolution.Kind.FALLBACK : BlockResolution.Kind.EXACT;
        } else {
            kind = sent != null ? BlockResolution.Kind.DEFAULT : BlockResolution.Kind.MISSING;
        }
        List<String> chain = new ArrayList<>(List.of(variant.identifier()));
        String item = sent == null ? null : sent.definition().getIdentifier();
        if (item != null && !item.equals(variant.identifier())) {
            chain.add(item);
        }
        PaletteBlock block = sent == null ? null : sent.block();
        ItemMatcher matcher = ItemMatcher.id(variant.identifier()).and(ItemMatcher.damage(variant.damage()));
        if (variant.blockState() != null) {
            matcher = matcher.and(ItemMatcher.blockState(variant.blockState()));
        }
        return new ItemExplanation(variant, canonical, kind, List.copyOf(chain), item,
            sent == null ? -1 : sent.definition().getRuntimeId(), sent == null ? 0 : sent.damage(),
            block == null ? null : BlockExplanation.describe(block.getState()), block == null ? -1 : block.getRuntimeId(),
            creativeLayout().locate(matcher).orElse(null));
    }

    /**
     * Like {@link #explain(ItemVariant)}, for a vanilla variant.
     *
     * @since 1.0.0
     */
    public @NonNull ItemExplanation explain(@NonNull BedrockItemVariant variant) {
        return explain(variant.toItemVariant());
    }

    /**
     * @return the canonical id of a stack the client sent, or -1; see {@link ItemPalette#match(ItemData)}
     */
    public int match(@NonNull ItemData data) {
        return items.match(data);
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
        Palette extended = PaletteCompiler.extend(this, layer);
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
