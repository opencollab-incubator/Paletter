package org.geysermc.paletter;

import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.item.component.BlockPlacer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BDS gives the plain item of a custom block the runtime id 255 - block_id, so it follows the block's numbering. */
class BlockItemIdTest {
    private static final Paletter PALETTER = Paletter.bundled();
    private static final int PROTOCOL = PALETTER.latest().protocolVersion();

    private static CustomBlock block(String identifier) {
        return CustomBlock.builder(identifier).menuCategory(CreativeCategory.CONSTRUCTION).build();
    }

    private static PaletteLayer layer(String id, String... identifiers) {
        PaletteLayer.Builder layer = PaletteLayer.builder(id);
        for (String identifier : identifiers) {
            layer.block(block(identifier));
        }
        return layer.build();
    }

    private static int blockId(Palette palette, String identifier) {
        return palette.blocks().blockProperties().stream().filter(property -> property.getName().equals(identifier)).findFirst().orElseThrow()
            .getProperties().getCompound("vanilla_block_data").getInt("block_id");
    }

    private static void assertPlainItemsFollowBlockIds(Palette palette) {
        for (CustomBlock block : palette.blocks().customBlocks()) {
            RegisteredItem item = palette.items().getDefinition(block.identifier());
            assertNotNull(item, block.identifier());
            assertEquals(255 - blockId(palette, block.identifier()), item.getRuntimeId(), block.identifier());
            assertTrue(item.getRuntimeId() < 0, "block ids start at 10000");
            assertEquals(item, palette.items().getDefinition(item.getRuntimeId()), "found by id too");
        }
    }

    @Test
    void plainBlockItemsAreNumberedByTheirBlock() {
        Palette palette = Palettes.palette(PROTOCOL, layer("blocks", "ids:a", "ids:c", "ids:b"));
        assertPlainItemsFollowBlockIds(palette);
        assertEquals(255 - 10000, palette.items().getDefinition("ids:c").getRuntimeId());
    }

    @Test
    void extendingWithBlocksNumbersTheEarlierBlockItemsAgain() {
        Palette first = Palettes.palette(PROTOCOL, layer("first", "ids:b", "ids:d"));
        int before = first.items().getDefinition("ids:b").getRuntimeId();
        // Sorts above both: every block id of the first layer moves up by one
        Palette second = first.extend(layer("second", "ids:e", "ids:a"));
        assertPlainItemsFollowBlockIds(first);
        assertPlainItemsFollowBlockIds(second);
        assertNotEquals(before, second.items().getDefinition("ids:b").getRuntimeId());
        assertEquals(first.items().definitions().size() + 2, second.items().definitions().size());
        // The id the item had in the first palette names nothing, or another block's item
        RegisteredItem old = second.items().getDefinition(before);
        assertTrue(old == null || !old.getIdentifier().equals("ids:b"));
        long named = second.items().definitions().stream().filter(item -> item.getIdentifier().equals("ids:b")).count();
        assertEquals(1, named);

        Palette third = second.extend(layer("third", "ids:c"));
        assertPlainItemsFollowBlockIds(third);
        assertEquals(List.of(), third.items().definitions().stream().filter(item -> item.getRuntimeId() < 0)
            .filter(item -> third.items().getDefinition(item.getRuntimeId()) != item).toList());
    }

    @Test
    void aReplacingItemIsSequentialAndTheBlocksItem() {
        CustomBlock block = block("ids:driven");
        CustomItem replacement = CustomItem.builder("ids:driven")
            .component(BlockPlacer.builder().block("ids:driven").replaceBlockItem(true).build()).build();
        Palette palette = Palettes.palette(PROTOCOL, PaletteLayer.builder("driven").block(block).item(replacement).build());
        RegisteredItem item = palette.items().getDefinition("ids:driven");
        assertTrue(item.getRuntimeId() > 0);
        // The wire sends replaceBlockItem 1 for the block's own identifier (addon capture, paletter:plain_block)
        BlockPlacer placer = item.components().stream().filter(BlockPlacer.class::isInstance).map(BlockPlacer.class::cast).findFirst().orElseThrow();
        assertTrue(placer.replaceBlockItem());
        assertNull(palette.items().getDefinition(255 - blockId(palette, "ids:driven")));
    }
}
