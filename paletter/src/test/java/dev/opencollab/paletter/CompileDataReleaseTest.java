package dev.opencollab.paletter;

import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.RegisteredBlock;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.RegisteredItem;
import dev.opencollab.paletter.item.component.MaxStackSize;
import dev.opencollab.paletter.property.Properties;
import dev.opencollab.paletter.vanilla.BedrockBlockType;
import dev.opencollab.paletter.vanilla.BedrockBlocks;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What only compiling uses is dropped once every palette a Paletter serves is compiled, and what comes after works as
 * before: {@code extend}, a version that is not served, and the properties of the data source.
 */
class CompileDataReleaseTest {
    private static final DataVersion OLDEST = Paletter.bundled().versions().getFirst();

    /** Every bundled version but the oldest, all compiled: the release has run. */
    private static Paletter released() {
        Paletter paletter = Paletter.builder().versions(version -> !version.equals(OLDEST)).build();
        List<DataVersion> versions = paletter.versions();
        for (DataVersion version : versions) {
            assertFalse(paletter.compileDataReleased(), "released before " + version + " was compiled");
            paletter.palette(version.protocolVersion());
        }
        assertTrue(paletter.compileDataReleased());
        return paletter;
    }

    private static PaletteLayer sessionLayer(String id) {
        return PaletteLayer.builder(id)
            .block(CustomBlock.builder("release:block").property(BlockProperty.bool("release:lit")).property(BlockProperty.range("release:level", 0, 7)).build())
            .block(CustomBlock.builder("release:other").property(BlockProperty.bool("release:lit")).build())
            .build();
    }

    private static PaletteLayer creativeLayer(String id) {
        return PaletteLayer.builder(id)
            .block(CustomBlock.builder("release:shown").menuCategory(CreativeCategory.CONSTRUCTION).build())
            .item(CustomItem.builder("release:gem").component(MaxStackSize.of(16)).menuCategory(CreativeCategory.ITEMS).build())
            .build();
    }

    @Test
    void releasedOnceEveryServedPaletteIsCompiled() {
        Paletter paletter = released();
        assertTrue(paletter.blockInterner().compileMapsReleased());
        assertFalse(paletter.canonicalBlocks().hasSchemas());
        for (DataVersion version : paletter.versions()) {
            assertEquals(0, paletter.palette(version.protocolVersion()).blocks().schemaCount(), version.toString());
        }
    }

    @Test
    void extendAfterTheReleaseIsTheSame() {
        Paletter paletter = released();
        // Compiles only the newest palette: nothing is released
        Paletter unreleased = Paletter.builder().build();
        assertReleasedExtendIsTheSame(paletter, unreleased, CompileDataReleaseTest::sessionLayer);
        assertReleasedExtendIsTheSame(paletter, unreleased, CompileDataReleaseTest::creativeLayer);
        assertFalse(unreleased.compileDataReleased());
    }

    private static void assertReleasedExtendIsTheSame(Paletter released, Paletter unreleased, Function<String, PaletteLayer> layer) {
        for (DataVersion version : released.versions()) {
            Palette extended = released.palette(version.protocolVersion()).extend(layer.apply("session"));
            Palette expected = unreleased.palette(version.protocolVersion()).extend(layer.apply("session"));
            PaletteComparison.assertSameContent(expected, extended);
            // The vanilla states are still the ones of the base palette
            assertSame(released.palette(version.protocolVersion()).blocks().air(), extended.blocks().air());
        }
    }

    @Test
    void aVersionCompiledAfterTheReleaseIsTheSame() {
        Paletter paletter = released();
        Palette late = paletter.vanilla(OLDEST);
        PaletteComparison.assertSameContent(Paletter.builder().build().vanilla(OLDEST), late);
        // Its vanilla states are those of the other versions, only its blocks and pages are its own
        assertSame(paletter.palette(paletter.latest().protocolVersion()).blockDefinition(BedrockBlocks.AIR.defaultState()),
            late.blockDefinition(BedrockBlocks.AIR.defaultState()));
        // And extending it works too
        PaletteComparison.assertSameContent(Paletter.builder().build().vanilla(OLDEST).extend(sessionLayer("late")),
            late.extend(sessionLayer("late")));
    }

    @Test
    void theDataSourceReadsThePropertiesAgain() {
        Paletter paletter = Paletter.builder().versions(version -> !version.equals(OLDEST)).build();
        DataVersion latest = paletter.latest();
        // The vanilla palette alone does not release anything
        Palette vanilla = paletter.vanilla(latest);
        Map<BedrockItemType, Properties> items = new HashMap<>();
        for (RegisteredItem item : vanilla.items().definitions()) {
            items.put(item.key(), paletter.properties(item.key(), latest));
        }
        Map<BedrockBlockType, Properties> blocks = new HashMap<>();
        for (RegisteredBlock block : vanilla.blocks().blocks()) {
            blocks.put(block.key(), paletter.properties(block.key(), latest));
        }
        assertTrue(items.values().stream().anyMatch(properties -> !properties.isEmpty()));
        assertTrue(blocks.values().stream().anyMatch(properties -> !properties.isEmpty()));
        assertFalse(paletter.compileDataReleased());
        paletter.warmUp();
        assertTrue(paletter.compileDataReleased());
        items.forEach((type, properties) -> assertEquals(properties, paletter.properties(type, latest), type.identifier()));
        blocks.forEach((type, properties) -> assertEquals(properties, paletter.properties(type, latest), type.identifier()));
    }
}
