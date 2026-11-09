package org.geysermc.paletter;

import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockBlocks;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.geysermc.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The public variant to block lookup: {@link Palette#blockOf} answers for every variant (known, extra, custom) and
 * {@link RegisteredBlockState#key()} turns the answer into a key, without {@code PaletteLayer.Builder#variant(ItemVariant)}.
 */
class VariantBlockTest {
    private static final Paletter PALETTER = Paletter.bundled();

    @Test
    void theKeyOfThePaletteBlockIsTheVersionIndependentBlock() {
        for (var version : PALETTER.versions()) {
            Palette palette = PALETTER.palette(version.protocolVersion());
            BedrockItemVariant stone = BedrockItems.STONE.base();
            RegisteredBlockState block = palette.blockOf(stone);
            assertNotNull(block, version.toString());
            assertSame(PALETTER.blockOf(stone), block.key(), version.toString());
        }
    }

    @Test
    void aVariantRegisteredThroughTheLayerHasTheBlockOfItsItem() {
        BedrockItemVariant extra = BedrockItems.OAK_PLANKS.variantOf(7);
        PaletteLayer layer = PaletteLayer.builder("test:variants").variant(extra).build();
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), layer);
        RegisteredBlockState block = palette.blockOf(extra);
        assertNotNull(block);
        assertEquals(palette.blockOf(BedrockItems.OAK_PLANKS.base()), block);
        // The extra variant has a handle in the palette, and so a key
        assertNotNull(palette.item(extra.type().identifier(), 7));
        assertNotNull(block.key());
    }

    @Test
    void aVariantNobodyRegisteredStillGetsTheBlockOfItsItem() {
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion());
        BedrockItemVariant unknown = BedrockItems.OAK_PLANKS.variantOf(1234);
        assertEquals(palette.blockOf(BedrockItems.OAK_PLANKS.base()), palette.blockOf(unknown));
        assertNull(palette.blockOf(BedrockItems.STICK.variantOf(1234)));
    }

    @Test
    void aCustomBlockItemHasItsBlockKey() {
        CustomBlock lamp = CustomBlock.builder("variant:lamp")
            .creative(CreativePlacement.loose(CreativeCategory.CONSTRUCTION)).build();
        PaletteLayer layer = PaletteLayer.builder("test:custom").block(lamp).build();
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), layer);
        BedrockItemVariant item = BedrockItemType.of("variant:lamp").base();
        RegisteredBlockState block = palette.blockOf(item);
        assertNotNull(block);
        assertEquals("variant:lamp", block.identifier());
        BedrockBlockState key = block.key();
        assertNotNull(key);
        assertEquals("variant:lamp", key.type().identifier());
        assertEquals(block.getRuntimeId(), palette.runtimeId(key));
    }

    @Test
    void aFallbackItemIsSentWithTheBlockOfItsFallback() {
        Palette palette = PALETTER.palette(2168);
        BedrockItemVariant missing = null;
        for (BedrockItemVariant variant : PALETTER.palette(PALETTER.latest().protocolVersion()).itemVariants()) {
            if (palette.isFallback(variant) && PALETTER.blockOf(variant) != null) {
                missing = variant;
                break;
            }
        }
        assertNotNull(missing, "fixture: the oldest version lacks a block item of the newest");
        RegisteredBlockState block = palette.blockOf(missing);
        assertNotNull(block);
        assertEquals(palette.blockOf(palette.fallbackOf(missing)), block);
        assertEquals(BedrockBlocks.UNKNOWN, block.key().type());
    }
}
