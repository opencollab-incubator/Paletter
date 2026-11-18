package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.item.component.MaxStackSize;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A layer without creative edits decides the default blocks of the items it changed only; a layer with an edit (here one that
 * changes nothing) decides those of every item. Both must give the same palette.
 */
class ExtendIncrementalTest {
    private static final Paletter PALETTER = Paletter.bundled();

    private static PaletteLayer.Builder content(String id) {
        return PaletteLayer.builder(id)
            .block(CustomBlock.builder("inc:lamp").menuCategory(CreativeCategory.CONSTRUCTION).build())
            .block(CustomBlock.builder("inc:hidden").item(CustomBlock.BlockItem.NONE).build())
            .item(CustomItem.builder("inc:gem").component(MaxStackSize.of(16)).build())
            .overrideItem("minecraft:apple", item -> item.replaceComponents(MaxStackSize.of(3)))
            .overrideItem("minecraft:stone", item -> item.block(NbtMap.builder().putString("name", "minecraft:dirt")
                .putCompound("states", NbtMap.EMPTY).build()))
            .overrideItem("minecraft:oak_door", item -> item.block(NbtMap.builder().putString("name", "minecraft:not_a_block")
                .putCompound("states", NbtMap.EMPTY).build()));
    }

    @Test
    void anIncrementalLayerGivesThePaletteOfAFullOne() {
        for (DataVersion version : List.of(PALETTER.latest(), PALETTER.versions().getFirst())) {
            Palette parent = PALETTER.vanilla(version).extend(content("inc:earlier").build());
            Palette incremental = parent.extend(PaletteLayer.builder("inc:second")
                .block(CustomBlock.builder("inc:second_lamp").menuCategory(CreativeCategory.NATURE).build())
                .item(CustomItem.builder("inc:second_gem").build()).build());
            Palette full = parent.extend(PaletteLayer.builder("inc:second")
                .block(CustomBlock.builder("inc:second_lamp").menuCategory(CreativeCategory.NATURE).build())
                .item(CustomItem.builder("inc:second_gem").build()).creative(editor -> { }).build());
            assertSamePalette(full, incremental, version.toString());
        }
    }

    @Test
    void aLayerThatOverridesGivesThePaletteOfAFullOne() {
        for (DataVersion version : List.of(PALETTER.latest(), PALETTER.versions().getFirst())) {
            Palette parent = PALETTER.vanilla(version);
            Palette incremental = parent.extend(content("inc:layer").build());
            Palette full = parent.extend(content("inc:layer").creative(editor -> { }).build());
            assertSamePalette(full, incremental, version.toString());
            // Overrides of an earlier layer are decided again by a later one, as they always were
            CustomItem gem = CustomItem.builder("inc:third_gem").build();
            assertSamePalette(full.extend(PaletteLayer.builder("inc:third").item(gem).creative(editor -> { }).build()),
                incremental.extend(PaletteLayer.builder("inc:third").item(gem).build()), version + " third");
        }
    }

    private static void assertSamePalette(Palette expected, Palette actual, String message) {
        List<RegisteredItem> items = new ArrayList<>(expected.items().definitions());
        assertEquals(items.size(), actual.items().definitions().size(), message);
        for (RegisteredItem item : items) {
            RegisteredItem other = actual.items().item(item.getIdentifier());
            assertEquals(expected.items().isMultiStateBlockItem(item.getIdentifier()), actual.items().isMultiStateBlockItem(item.getIdentifier()),
                message + " " + item.getIdentifier());
            assertEquals(describe(expected, item), describe(actual, other), message + " " + item.getIdentifier());
        }
        assertEquals(expected.report().entries().stream().map(Object::toString).sorted().toList(),
            actual.report().entries().stream().map(Object::toString).sorted().toList(), message + " report");
    }

    private static String describe(Palette palette, RegisteredItem item) {
        var block = palette.items().defaultBlock(item);
        return block == null ? "none" : block.getRuntimeId() + " " + block.getState();
    }
}
