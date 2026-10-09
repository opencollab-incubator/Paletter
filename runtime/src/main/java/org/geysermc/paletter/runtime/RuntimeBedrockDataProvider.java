package org.geysermc.paletter.runtime;

import org.geysermc.paletter.PaletterPalettes;
import org.geysermc.paletter.api.BedrockVersion;
import org.geysermc.paletter.api.block.BedrockBlockState;
import org.geysermc.paletter.api.item.BedrockItem;
import org.geysermc.paletter.api.palette.BlockPalette;
import org.geysermc.paletter.api.palette.ItemPalette;
import org.geysermc.paletter.api.spi.BedrockDataProvider;
import org.geysermc.paletter.palette.FullPalette;
import org.geysermc.paletter.palette.PaletteVersion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RuntimeBedrockDataProvider implements BedrockDataProvider {
    private final Map<BedrockVersion, BlockPalette> blockPalettes = new ConcurrentHashMap<>();
    private final Map<BedrockVersion, ItemPalette> itemPalettes = new ConcurrentHashMap<>();

    public RuntimeBedrockDataProvider() {
        try {
            PaletterPalettes.init();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public ItemPalette itemPalette(BedrockVersion version) {
        return itemPalettes.computeIfAbsent(version, this::loadItemPalette);
    }

    private ItemPalette loadItemPalette(BedrockVersion version) {
        PaletteVersion paletteVersion = paletteVersion(version);
        try {
            FullPalette full = PaletterPalettes.getOrLoad(paletteVersion);
            return new RuntimeItemPalette(version, full.itemPalette(), full.itemComponentPalette());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public BlockPalette blockPalette(BedrockVersion version) {
        return blockPalettes.computeIfAbsent(version, this::loadBlockPalette);
    }

    private BlockPalette loadBlockPalette(BedrockVersion version) {
        PaletteVersion paletteVersion = paletteVersion(version);
        try {
            FullPalette full = PaletterPalettes.getOrLoad(paletteVersion);
            return new RuntimeBlockPalette(version, full.blockPalette());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private PaletteVersion paletteVersion(BedrockVersion version) {
        PaletteVersion paletteVersion = SupportedVersions.BY_BEDROCK_VERSION.get(version);
        if (paletteVersion == null) {
            throw new IllegalArgumentException("Unsupported Bedrock version: " + version);
        }
        return paletteVersion;
    }

    @Override
    public Set<BedrockVersion> supportedVersions() {
        return SupportedVersions.BY_BEDROCK_VERSION.keySet();
    }

    @Override
    public Optional<BedrockItem> convert(BedrockItem item, BedrockVersion from, BedrockVersion to) {
        // No fallback graph yet (design doc §3) - this only checks whether the same identifier
        // still resolves in the target version, it doesn't walk renames/removals to a substitute.
        return itemPalette(to).byIdentifier(item.identifier()).map(resolved -> resolved);
    }

    @Override
    public Optional<BedrockBlockState> convert(BedrockBlockState state, BedrockVersion from, BedrockVersion to) {
        // Same limitation as the item overload for a wholly unknown block. For a known block whose
        // exact state combination doesn't exist in the target version, this applies §3's stated
        // per-state fallback: drop down to that block's default state instead.
        BlockPalette target = blockPalette(to);

        BedrockBlockState defaultState;
        try {
            defaultState = target.get(state.block()).defaultState();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        for (BedrockBlockState candidate : target.get(state.block()).states()) {
            if (candidate.states().equals(state.states())) {
                return Optional.of(candidate);
            }
        }
        return Optional.of(defaultState);
    }

    @Override
    public void preload(BedrockVersion... versions) {
        for (BedrockVersion version : versions) {
            blockPalette(version);
            itemPalette(version);
        }
    }

    @Override
    public void evict(BedrockVersion version) {
        blockPalettes.remove(version);
        itemPalettes.remove(version);
    }
}
