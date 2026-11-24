package dev.opencollab.paletter;

import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockBlocks;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import dev.opencollab.paletter.vanilla.BedrockItems;
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
            BedrockItemVariant stone = BedrockItems.STONE.variant();
            RegisteredBlockState block = palette.resolve(stone).block();
            assertNotNull(block, version.toString());
            assertSame(PALETTER.vanilla(PALETTER.latest()).blockOf(stone), palette.blocks().key(block), version.toString());
        }
    }

    @Test
    void aVariantRegisteredThroughTheLayerHasTheBlockOfItsItem() {
        BedrockItemVariant extra = BedrockItems.OAK_PLANKS.variant(7);
        PaletteLayer layer = PaletteLayer.builder("test:variants").variant(extra).build();
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), layer);
        RegisteredBlockState block = palette.resolve(extra).block();
        assertNotNull(block);
        assertEquals(palette.resolve(BedrockItems.OAK_PLANKS).block(), block);
        // The extra variant has a handle in the palette, and so a key
        assertNotNull(palette.itemVariant(extra.type().identifier(), 7, Lookup.RENAMED));
        assertNotNull(palette.blocks().key(block));
    }

    @Test
    void aVariantNobodyRegisteredStillGetsTheBlockOfItsItem() {
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion());
        BedrockItemVariant unknown = BedrockItems.OAK_PLANKS.variant(1234);
        assertEquals(palette.resolve(BedrockItems.OAK_PLANKS).block(), palette.resolve(unknown).block());
        assertNull(palette.resolve(BedrockItems.STICK.variant(1234)).block());
    }

    @Test
    void aCustomBlockItemHasItsBlockKey() {
        CustomBlock lamp = CustomBlock.builder("variant:lamp")
            .menuCategory(CreativeCategory.CONSTRUCTION).build();
        PaletteLayer layer = PaletteLayer.builder("test:custom").block(lamp).build();
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), layer);
        BedrockItemVariant item = Access.vanilla().itemType("variant:lamp").variant();
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
            if (palette.resolve(variant).fallback() && PALETTER.vanilla(PALETTER.latest()).blockOf(variant) != null) {
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
        BedrockItemVariant planks = BedrockItems.OAK_PLANKS.variant();
        for (var version : PALETTER.versions()) {
            Palette palette = PALETTER.palette(version.protocolVersion());
            BedrockBlockState key = palette.blockOf(planks);
            assertNotNull(key, version.toString());
            // The same key Paletter gives without a palette, and the palette's runtime id of it
            assertSame(PALETTER.vanilla(PALETTER.latest()).blockOf(planks), key, version.toString());
            assertEquals(palette.resolve(planks).block().getRuntimeId(), palette.runtimeId(key), version.toString());
        }
        assertNull(PALETTER.palette(PALETTER.latest().protocolVersion()).blockOf(BedrockItems.STICK.variant()));

        // A fallback: the key of the block its fallback is sent with, where Paletter#blockOf knows the real block
        Palette oldest = PALETTER.palette(2168);
        for (BedrockItemVariant variant : PALETTER.palette(PALETTER.latest().protocolVersion()).itemVariants()) {
            if (oldest.resolve(variant).fallback() && PALETTER.vanilla(PALETTER.latest()).blockOf(variant) != null) {
                assertEquals(BedrockBlocks.UNKNOWN, oldest.blockOf(variant).type());
                assertNotNull(PALETTER.vanilla(PALETTER.latest()).blockOf(variant));
                break;
            }
        }
    }

    @Test
    void theBlockKeyOfACustomBlockItemIsTheCustomState() {
        CustomBlock lamp = CustomBlock.builder("variant:keyed")
            .menuCategory(CreativeCategory.CONSTRUCTION).build();
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("test:keyed").block(lamp).build());
        BedrockItemVariant item = Access.vanilla().itemType("variant:keyed").variant();
        BedrockBlockState key = palette.blockOf(item);
        assertNotNull(key);
        assertSame(lamp.type(), key.type());
    }
}
