package org.geysermc.paletter;

import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.component.Geometry;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativeEntry;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.component.MaxStackSize;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A layer that changes neither the creative inventory nor what the existing items resolve to reuses what its parent
 * compiled; every other layer rebuilds it.
 */
class ExtendReuseTest {
    private static final Paletter PALETTER = Paletter.bundled();
    private static final CustomBlock LAMP = CustomBlock.builder("reuse:lamp")
        .property(BlockProperty.range("reuse:level", 0, 3))
        .component(Geometry.of("minecraft:geometry.full_block"))
        .build();

    private static Palette parent() {
        return PALETTER.palette(PALETTER.latest().protocolVersion());
    }

    private static void assertSameCanonicalItems(Palette parent, Palette child) {
        assertTrue(child.items().canonical().size() >= parent.items().canonical().size());
        for (int id = 0; id < parent.items().canonical().size(); id++) {
            assertSame(parent.items().item(id), child.items().item(id), "canonical item " + id);
        }
    }

    @Test
    void anEmptyLayerSharesTheCreativeContent() {
        Palette parent = parent();
        Palette child = parent.extend(PaletteLayer.builder("empty").build());
        assertSame(parent.creative(), child.creative());
        assertSame(parent.creativeLayout(), child.creativeLayout());
        assertSameCanonicalItems(parent, child);
    }

    @Test
    void aBlockWithoutCreativePlacementSharesTheCreativeContentAndTheItemTable() {
        Palette parent = parent();
        Palette child = parent.extend(PaletteLayer.builder("block").block(LAMP).build());
        assertNotSame(parent.blocks(), child.blocks());
        assertSame(parent.creative(), child.creative());
        assertSameCanonicalItems(parent, child);
        assertNotNull(child.items().item("reuse:lamp"));
    }

    @Test
    void aLayerThatPlacesAnItemRebuildsTheCreativeContent() {
        Palette parent = parent();
        CustomItem ruby = CustomItem.builder("reuse:ruby").creative(CreativePlacement.loose(CreativeCategory.ITEMS)).build();
        Palette child = parent.extend(PaletteLayer.builder("item").item(ruby).build());
        assertNotSame(parent.creative(), child.creative());
        assertEquals(parent.creative().size() + 1, child.creative().size());
        assertSameCanonicalItems(parent, child);
    }

    @Test
    void anOverrideOfAnItemInTheCreativeInventoryRebuildsIt() {
        Palette parent = parent();
        Palette child = parent.extend(PaletteLayer.builder("override")
            .overrideItem("minecraft:cake", item -> item.components(MaxStackSize.of(1))).build());
        assertNotSame(parent.creative(), child.creative());
        assertNotSame(parent.items().item("minecraft:cake"), child.items().item("minecraft:cake"));
        CreativeEntry cake = child.creative().items().stream().map(data -> child.creative().entry(data.getNetId()))
            .filter(entry -> entry.stack().type().identifier().equals("minecraft:cake")).findFirst().orElseThrow();
        assertSame(child.items().item("minecraft:cake"), cake.item().getDefinition());
    }
}
