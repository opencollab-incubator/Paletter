package org.geysermc.paletter.palette;

import org.geysermc.paletter.palette.block.BlockPalette;
import org.geysermc.paletter.palette.block.BlockPropertiesPalette;
import org.geysermc.paletter.palette.item.CreativeItemPalette;
import org.geysermc.paletter.palette.item.ItemComponentPalette;
import org.geysermc.paletter.palette.item.ItemPalette;

public record FullPalette(
        PaletteVersion version,
        BlockPalette blockPalette,
        BlockPropertiesPalette blockPropertiesPalette,
        ItemPalette itemPalette,
        ItemComponentPalette itemComponentPalette,
        CreativeItemPalette creativeItemPalette
) {
}
