package org.geysermc.paletter;

import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.component.BlockPlacer;
import org.geysermc.paletter.creative.CreativeCategory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Paletter owns the order of custom blocks: identifier descending, whatever order they are registered in. */
class CustomOrderTest {
    private static final Paletter PALETTER = Paletter.bundled();

    private static CustomBlock block(String identifier) {
        return CustomBlock.builder(identifier).menuCategory(CreativeCategory.CONSTRUCTION).build();
    }

    private static Palette palette(String... identifiers) {
        PaletteLayer.Builder layer = PaletteLayer.builder("order");
        for (String identifier : identifiers) {
            layer.block(block(identifier));
        }
        return Palettes.palette(PALETTER.latest().protocolVersion(), layer.build());
    }

    @Test
    void propertyListAndBlockItemIdsFollowTheIdentifierNotTheRegistration() {
        List<String> ascending = List.of("order:a", "order:b", "order:c", "order:d");
        List<String> shuffled = List.of("order:c", "order:a", "order:d", "order:b");
        Palette first = palette(ascending.toArray(String[]::new));
        Palette second = palette(shuffled.toArray(String[]::new));

        for (Palette palette : List.of(first, second)) {
            List<String> properties = new ArrayList<>();
            palette.blocks().blockProperties().forEach(property -> {
                if (property.getName().startsWith("order:")) {
                    properties.add(property.getName());
                }
            });
            assertEquals(List.of("order:d", "order:c", "order:b", "order:a"), properties);

            // Plain block items are 255 - block_id: the identifiers above get the highest item ids
            int previous = Integer.MAX_VALUE;
            for (String identifier : List.of("order:d", "order:c", "order:b", "order:a")) {
                int runtimeId = palette.items().getDefinition(identifier).getRuntimeId();
                assertTrue(runtimeId < previous, identifier + " has a lower id than the identifiers above it");
                previous = runtimeId;
            }
        }
        for (String identifier : ascending) {
            assertEquals(first.items().getDefinition(identifier).getRuntimeId(), second.items().getDefinition(identifier).getRuntimeId(), identifier);
        }
        assertTrue(first.blocks().blockProperties().equals(second.blocks().blockProperties()));
    }

    /**
     * An item that replaces a block item is an ordinary data driven item: it shares the sequence of the custom items, in
     * registration order (the addon capture: paletter:plain 908, paletter:plain_block 909, paletter:projectile 910).
     */
    @Test
    void replacingItemsShareTheSequenceOfTheCustomItems() {
        PaletteLayer.Builder layer = PaletteLayer.builder("driven");
        for (String identifier : List.of("order:a", "order:c", "order:b")) {
            layer.block(block(identifier));
        }
        for (String identifier : List.of("order:a", "order:plain", "order:b", "order:c")) {
            CustomItem.Builder item = CustomItem.builder(identifier).menuCategory(CreativeCategory.ITEMS);
            if (!identifier.equals("order:plain")) {
                item.component(BlockPlacer.builder().block(identifier).replaceBlockItem(true).build());
            }
            layer.item(item.build());
        }
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), layer.build());
        int a = palette.items().getDefinition("order:a").getRuntimeId();
        assertEquals(a + 1, palette.items().getDefinition("order:plain").getRuntimeId());
        assertEquals(a + 2, palette.items().getDefinition("order:b").getRuntimeId());
        assertEquals(a + 3, palette.items().getDefinition("order:c").getRuntimeId());
    }
}
