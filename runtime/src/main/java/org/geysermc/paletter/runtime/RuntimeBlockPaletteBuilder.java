package org.geysermc.paletter.runtime;

import org.geysermc.paletter.api.block.CustomBedrockBlock;
import org.geysermc.paletter.api.palette.BlockPalette;

import java.util.ArrayList;
import java.util.List;

final class RuntimeBlockPaletteBuilder implements BlockPalette.Builder {
    private final RuntimeBlockPalette base;
    private final List<CustomBedrockBlock> customs = new ArrayList<>();

    RuntimeBlockPaletteBuilder(RuntimeBlockPalette base) {
        this.base = base;
    }

    @Override
    public BlockPalette.Builder register(CustomBedrockBlock custom) {
        customs.add(custom);
        return this;
    }

    @Override
    public BlockPalette build() {
        return base.withCustoms(customs);
    }
}
