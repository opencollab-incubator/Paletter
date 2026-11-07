package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.geysermc.paletter.block.BlockPalette;
import org.geysermc.paletter.creative.CreativeContent;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.item.ItemPalette;

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
 * palette.applyTo(startGamePacket);              // block properties
 * session.sendPacket(palette.itemComponentPacket());
 * session.sendPacket(palette.creativeContentPacket());
 * }</pre>
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
     * Adds the block properties (vanilla data driven and custom blocks) to a start game packet.
     */
    public void applyTo(@NonNull StartGamePacket packet) {
        packet.getBlockProperties().addAll(blocks.blockProperties());
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
        return "Palette{" + version() + ", layers=" + ids + ", " + blocks.size() + " block states, "
            + items.definitions().size() + " items, " + creative.size() + " creative entries}";
    }
}
