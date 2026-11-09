package org.geysermc.paletter;

import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativeGroup;
import org.geysermc.paletter.creative.CreativeItem;
import org.geysermc.paletter.creative.CreativeNode;
import org.geysermc.paletter.creative.CreativeOrdering;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.creative.ItemMatcher;
import org.geysermc.paletter.item.CustomItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CreativeOrdering}; the comparison with real BDS captures is {@code CreativeOrderCheckTest} in the generator.
 */
class CreativeOrderingTest {
    private static final Paletter PALETTER = Paletter.bundled();
    private static final int LATEST = 2225;

    private static CustomItem item(String identifier, CreativePlacement placement) {
        return CustomItem.builder(identifier).creative(placement).build();
    }

    private static List<String> stacks(Palette palette) {
        List<String> stacks = new java.util.ArrayList<>();
        palette.creativeLayout().forEachStack(stack -> stacks.add(stack.type().identifier() + ":" + stack.variant().aux()));
        return stacks;
    }

    @Test
    void bdsIsTheDefault() {
        PaletteLayer layer = PaletteLayer.builder("x")
            .item(item("test:item", CreativePlacement.loose(CreativeCategory.CONSTRUCTION))).build();
        Palette explicit = Paletter.builder().creativeOrdering(CreativeOrdering.BDS).layer(layer).build().palette(LATEST);
        Palette defaulted = Palettes.palette(LATEST, layer);
        assertEquals(stacks(explicit), stacks(defaulted));
    }

    @Test
    void bdsAppendsToTheGroupOfTheCategoryInDefinitionOrderAndStripsTheMinecraftNamespace() {
        PaletteLayer layer = PaletteLayer.builder("swords")
            .item(item("test:sword_c", CreativePlacement.group(CreativeCategory.EQUIPMENT, "minecraft:itemGroup.name.sword")))
            .item(item("test:sword_a", CreativePlacement.group(CreativeCategory.EQUIPMENT, "itemGroup.name.sword")))
            .build();
        Palette palette = Palettes.palette(LATEST, layer);
        CreativeGroup swords = palette.creativeLayout().group("itemGroup.name.sword").orElseThrow();
        List<BedrockItemStack> stacks = swords.stacks();
        assertEquals("test:sword_c", stacks.get(stacks.size() - 2).type().identifier());
        assertEquals("test:sword_a", stacks.getLast().type().identifier());
    }

    @Test
    void bdsCreatesMissingGroupsPerCategoryAtTheEnd() {
        PaletteLayer layer = PaletteLayer.builder("groups")
            .item(item("test:wrong_category", CreativePlacement.group(CreativeCategory.ITEMS, "minecraft:itemGroup.name.sword")))
            .item(item("test:new_group", CreativePlacement.group(CreativeCategory.NATURE, "test:itemGroup.new")))
            .item(item("test:loose", CreativePlacement.loose(CreativeCategory.NATURE)))
            .build();
        Palette palette = Palettes.palette(LATEST, layer);
        List<CreativeNode> items = palette.creativeLayout().nodes(CreativeCategory.ITEMS);
        CreativeGroup sword = assertInstanceOf(CreativeGroup.class, items.getLast());
        assertEquals("itemGroup.name.sword", sword.name());
        assertEquals(List.of(BedrockItemStack.of(BedrockItemType.of("test:wrong_category"))), sword.stacks());

        List<CreativeNode> nature = palette.creativeLayout().nodes(CreativeCategory.NATURE);
        assertEquals(new CreativeItem(BedrockItemStack.of(BedrockItemType.of("test:loose"))), nature.getLast());
        CreativeGroup created = assertInstanceOf(CreativeGroup.class, nature.get(nature.size() - 2));
        assertEquals("test:itemGroup.new", created.name());
    }

    @Test
    void anchorsStillWin() {
        PaletteLayer layer = PaletteLayer.builder("anchor")
            .item(item("test:after_hopper", CreativePlacement.group(CreativeCategory.ITEMS, "itemGroup.name.minecart")
                .after(ItemMatcher.id("minecraft:hopper_minecart"))))
            .build();
        Palette palette = Palettes.palette(LATEST, layer);
        String group = palette.creativeLayout().groupOf(ItemMatcher.id("minecraft:hopper_minecart"));
        assertEquals(group, palette.creativeLayout().groupOf(ItemMatcher.id("test:after_hopper")));
        List<String> ids = palette.creativeLayout().group(group).orElseThrow().stacks().stream().map(stack -> stack.type().identifier()).toList();
        assertEquals(ids.indexOf("minecraft:hopper_minecart") + 1, ids.indexOf("test:after_hopper"));
    }

    @Test
    void bdsPlacesBlocksBeforeItemsAndBlocksByMenuCategory() {
        PaletteLayer layer = PaletteLayer.builder("blocks")
            .item(item("test:item", CreativePlacement.loose(CreativeCategory.CONSTRUCTION)))
            .block(CustomBlock.builder("test:block_menu_only").menuCategory(org.geysermc.paletter.block.component.MenuCategory.builder()
                .category(org.geysermc.paletter.block.component.MenuCategory.Category.CONSTRUCTION).build()).build())
            .build();
        Palette palette = Palettes.palette(LATEST, layer);
        List<CreativeNode> construction = palette.creativeLayout().nodes(CreativeCategory.CONSTRUCTION);
        assertEquals("test:block_menu_only", ((CreativeItem) construction.get(construction.size() - 2)).stack().type().identifier());
        assertEquals("test:item", ((CreativeItem) construction.getLast()).stack().type().identifier());

        Palette append = Paletter.builder().creativeOrdering(CreativeOrdering.APPEND).layer(PaletteLayer.builder("blocks")
            .item(item("test:item", CreativePlacement.loose(CreativeCategory.CONSTRUCTION)))
            .block(CustomBlock.builder("test:block_menu_only").menuCategory(org.geysermc.paletter.block.component.MenuCategory.builder()
                .category(org.geysermc.paletter.block.component.MenuCategory.Category.CONSTRUCTION).build()).build())
            .build()).build().palette(LATEST);
        assertFalse(append.creativeLayout().contains(ItemMatcher.id("test:block_menu_only")));
    }

    @Test
    void blockOrderFollowsTheMsvcHashMap() {
        // BDS 1.26.63.1, creative order probe: block ids 10000.. in this order; BDS iterated its data driven block map
        // (start game block properties, and the creative block items) in the order asserted below
        List<String> registered = List.of("exp:block_hidden", "exp:block_planks", "exp:block_g3", "zzz:block_c", "exp:block_nomenu",
            "exp:block_sword", "exp:block_with_item", "exp:block_b", "exp:block_placed", "aaa:block_d", "exp:block_items",
            "exp:block_none", "exp:block_a", "exp:block_g1");
        List<String> vanilla = PALETTER.vanilla(PALETTER.version(LATEST)).vanillaData().dataDrivenBlocks().stream()
            .map(block -> block.getName()).toList();
        List<String> order = CreativeOrdering.BDS.blockOrder(registered, vanilla);
        assertEquals(List.of("exp:block_hidden", "exp:block_sword", "exp:block_planks", "exp:block_g3", "exp:block_nomenu",
            "zzz:block_c", "exp:block_with_item", "exp:block_b", "exp:block_placed", "aaa:block_d", "exp:block_items",
            "exp:block_g1", "exp:block_none", "exp:block_a"), order);
        assertEquals(registered, CreativeOrdering.APPEND.blockOrder(registered, vanilla));
    }
}
