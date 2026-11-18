package dev.opencollab.paletter;

import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.component.Geometry;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.creative.CreativeEntry;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.component.MaxStackSize;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        CustomItem ruby = CustomItem.builder("reuse:ruby").menuCategory(CreativeCategory.ITEMS).build();
        Palette child = parent.extend(PaletteLayer.builder("item").item(ruby).build());
        assertNotSame(parent.creative(), child.creative());
        assertEquals(parent.creative().size() + 1, child.creative().size());
        assertSameCanonicalItems(parent, child);
    }

    @Test
    void anOverrideOfAnItemInTheCreativeInventoryRebuildsIt() {
        Palette parent = parent();
        Palette child = parent.extend(PaletteLayer.builder("override")
            .overrideItem("minecraft:cake", item -> item.modifyComponents(MaxStackSize.of(1))).build());
        assertNotSame(parent.creative(), child.creative());
        assertNotSame(parent.items().item("minecraft:cake"), child.items().item("minecraft:cake"));
        CreativeEntry cake = child.creative().items().stream().map(data -> child.creative().entry(data.getNetId()))
            .filter(entry -> entry.stack().type().identifier().equals("minecraft:cake")).findFirst().orElseThrow();
        assertSame(child.items().item("minecraft:cake"), cake.item().getDefinition());
    }

    private static org.cloudburstmc.protocol.bedrock.data.BlockPropertyData properties(Palette palette, String identifier) {
        return palette.blocks().blockProperties().stream().filter(data -> data.getName().equals(identifier)).findFirst().orElseThrow();
    }

    @Test
    void aBlockKeepsItsEncodingWhileItsBlockIdStays() {
        Palette parent = parent().extend(PaletteLayer.builder("lamp").block(LAMP).build());
        // "reuse:aa" sorts after "reuse:lamp": BDS numbers by identifier in descending order, so the lamp keeps its id
        Palette child = parent.extend(PaletteLayer.builder("after").block(CustomBlock.builder("reuse:aa").build()).build());
        assertEquals(parent.blocks().customBlockId("reuse:lamp"), child.blocks().customBlockId("reuse:lamp"));
        assertSame(properties(parent, "reuse:lamp"), properties(child, "reuse:lamp"));
    }

    @Test
    void aBlockIsEncodedAgainWhenItsBlockIdChanges() {
        Palette parent = parent().extend(PaletteLayer.builder("lamp").block(LAMP).build());
        // "reuse:zz" sorts before "reuse:lamp": the lamp's id moves up
        Palette child = parent.extend(PaletteLayer.builder("before").block(CustomBlock.builder("reuse:zz").build()).build());
        assertNotEquals(parent.blocks().customBlockId("reuse:lamp"), child.blocks().customBlockId("reuse:lamp"));
        assertNotSame(properties(parent, "reuse:lamp"), properties(child, "reuse:lamp"));
        assertEquals(child.blocks().customBlockId("reuse:lamp"), properties(child, "reuse:lamp").getProperties().getCompound("vanilla_block_data").getInt("block_id"));
    }
}
