package org.geysermc.paletter.runtime;

import org.geysermc.paletter.api.item.BedrockItem;
import org.geysermc.paletter.api.item.CustomBedrockItem;
import org.geysermc.paletter.api.palette.ItemPalette;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class RuntimeItemPaletteBuilder implements ItemPalette.Builder {
    private final RuntimeItemPalette base;
    private final List<CustomBedrockItem> customs = new ArrayList<>();
    private final Map<BedrockItem, BedrockItem> fallbacks = new HashMap<>();

    RuntimeItemPaletteBuilder(RuntimeItemPalette base) {
        this.base = base;
    }

    @Override
    public ItemPalette.Builder register(CustomBedrockItem custom) {
        customs.add(custom);
        return this;
    }

    @Override
    public ItemPalette.Builder fallback(BedrockItem unresolved, BedrockItem fallback) {
        fallbacks.put(unresolved, fallback);
        return this;
    }

    @Override
    public ItemPalette build() {
        return base.withCustomsAndFallbacks(customs, fallbacks);
    }
}
