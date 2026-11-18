package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.vanilla.BedrockBlockType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.geysermc.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaletteReportTest {
    private static final Paletter PALETTER = Paletter.builder().fallbacks(Fallbacks.NONE).build();
    private static final DataVersion OLDEST = PALETTER.versions().getFirst();

    @Test
    void theReportKnowsItsVersionAndWhatNothingCovers() {
        PaletteReport report = PALETTER.vanilla(OLDEST).report();
        assertEquals(OLDEST, report.version());
        assertFalse(report.unknownBlocks().isEmpty(), "newer blocks have no equivalent in the oldest version");
        assertFalse(report.unknownItems().isEmpty());
        assertTrue(PALETTER.vanilla(PALETTER.latest()).report().unknownBlocks().isEmpty());
    }

    @Test
    void unknownItemsAreExactVariantsAndIncludeGatedAndRemovedOnes() {
        PaletteReport report = PALETTER.vanilla(OLDEST).report();
        assertTrue(report.unknownItems().stream().anyMatch(variant -> variant.type().equals(BedrockItems.BLACK_CUSHION)),
            "behind a disabled experiment");
        // Items are exact variants: the base variant of the cushion is what is missing
        assertTrue(report.unknownItems().stream().anyMatch(variant -> variant.type().equals(BedrockItems.BLACK_CUSHION) && variant.aux() == 0));

        Palette removed = Paletter.builder().fallbacks(Fallbacks.NONE).removeItems(BedrockItems.APPLE, BedrockItems.STICK).build()
            .palette(PALETTER.latest().protocolVersion());
        assertTrue(removed.report().unknownItems().stream().anyMatch(item -> item.type().equals(BedrockItems.APPLE)));
        assertTrue(removed.report().unknownItems().stream().anyMatch(item -> item.type().equals(BedrockItems.STICK)));
    }

    @Test
    void theReportCannotBeChanged() {
        PaletteReport report = PALETTER.vanilla(OLDEST).report();
        assertThrows(UnsupportedOperationException.class, () -> report.entries().add(null));
        assertThrows(UnsupportedOperationException.class, () -> report.unknownBlocks().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.unknownItems().clear());
    }

    @Test
    void anExtendedPaletteKeepsWhatTheParentReported() {
        Palette base = PALETTER.vanilla(OLDEST);
        CustomBlock lamp = CustomBlock.builder("report:lamp").build();
        CustomItem ruby = CustomItem.builder("report:ruby").menuCategory(CreativeCategory.ITEMS).build();
        // Custom content grows the canonical set; what the vanilla data lacks stays the same
        Palette extended = base.extend(PaletteLayer.builder("test:report").block(lamp).item(ruby).build());
        assertEquals(OLDEST, extended.report().version());
        assertEquals(base.report().unknownBlocks(), extended.report().unknownBlocks());
        assertEquals(base.report().unknownItems(), extended.report().unknownItems());
        assertFalse(extended.report().unknownBlocks().contains(lamp.type()));
        assertFalse(extended.report().unknownItems().contains(ruby.type().variant()));
    }

    @Test
    void explanationsNameTheDifferingKeys() {
        Palette palette = PALETTER.vanilla(OLDEST);
        NbtMap known = PALETTER.vanillaData(OLDEST).blockStates().stream()
            .filter(state -> !state.getCompound("states").isEmpty()).findFirst().orElseThrow();
        String name = BlockStates.name(known);
        String key = known.getCompound("states").keySet().iterator().next();

        String value = palette.explainUnknown(BlockStates.of(name, Map.of(key, "paletter_test_value")), Lookup.EXACT);
        assertTrue(value.contains("key " + key + " is paletter_test_value"), value);
        String extra = palette.explainUnknown(BlockStates.of(name, Map.of(key, known.getCompound("states").get(key),
            "paletter_test_key", "x")), Lookup.EXACT);
        assertTrue(extra.contains("key paletter_test_key is not a state of " + name), extra);
        assertTrue(palette.explainUnknown(BlockStates.of(name, Map.of()), Lookup.EXACT).contains("is not set (states have e.g. "));

        String added = PALETTER.vanillaData(PALETTER.latest()).items().stream().map(item -> item.identifier())
            .filter(id -> PALETTER.vanillaData(OLDEST).items().stream().noneMatch(old -> old.identifier().equals(id))).findFirst().orElseThrow();
        assertTrue(palette.explainUnknown(added, 0, Lookup.EXACT).contains("no item " + added + " in " + OLDEST), added);
    }

    @Test
    void stateValuesAreComparedByValueNotByType() {
        // NBT keeps booleans as bytes: asking with a boolean (or an int) for the same value is no difference
        NbtMap known = PALETTER.vanillaData(OLDEST).blockStates().stream()
            .filter(state -> state.getCompound("states").values().stream().anyMatch(value -> value instanceof Byte)).findFirst().orElseThrow();
        Map<String, Object> asked = new HashMap<>(known.getCompound("states"));
        String flag = asked.entrySet().stream().filter(entry -> entry.getValue() instanceof Byte).map(Map.Entry::getKey).findFirst().orElseThrow();
        asked.put(flag, ((Byte) asked.get(flag)) != 0);
        asked.put("paletter_test_key", "x");
        String why = PALETTER.vanilla(OLDEST).explainUnknown(BlockStates.of(BlockStates.name(known), asked), Lookup.EXACT);
        assertTrue(why.contains("paletter_test_key"), why);
        assertFalse(why.contains("key " + flag + " is"), why);
    }
}
