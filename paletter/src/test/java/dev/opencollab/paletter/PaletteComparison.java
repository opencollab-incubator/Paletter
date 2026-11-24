package dev.opencollab.paletter;

import dev.opencollab.paletter.block.BlockRegistry;
import dev.opencollab.paletter.block.RegisteredBlock;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.item.ItemRegistry;
import dev.opencollab.paletter.item.RegisteredItem;
import dev.opencollab.paletter.item.ResolvedItem;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compares palettes compiled by different Paletters: by value ({@link #assertSameContent}), and by which objects the
 * palettes of one Paletter share ({@link #sharing}).
 */
final class PaletteComparison {
    private PaletteComparison() {
    }

    /** Everything a palette sends and reports: runtime ids, states, definitions, canonical tables, packets and report. */
    static void assertSameContent(Palette expected, Palette actual) {
        String at = expected.version().toString();
        assertEquals(expected.version(), actual.version());
        BlockRegistry blocks = expected.blocks();
        BlockRegistry otherBlocks = actual.blocks();
        assertEquals(blocks.size(), otherBlocks.size(), at);
        for (int i = 0; i < blocks.size(); i++) {
            RegisteredBlockState block = blocks.byIndex(i);
            RegisteredBlockState other = otherBlocks.byIndex(i);
            assertEquals(block.getRuntimeId(), other.getRuntimeId(), at + " block " + i);
            assertEquals(block.getState(), other.getState(), at + " block " + i);
            assertEquals(block.isCustom(), other.isCustom(), at + " block " + i);
        }
        assertEquals(blocks.canonical().size(), otherBlocks.canonical().size(), at);
        for (int canonical = 0; canonical < blocks.canonical().size(); canonical++) {
            assertEquals(blocks.runtimeId(canonical), otherBlocks.runtimeId(canonical), at + " canonical block " + canonical);
            assertEquals(blocks.isMissing(canonical), otherBlocks.isMissing(canonical), at + " canonical block " + canonical);
            assertEquals(blocks.isFallback(canonical), otherBlocks.isFallback(canonical), at + " canonical block " + canonical);
        }
        assertEquals(blocks.blockProperties(), otherBlocks.blockProperties(), at);
        assertEquals(blocks.customBlocks(), otherBlocks.customBlocks(), at);
        List<String> identifiers = new ArrayList<>();
        blocks.blocks().forEach(block -> identifiers.add(block.identifier()));
        List<String> otherIdentifiers = new ArrayList<>();
        otherBlocks.blocks().forEach(block -> otherIdentifiers.add(block.identifier()));
        assertEquals(identifiers, otherIdentifiers, at);

        ItemRegistry items = expected.items();
        ItemRegistry otherItems = actual.items();
        List<RegisteredItem> definitions = items.definitions();
        List<RegisteredItem> otherDefinitions = otherItems.definitions();
        assertEquals(definitions.size(), otherDefinitions.size(), at);
        for (int i = 0; i < definitions.size(); i++) {
            RegisteredItem item = definitions.get(i);
            RegisteredItem other = otherDefinitions.get(i);
            assertEquals(item.getIdentifier(), other.getIdentifier(), at + " item " + i);
            assertEquals(item.getRuntimeId(), other.getRuntimeId(), at + " item " + i);
            assertEquals(item.isComponentBased(), other.isComponentBased(), at + " item " + i);
            assertEquals(item.getComponentData(), other.getComponentData(), at + " item " + i);
        }
        assertEquals(items.canonical().size(), otherItems.canonical().size(), at);
        for (int canonical = 0; canonical < items.canonical().size(); canonical++) {
            ResolvedItem item = items.item(canonical);
            ResolvedItem other = otherItems.item(canonical);
            assertEquals(item == null, other == null, at + " canonical item " + canonical);
            if (item != null) {
                assertEquals(item.definition().getRuntimeId(), other.definition().getRuntimeId(), at + " canonical item " + canonical);
                assertEquals(item.aux(), other.aux(), at + " canonical item " + canonical);
                assertEquals(item.fallback(), other.fallback(), at + " canonical item " + canonical);
                assertEquals(item.block() == null ? null : item.block().getRuntimeId(),
                    other.block() == null ? null : other.block().getRuntimeId(), at + " canonical item " + canonical);
            }
        }
        assertEquals(items.tags(), otherItems.tags(), at);
        // By their text: the definitions in them are compared by identity, and those of custom content are each
        // Paletter's own (their values are compared above)
        assertEquals(expected.creativeContentPacket().toString(), actual.creativeContentPacket().toString(), at);
        assertEquals(expected.itemComponentPacket().toString(), actual.itemComponentPacket().toString(), at);
        assertEquals(expected.voxelShapesPacket().toString(), actual.voxelShapesPacket().toString(), at);
        assertEquals(expected.experiments(), actual.experiments(), at);

        PaletteReport report = expected.report();
        PaletteReport otherReport = actual.report();
        assertEquals(report.version(), otherReport.version(), at);
        assertEquals(report.entries(), otherReport.entries(), at);
        assertEquals(List.copyOf(report.unknownBlocks()), List.copyOf(otherReport.unknownBlocks()), at);
        assertEquals(List.copyOf(report.unknownItems()), List.copyOf(otherReport.unknownItems()), at);
    }

    /**
     * Which objects the palettes share, as a list with one entry per block state, state map, block, entry of the canonical
     * block table, item definition and component data of every palette, in palette order: the place (version and position) the object was first
     * seen at. Two Paletters whose lists are equal share the same objects between their palettes.
     */
    static List<String> sharing(List<Palette> palettes) {
        Map<Object, String> first = new IdentityHashMap<>();
        List<String> places = new ArrayList<>();
        for (Palette palette : palettes) {
            String version = palette.version().name();
            BlockRegistry blocks = palette.blocks();
            for (int i = 0; i < blocks.size(); i++) {
                RegisteredBlockState block = blocks.byIndex(i);
                places.add(first.computeIfAbsent(block, b -> version + " state " + block.getRuntimeId()));
                places.add(first.computeIfAbsent(block.getState(), s -> version + " nbt " + block.getRuntimeId()));
            }
            for (RegisteredBlock block : blocks.blocks()) {
                places.add(first.computeIfAbsent(block, b -> version + " block " + block.identifier()));
            }
            for (int canonical = 0; canonical < blocks.canonical().size(); canonical++) {
                int id = canonical;
                places.add(first.computeIfAbsent(blocks.block(canonical), b -> version + " canonical " + id));
            }
            for (RegisteredItem item : palette.items().definitions()) {
                places.add(first.computeIfAbsent(item, i -> version + " item " + item.getIdentifier()));
                if (item.getComponentData() != null) {
                    places.add(first.computeIfAbsent(item.getComponentData(), c -> version + " components " + item.getIdentifier()));
                }
            }
        }
        return places;
    }
}
