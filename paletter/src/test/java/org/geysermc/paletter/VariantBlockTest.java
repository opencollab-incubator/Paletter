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
 * The public variant to block lookup: {@link Palette#blockOf} answers for every variant (known, extra, custom) with the block's key, without {@code PaletteLayer.Builder#variant(ItemVariant)}.
 */
class VariantBlockTest {
    private static final Paletter PALETTER = Paletter.bundled();

    @Test
    void theKeyOfThePaletteBlockIsTheVersionIndependentBlock() {
        for (var version : PALETTER.versions()) {
            Palette palette = PALETTER.palette(version.protocolVersion());
            BedrockItemVariant stone = BedrockItems.STONE.base();
            RegisteredBlockState block = palette.resolve(stone).block();
            assertNotNull(block, version.toString());
            assertSame(PALETTER.blockOf(stone), palette.blocks().key(block), version.toString());
        }
    }

    @Test
    void aVariantRegisteredThroughTheLayerHasTheBlockOfItsItem() {
        BedrockItemVariant extra = BedrockItems.OAK_PLANKS.variantOf(7);
        PaletteLayer layer = PaletteLayer.builder("test:variants").variant(extra).build();
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), layer);
        RegisteredBlockState block = palette.resolve(extra).block();
        assertNotNull(block);
        assertEquals(palette.resolve(BedrockItems.OAK_PLANKS.base()).block(), block);
        // The extra variant has a handle in the palette, and so a key
        assertNotNull(palette.item(extra.type().identifier(), 7));
        assertNotNull(palette.blocks().key(block));
    }

    @Test
    void aVariantNobodyRegisteredStillGetsTheBlockOfItsItem() {
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion());
        BedrockItemVariant unknown = BedrockItems.OAK_PLANKS.variantOf(1234);
        assertEquals(palette.resolve(BedrockItems.OAK_PLANKS.base()).block(), palette.resolve(unknown).block());
        assertNull(palette.resolve(BedrockItems.STICK.variantOf(1234)).block());
    }

    @Test
    void aCustomBlockItemHasItsBlockKey() {
        CustomBlock lamp = CustomBlock.builder("variant:lamp")
            .creative(CreativePlacement.loose(CreativeCategory.CONSTRUCTION)).build();
        PaletteLayer layer = PaletteLayer.builder("test:custom").block(lamp).build();
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), layer);
        BedrockItemVariant item = BedrockItemType.of("variant:lamp").base();
        RegisteredBlockState block = palette.resolve(item).block();
        assertNotNull(block);
        assertEquals("variant:lamp", block.identifier());
        BedrockBlockState key = palette.blocks().key(block);
        assertNotNull(key);
        assertEquals("variant:lamp", key.type().identifier());
        assertEquals(block.getRuntimeId(), palette.runtimeId(key));
    }

    @Test
    void aFallbackItemIsSentWithTheBlockOfItsFallback() {
        Palette palette = PALETTER.palette(2168);
        BedrockItemVariant missing = null;
        for (BedrockItemVariant variant : PALETTER.palette(PALETTER.latest().protocolVersion()).itemVariants()) {
            if (palette.resolve(variant).fallback() && PALETTER.blockOf(variant) != null) {
                missing = variant;
                break;
            }
        }
        assertNotNull(missing, "fixture: the oldest version lacks a block item of the newest");
        RegisteredBlockState block = palette.resolve(missing).block();
        assertNotNull(block);
        assertEquals(palette.resolve(palette.resolve(missing).variant()).block(), block);
        assertEquals(BedrockBlocks.UNKNOWN, palette.blocks().key(block).type());
    }

    @Test
    void theBlockKeyIsExplicit() {
        BedrockItemVariant planks = BedrockItems.OAK_PLANKS.base();
        for (var version : PALETTER.versions()) {
            Palette palette = PALETTER.palette(version.protocolVersion());
            BedrockBlockState key = palette.blockOf(planks);
            assertNotNull(key, version.toString());
            // The same key Paletter gives without a palette, and the palette's runtime id of it
            assertSame(PALETTER.blockOf(planks), key, version.toString());
            assertEquals(palette.resolve(planks).block().getRuntimeId(), palette.runtimeId(key), version.toString());
        }
        assertNull(PALETTER.palette(PALETTER.latest().protocolVersion()).blockOf(BedrockItems.STICK.base()));

        // A fallback: the key of the block its fallback is sent with, where Paletter#blockOf knows the real block
        Palette oldest = PALETTER.palette(2168);
        for (BedrockItemVariant variant : PALETTER.palette(PALETTER.latest().protocolVersion()).itemVariants()) {
            if (oldest.resolve(variant).fallback() && PALETTER.blockOf(variant) != null) {
                assertEquals(BedrockBlocks.UNKNOWN, oldest.blockOf(variant).type());
                assertNotNull(PALETTER.blockOf(variant));
                break;
            }
        }
    }

    @Test
    void theBlockKeyOfACustomBlockItemIsTheCustomState() {
        CustomBlock lamp = CustomBlock.builder("variant:keyed")
            .creative(CreativePlacement.loose(CreativeCategory.CONSTRUCTION)).build();
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("test:keyed").block(lamp).build());
        BedrockItemVariant item = BedrockItemType.of("variant:keyed").base();
        BedrockBlockState key = palette.blockOf(item);
        assertNotNull(key);
        assertSame(lamp.type(), key.type());
        assertNull(PALETTER.blockOf(item), "Paletter alone does not know custom content");
    }
}
