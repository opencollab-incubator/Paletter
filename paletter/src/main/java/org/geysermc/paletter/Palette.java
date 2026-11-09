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
import org.geysermc.paletter.block.BlockResolution;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.internal.CanonicalBlocks;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.creative.CreativeContent;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.creative.ItemMatcher;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.CanonicalItems;
import org.geysermc.paletter.item.ItemRegistry;
import org.geysermc.paletter.item.ItemVariant;
import org.geysermc.paletter.item.ResolvedItem;
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
 * {@link BedrockItemVariant}): {@link #block(NbtMap)} and {@link #item(ItemVariant)} find them once, then
 * {@link #runtimeId(BedrockBlockState)} and {@link #block(BedrockBlockState)} for blocks, {@link #item(BedrockItemVariant)}
 * and {@link #match(ItemData)} for items. They are field reads and array lookups that do not allocate. The ids behind
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
    private final Map<PaletteLayer, WeakReference<Palette>> extensions = new WeakHashMap<>();

    Palette(VanillaData data, List<PaletteLayer> layers, BlockRegistry blocks, ItemRegistry items,
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

    @Internal
    public @NonNull BlockRegistry blocks() {
        return blocks;
    }

    @Internal
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
     * @param variant an item variant of any bundled version (mapped forward as in {@link Paletter#item(ItemVariant)})
     *                or of a custom item of this palette's layers
     * @return its handle, or null if there is no equivalent (see {@link #explainUnknown(ItemVariant)})
     * @since 1.0.0
     */
    public @Nullable BedrockItemVariant item(@NonNull ItemVariant variant) {
        return items.canonical().handle(Canonicals.item(items.canonical(), blocks.canonical(), variant), blocks.canonical());
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
     * @return why an item variant has no handle in this palette, or null if it has one
     * @since 1.0.0
     */
    public @Nullable String explainUnknown(@NonNull ItemVariant variant) {
        Forwarding forwarding = Canonicals.forwardItem(items.canonical(), blocks.canonical(), variant);
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
            variants.add(canonical.handle(id, blocks.canonical()));
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
     * The block state an item variant is sent with in this palette's version: Paletter alone decides it, see
     * {@link ItemRegistry#blockOf}. A block state of the variant wins over the item's block.
     *
     * @return the block, or null if the item is not a block item (or does not exist in this version and has no fallback)
     * @since 1.0.0
     */
    public @Nullable RegisteredBlockState blockOf(@NonNull ItemVariant variant) {
        return items.blockOf(variant);
    }

    /**
     * @return what to send for the base variant of an item type, or null if it is not in this palette, or does not
     *         exist in this version and has no fallback
     * @since 1.0.0
     */
    public @Nullable ResolvedItem item(@NonNull BedrockItemType type) {
        return item(type.base());
    }

    /**
     * @return what to send for a variant, or null if it is not in this palette (see
     *         {@link PaletteLayer.Builder#variant(BedrockItemVariant)}), or does not exist in this version and has no
     *         fallback
     * @since 1.0.0
     */
    public @Nullable ResolvedItem item(@NonNull BedrockItemVariant variant) {
        int canonical = variant.canonicalIn(items.canonical());
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
        int canonical = Canonicals.block(blocks.canonical(), normalized);
        BlockResolution resolution = blocks.resolve(normalized);
        BlockResolution.Kind kind = resolution.block() != null ? resolution.kind()
            : blocks.defaultFallback() != null ? BlockResolution.Kind.DEFAULT : BlockResolution.Kind.MISSING;
        RegisteredBlockState sent = kind == BlockResolution.Kind.MISSING ? null
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
     * Explains what this palette does with an item variant: canonical and runtime id, how it was resolved for this
     * version, the block it is sent with and its place in the creative inventory. Slow, for debugging only.
     *
     * @since 1.0.0
     */
    public @NonNull ItemExplanation explain(@NonNull ItemVariant variant) {
        int canonical = Canonicals.item(items.canonical(), blocks.canonical(), variant);
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
        RegisteredBlockState block = sent == null ? null : sent.block();
        ItemMatcher matcher = ItemMatcher.id(variant.identifier()).and(ItemMatcher.damage(variant.damage()));
        if (variant.blockState() != null) {
            matcher = matcher.and(ItemMatcher.blockState(variant.blockState()));
        }
        return new ItemExplanation(variant, items.canonical().handle(canonical, blocks.canonical()), kind, List.copyOf(chain), item,
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
     * @return the variant a stack the client sent is, or null; see {@link ItemRegistry#match(ItemData)}
     * @since 1.0.0
     */
    public @Nullable BedrockItemVariant match(@NonNull ItemData data) {
        return items.canonical().handle(items.match(data), blocks.canonical());
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
