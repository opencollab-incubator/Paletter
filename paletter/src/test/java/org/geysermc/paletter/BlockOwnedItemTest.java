package org.geysermc.paletter;

import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativeGroup;
import org.geysermc.paletter.creative.CreativeItem;
import org.geysermc.paletter.creative.CreativeOrdering;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.creative.ItemMatcher;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.block.component.MenuCategory;
import org.geysermc.paletter.item.component.BlockPlacer;
import org.geysermc.paletter.item.component.Icon;
import org.geysermc.paletter.vanilla.BedrockBlocks;
import org.geysermc.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A custom block owns its item, and where it goes in the creative inventory. */
class BlockOwnedItemTest {
    private static final Paletter PALETTER = Paletter.bundled();
    private static final int PROTOCOL = PALETTER.latest().protocolVersion();

    private static List<BedrockItemStack> stacksOf(Palette palette, String identifier) {
        return palette.creativeLayout().stacks().filter(stack -> stack.type().identifier().equals(identifier)).toList();
    }

    @Test
    void theMenuCategoryAloneDecidesWhetherContentIsInCreative() {
        assertSame(CreativePlacement.DEFAULT, CustomItem.builder("own:item").build().creativePlacement());
        assertSame(CreativePlacement.DEFAULT, CustomBlock.builder("own:block").build().creativePlacement());
        assertThrows(NullPointerException.class, () -> CustomItem.builder("own:item").creativePlacement(null));
        assertThrows(NullPointerException.class, () -> CustomBlock.builder("own:block").creativePlacement(null));
        assertThrows(NullPointerException.class, () -> CreativePlacement.after((ItemMatcher) null));

        MenuCategory none = MenuCategory.builder().category(MenuCategory.Category.NONE).build();
        CustomBlock hidden = CustomBlock.builder("own:hidden").build();
        CustomBlock menuNone = CustomBlock.builder("own:menu_none").menuCategory(none).build();
        CustomBlock shown = CustomBlock.builder("own:shown").menuCategory(CreativeCategory.NATURE).build();
        CustomItem item = CustomItem.builder("own:item").build();
        CustomItem itemNone = CustomItem.builder("own:item_none").menuCategory(org.geysermc.paletter.item.component.MenuCategory.builder()
            .category(org.geysermc.paletter.item.component.MenuCategory.Category.NONE).build()).build();
        CustomItem itemShown = CustomItem.builder("own:item_shown").menuCategory(CreativeCategory.ITEMS).build();
        PaletteLayer layer = PaletteLayer.builder("menu").block(hidden).block(menuNone).block(shown)
            .item(item).item(itemNone).item(itemShown).build();
        // The ordering never decides presence
        for (Palette palette : List.of(Palettes.palette(PROTOCOL, layer),
            Paletter.builder().creativeOrdering(CreativeOrdering.APPEND).layer(layer).build().palette(PROTOCOL))) {
            assertTrue(stacksOf(palette, "own:hidden").isEmpty());
            assertTrue(stacksOf(palette, "own:menu_none").isEmpty());
            assertEquals(1, stacksOf(palette, "own:shown").size());
            assertTrue(stacksOf(palette, "own:item").isEmpty());
            assertTrue(stacksOf(palette, "own:item_none").isEmpty());
            assertEquals(1, stacksOf(palette, "own:item_shown").size());
        }
    }

    @Test
    void aPlacementOnlyAdjustsThePositionWithinTheMenuCategory() {
        CustomItem ruby = CustomItem.builder("own:after_hopper").menuCategory(CreativeCategory.ITEMS)
            .creativePlacement(CreativePlacement.after(BedrockItems.HOPPER_MINECART)).build();
        CustomItem missing = CustomItem.builder("own:missing_anchor").menuCategory(CreativeCategory.ITEMS)
            .creativePlacement(CreativePlacement.after(ItemMatcher.id("own:nowhere"))).build();
        CustomItem hidden = CustomItem.builder("own:hidden_anchor").creativePlacement(CreativePlacement.after(BedrockItems.HOPPER_MINECART)).build();
        Palette palette = Palettes.palette(PROTOCOL, PaletteLayer.builder("anchors").item(ruby).item(missing).item(hidden).build());
        List<String> ids = palette.creativeLayout().stacks().map(stack -> stack.type().identifier()).toList();
        assertEquals(ids.indexOf("minecraft:hopper_minecart") + 1, ids.indexOf("own:after_hopper"));
        assertTrue(ids.contains("own:missing_anchor"), "an anchor that matches nothing falls back to the default position");
        assertTrue(palette.report().warnings().stream().anyMatch(entry -> entry.message().contains("own:missing_anchor")
            && entry.message().contains("does not exist")), "and is reported");
        assertEquals(1, palette.report().warnings().stream().filter(entry -> entry.message().contains("does not exist")).count());
        assertFalse(ids.contains("own:hidden_anchor"), "an anchor without a menu category does not put it in creative");
    }

    @Test
    void anItemThatReplacesTheBlockItemIsAnOrdinaryItemInItsOwnCategory() {
        // BDS 1.26.63.1 (data/bds/1.26.63.1/addon, paletter:plain_block): block construction, item items, plain stack
        CustomBlock block = CustomBlock.builder("own:driven").menuCategory(CreativeCategory.CONSTRUCTION).build();
        CustomItem item = CustomItem.builder("own:driven").component(Icon.of("driven"))
            .component(BlockPlacer.builder().block("own:driven").replaceBlockItem(true).build())
            .menuCategory(CreativeCategory.ITEMS).build();
        CustomBlock plain = CustomBlock.builder("own:plain").menuCategory(CreativeCategory.CONSTRUCTION).build();
        Palette palette = Palettes.palette(PROTOCOL, PaletteLayer.builder("driven").block(block).block(plain).item(item).build());
        assertEquals(MenuCategory.Category.CONSTRUCTION, block.menuCategory().category(), "the block keeps its own category");
        assertNull(stacksOf(palette, "own:driven").getFirst().blockState());
        assertNotNull(stacksOf(palette, "own:plain").getFirst().blockState());
        assertEquals(1, stacksOf(palette, "own:driven").size());
        assertSame(item.type(), palette.itemOf(block.type()));
        assertSame(item.type(), palette.itemDefinition(item.type()).key());
        assertSame(block.type().defaultState(), palette.blockOf(item.type()));
        assertTrue(palette.itemDefinition(item.type()).getRuntimeId() > 0, "a data driven item, numbered with the items");
    }

    private static CustomItem replacing(String identifier) {
        return CustomItem.builder(identifier).component(Icon.of("x"))
            .component(BlockPlacer.builder().block(identifier).replaceBlockItem(true).build()).build();
    }

    @Test
    void aReplacingItemMustBeInItsBlocksLayer() {
        for (CustomBlock.BlockItem blockItem : List.of(CustomBlock.BlockItem.SIMPLE, CustomBlock.BlockItem.NONE)) {
            CustomBlock block = CustomBlock.builder("own:early").item(blockItem).build();
            PaletteLayer early = PaletteLayer.builder("early").block(block).build();
            PaletteLayer later = PaletteLayer.builder("later").item(replacing("own:early")).build();
            PaletteException e = assertThrows(PaletteException.class, () -> Palettes.palette(PROTOCOL, early, later));
            assertTrue(e.getMessage().contains("a replacing item must be in its block's layer"), e.getMessage());
            assertEquals("later", e.layer());
            assertEquals("own:early", e.identifier());
            // Through Palette#extend directly
            Palette base = PALETTER.palette(PROTOCOL).extend(early);
            PaletteException extended = assertThrows(PaletteException.class, () -> base.extend(later));
            assertTrue(extended.getMessage().contains("a replacing item must be in its block's layer"), extended.getMessage());
            assertEquals("later", extended.layer());
            assertEquals("own:early", extended.identifier());
        }
    }

    @Test
    void aReplacingItemContradictsABlockWithoutItem() {
        CustomBlock block = CustomBlock.builder("own:none").item(CustomBlock.BlockItem.NONE).build();
        PaletteException e = assertThrows(PaletteException.class,
            () -> Palettes.palette(PROTOCOL, PaletteLayer.builder("both").block(block).item(replacing("own:none")).build()));
        assertTrue(e.getMessage().contains("BlockItem.NONE"), e.getMessage());
        assertEquals("both", e.layer());
        assertEquals("own:none", e.identifier());
    }

    @Test
    void aReplacingItemIsPlacedByItsOwnMenuCategoryUnderAppendToo() {
        CustomBlock block = CustomBlock.builder("own:driven").menuCategory(CreativeCategory.CONSTRUCTION).build();
        CustomItem item = CustomItem.builder("own:driven").component(Icon.of("x"))
            .component(BlockPlacer.builder().block("own:driven").replaceBlockItem(true).build())
            .menuCategory(CreativeCategory.ITEMS).build();
        PaletteLayer layer = PaletteLayer.builder("driven").block(block).item(item).build();
        Palette append = Paletter.builder().creativeOrdering(CreativeOrdering.APPEND).layer(layer).build().palette(PROTOCOL);
        assertEquals(1, stacksOf(append, "own:driven").size());
        assertNull(stacksOf(append, "own:driven").getFirst().blockState());
        assertSame(item.type(), append.itemOf(block.type()));
        assertTrue(append.creativeLayout().nodes(CreativeCategory.ITEMS).stream().anyMatch(node ->
            node instanceof CreativeItem entry && entry.stack().type().identifier().equals("own:driven")
            || node instanceof CreativeGroup group && group.stacks().stream().anyMatch(stack -> stack.type().identifier().equals("own:driven"))));
    }

    @Test
    void aMenuCategoryWithoutACategoryIsNotInCreative() {
        // The wire sends none for it; compiling must not fail
        CustomItem item = CustomItem.builder("own:no_category").menuCategory(
            org.geysermc.paletter.item.component.MenuCategory.builder().group("itemGroup.name.x").build()).build();
        Palette palette = Palettes.palette(PROTOCOL, PaletteLayer.builder("none").item(item).build());
        assertTrue(stacksOf(palette, "own:no_category").isEmpty());
        assertNotNull(palette.itemDefinition("own:no_category"));
    }

    @Test
    void theFlagWithoutABlockIsReported() {
        Palette palette = Palettes.palette(PROTOCOL, PaletteLayer.builder("alone").item(replacing("own:alone")).build());
        assertTrue(palette.report().warnings().stream().anyMatch(entry -> entry.message().contains("no custom block of that name")));
        assertNull(palette.itemOf(CustomBlock.builder("own:alone").build().type()));
        assertNotNull(palette.itemDefinition("own:alone"));
    }

    @Test
    void aReplacingItemHasItsOwnMenuCategoryAndTheBlockKeepsItsOwn() {
        // The item hidden, the block shown, and the other way round: neither decides for the other
        CustomBlock shown = CustomBlock.builder("own:shown_block").menuCategory(CreativeCategory.CONSTRUCTION).build();
        CustomBlock hidden = CustomBlock.builder("own:hidden_block").build();
        Palette palette = Palettes.palette(PROTOCOL, PaletteLayer.builder("both").block(shown).block(hidden)
            .item(replacing("own:shown_block")).item(CustomItem.builder("own:hidden_block").component(Icon.of("x"))
                .component(BlockPlacer.builder().block("own:hidden_block").replaceBlockItem(true).build())
                .menuCategory(CreativeCategory.ITEMS).build()).build());
        assertTrue(stacksOf(palette, "own:shown_block").isEmpty(), "the replacing item has no menu category");
        assertEquals(1, stacksOf(palette, "own:hidden_block").size(), "the item's category counts, not the block's");
    }

    @Test
    void anItemWithTheBlocksIdentifierNeedsTheFlagToReplaceTheBlockItem() {
        CustomBlock block = CustomBlock.builder("own:both").build();
        CustomItem item = CustomItem.builder("own:both").component(Icon.of("both")).build();
        assertThrows(PaletteException.class, () -> Palettes.palette(PROTOCOL, PaletteLayer.builder("both").block(block).item(item).build()));
    }

    @Test
    void itemOfIsTheBlocksItem() {
        CustomBlock lamp = CustomBlock.builder("own:lamp").build();
        CustomBlock none = CustomBlock.builder("own:none").item(CustomBlock.BlockItem.NONE).build();
        Palette palette = Palettes.palette(PROTOCOL, PaletteLayer.builder("items").block(lamp).block(none).build());
        assertSame(lamp.itemType(), palette.itemOf(lamp.type()));
        assertNull(palette.itemOf(none.type()));
        assertEquals("minecraft:oak_log", palette.itemOf(BedrockBlocks.OAK_LOG).identifier());
        assertEquals("minecraft:acacia_sign", palette.itemOf(PALETTER.vanilla(PALETTER.latest()).blockOf(BedrockItems.ACACIA_SIGN).type()).identifier());
        // The Script API relation gives the double slab the slab item
        assertEquals("minecraft:acacia_slab", palette.itemOf(BedrockBlocks.ACACIA_DOUBLE_SLAB).identifier());
        assertNull(PALETTER.vanilla(PALETTER.latest()).itemOf(lamp.type()));
    }

    @Test
    void itemOfFindsTheBlockOfAnEarlierLayerThroughALaterPalette() {
        CustomBlock lamp = CustomBlock.builder("own:early").build();
        Palette base = PALETTER.palette(PROTOCOL).extend(PaletteLayer.builder("early").block(lamp).build());
        Palette later = base.extend(PaletteLayer.builder("later").block(CustomBlock.builder("own:late").build()).build());
        assertSame(lamp.itemType(), later.itemOf(lamp.type()));
    }
}
