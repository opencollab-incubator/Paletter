package dev.opencollab.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.item.BedrockItemStack;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.RegisteredItem;
import dev.opencollab.paletter.item.ResolvedItem;
import dev.opencollab.paletter.item.component.BlockPlacer;
import dev.opencollab.paletter.item.component.MaxStackSize;
import dev.opencollab.paletter.vanilla.BedrockBlocks;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A layer without creative edits decides the default blocks of the items it changed only; a layer with an edit (here one that
 * changes nothing) decides those of every item. Both must give the same palette: the item palette, the creative content, what
 * every canonical id resolves to, and the report in its order.
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
                .putCompound("states", NbtMap.EMPTY).build()))
            // Several warnings: their order is the full path's, not the alphabetical one
            .overrideItem("minecraft:acacia_door", item -> item.block(NbtMap.builder().putString("name", "minecraft:not_a_block")
                .putCompound("states", NbtMap.EMPTY).build()))
            .overrideItem("minecraft:stick", item -> item.block(NbtMap.builder().putString("name", "minecraft:not_a_block")
                .putCompound("states", NbtMap.EMPTY).build()))
            .overrideItem("minecraft:zombie_spawn_egg", item -> item.block(NbtMap.builder().putString("name", "minecraft:not_a_block")
                .putCompound("states", NbtMap.EMPTY).build()))
            .overrideItem("minecraft:bread", item -> item.block(NbtMap.builder().putString("name", "minecraft:not_a_block")
                .putCompound("states", NbtMap.EMPTY).build()));
    }

    private static List<DataVersion> versions() {
        return List.of(PALETTER.latest(), PALETTER.versions().getFirst());
    }

    /**
     * Extends the root with the layers one after the other: as they are, or (full) each with a creative edit that changes
     * nothing, which makes the layer decide everything again.
     */
    private static Palette chain(Palette root, boolean full, List<Supplier<PaletteLayer.Builder>> layers) {
        Palette palette = root;
        for (Supplier<PaletteLayer.Builder> layer : layers) {
            PaletteLayer.Builder builder = layer.get();
            if (full) {
                builder.creative(editor -> { });
            }
            palette = palette.extend(builder.build());
        }
        return palette;
    }

    private static void assertChain(Paletter paletter, List<Supplier<PaletteLayer.Builder>> layers) {
        for (DataVersion version : List.of(paletter.latest(), paletter.versions().getFirst())) {
            Palette root = paletter.vanilla(version);
            assertSamePalette(chain(root, true, layers), chain(root, false, layers), version.toString());
        }
    }

    @Test
    void anIncrementalLayerGivesThePaletteOfAFullOne() {
        for (DataVersion version : versions()) {
            Palette parent = PALETTER.vanilla(version).extend(content("inc:earlier").build());
            List<Supplier<PaletteLayer.Builder>> second = List.of(() -> PaletteLayer.builder("inc:second")
                .block(CustomBlock.builder("inc:second_lamp").menuCategory(CreativeCategory.NATURE).build())
                .item(CustomItem.builder("inc:second_gem").build()));
            assertSamePalette(chain(parent, true, second), chain(parent, false, second), version.toString());
        }
    }

    @Test
    void aLayerThatOverridesGivesThePaletteOfAFullOne() {
        for (DataVersion version : versions()) {
            Palette parent = PALETTER.vanilla(version);
            Palette incremental = chain(parent, false, List.of(() -> content("inc:layer")));
            Palette full = chain(parent, true, List.of(() -> content("inc:layer")));
            assertSamePalette(full, incremental, version.toString());
            // Overrides of an earlier layer are decided again by a later one, as they always were
            CustomItem gem = CustomItem.builder("inc:third_gem").build();
            assertSamePalette(full.extend(PaletteLayer.builder("inc:third").item(gem).creative(editor -> { }).build()),
                incremental.extend(PaletteLayer.builder("inc:third").item(gem).build()), version + " third");
        }
    }

    @Test
    void aLaterBlockWithTheIdentifierOfAnEarlierItemWithoutABlock() {
        assertChain(PALETTER, List.of(
            () -> PaletteLayer.builder("inc:a").item(CustomItem.builder("inc:twin").build()),
            () -> PaletteLayer.builder("inc:b").block(CustomBlock.builder("inc:twin").item(CustomBlock.BlockItem.NONE).build())));
    }

    @Test
    void aBlockItemReplacement() {
        assertChain(PALETTER, List.of(
            () -> PaletteLayer.builder("inc:a")
                .block(CustomBlock.builder("inc:driven").menuCategory(CreativeCategory.CONSTRUCTION).build())
                .item(CustomItem.builder("inc:driven")
                    .component(BlockPlacer.builder().block("inc:driven").replaceBlockItem(true).build()).build()),
            () -> PaletteLayer.builder("inc:b").block(CustomBlock.builder("inc:other").menuCategory(CreativeCategory.NATURE).build())));
    }

    @Test
    void aNoBlockOverride() {
        assertChain(PALETTER, List.of(
            () -> PaletteLayer.builder("inc:a").overrideItem("minecraft:stone", item -> item.noBlock())
                .overrideItem("minecraft:oak_planks", item -> item.noBlock()),
            () -> PaletteLayer.builder("inc:b").item(CustomItem.builder("inc:gem").build())));
    }

    @Test
    void aParentItemWithSeveralCreativeBlockStates() {
        for (DataVersion version : versions()) {
            // A parent item with creative stacks of two block states
            CustomItem multiItem = CustomItem.builder("inc:multi").build();
            Palette root = PALETTER.vanilla(version).extend(PaletteLayer.builder("inc:parent").item(multiItem)
                .creative(editor -> {
                    editor.insert(BedrockItemStack.of(multiItem.type()).withBlockState(BedrockBlocks.STONE.defaultState())).loose(CreativeCategory.ITEMS);
                    editor.insert(BedrockItemStack.of(multiItem.type()).withBlockState(BedrockBlocks.DIRT.defaultState())).loose(CreativeCategory.ITEMS);
                }).build());
            RegisteredItem multi = root.items().getDefinition("inc:multi");
            assertTrue(root.items().isMultiStateBlockItem("inc:multi"), version.toString());
            List<Supplier<PaletteLayer.Builder>> unrelated = List.of(() -> PaletteLayer.builder("inc:a").item(CustomItem.builder("inc:gem").build()));
            assertSamePalette(chain(root, true, unrelated), chain(root, false, unrelated), version + " unrelated");
            // Overridden, the item is decided again from the creative states of the parent's layout
            List<Supplier<PaletteLayer.Builder>> overridden = List.of(() -> PaletteLayer.builder("inc:b")
                .overrideItem(multi.getIdentifier(), item -> item.replaceComponents(MaxStackSize.of(7))));
            assertSamePalette(chain(root, true, overridden), chain(root, false, overridden), version + " overridden " + multi.getIdentifier());
        }
    }

    @Test
    void threeIncrementalLayersOnBothSides() {
        assertChain(PALETTER, List.of(() -> content("inc:one"),
            () -> PaletteLayer.builder("inc:two").block(CustomBlock.builder("inc:two_lamp").menuCategory(CreativeCategory.NATURE).build())
                .overrideItem("minecraft:stone", item -> item.noBlock()),
            () -> PaletteLayer.builder("inc:three").item(CustomItem.builder("inc:three_gem").build())
                .overrideItem("minecraft:apple", item -> item.replaceComponents(MaxStackSize.of(5)))));
    }

    @Test
    void withExperimentsEnabled() {
        assertChain(Paletter.builder().allExperiments().build(), List.of(() -> content("inc:one"),
            () -> PaletteLayer.builder("inc:two").item(CustomItem.builder("inc:two_gem").build())));
    }

    @Test
    void withGlobalLayers() {
        Paletter paletter = Paletter.builder().layer(content("inc:global").build()).build();
        assertChain(paletter, List.of(() -> PaletteLayer.builder("inc:one")
                .block(CustomBlock.builder("inc:one_lamp").menuCategory(CreativeCategory.NATURE).build())
                .overrideItem("minecraft:apple", item -> item.replaceComponents(MaxStackSize.of(2))),
            () -> PaletteLayer.builder("inc:two").item(CustomItem.builder("inc:two_gem").build())));
    }

    private static void assertSamePalette(Palette expected, Palette actual, String message) {
        List<RegisteredItem> items = new ArrayList<>(expected.items().definitions());
        assertEquals(items.size(), actual.items().definitions().size(), message);
        for (RegisteredItem item : items) {
            RegisteredItem other = actual.items().getDefinition(item.getIdentifier());
            assertEquals(expected.items().isMultiStateBlockItem(item.getIdentifier()), actual.items().isMultiStateBlockItem(item.getIdentifier()),
                message + " " + item.getIdentifier());
            assertEquals(describe(expected, item), describe(actual, other), message + " " + item.getIdentifier());
        }
        assertEquals(itemPalette(expected), itemPalette(actual), message + " item palette");
        assertEquals(creative(expected), creative(actual), message + " creative content");
        int canonical = expected.items().canonical().size();
        assertEquals(canonical, actual.items().canonical().size(), message);
        for (int i = 0; i < canonical; i++) {
            assertEquals(describe(expected.items().item(i)), describe(actual.items().item(i)), message + " canonical " + i);
        }
        assertEquals(expected.items().missingCanonical().toString(), actual.items().missingCanonical().toString(), message + " missing");
        // The order of the report is its content too
        assertEquals(expected.report().entries().stream().map(Object::toString).toList(),
            actual.report().entries().stream().map(Object::toString).toList(), message + " report");
    }

    private static String describe(Palette palette, RegisteredItem item) {
        var block = palette.items().defaultBlock(item);
        return block == null ? "none" : block.getRuntimeId() + " " + block.getState();
    }

    private static String describe(ResolvedItem item) {
        return item == null ? "unresolved" : item.definition().getRuntimeId() + " " + item.damage() + " " + item.fallback() + " "
            + (item.block() == null ? "no block" : item.block().getRuntimeId());
    }

    /** The item palette as sent: every definition with the data it carries. */
    private static List<String> itemPalette(Palette palette) {
        ItemComponentPacket packet = palette.itemComponentPacket();
        return packet.getItems().stream().map(item -> item.getIdentifier() + " " + item.getRuntimeId() + " " + item.getVersion() + " "
            + item.isComponentBased() + " " + item.getComponentData()).toList();
    }

    private static List<String> creative(Palette palette) {
        CreativeContentPacket packet = palette.creativeContentPacket();
        List<String> lines = new ArrayList<>();
        for (var entry : packet.getContents()) {
            ItemData data = entry.getItem();
            lines.add(entry.getGroupId() + " " + entry.getNetId() + " " + data.getDefinition().getRuntimeId() + " " + data.getDamage() + " " + data.getCount() + " " + data.getTag() + " "
                + (data.getBlockDefinition() == null ? "no block" : data.getBlockDefinition().getRuntimeId()));
        }
        packet.getGroups().forEach(group -> lines.add(group.getCategory() + " " + group.getName() + " "
            + (group.getIcon() == null || group.getIcon().getDefinition() == null ? "no icon" : group.getIcon().getDefinition().getRuntimeId())));
        return lines;
    }
}
