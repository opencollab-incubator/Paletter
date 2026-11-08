package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.block.BlockFallback;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.block.BlockResolution;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.FallbackDefaults;
import org.geysermc.paletter.data.VersionFallbacks;
import org.geysermc.paletter.item.ItemFallback;
import org.geysermc.paletter.item.ItemVariant;
import org.geysermc.paletter.item.ResolvedItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Content of the newest version that an older one lacks: Paletter bundles no fallbacks, so it is the
 * {@code minecraft:unknown} default, which the layer API overrides.
 */
class FallbackTest {
    private static final Paletter PALETTER = Paletter.bundled();
    private static final int OLDEST = 2168;

    /** A canonical block of the newest version that the oldest lacks. */
    private static int blockWithoutFallback(Palette palette) {
        return missingBlock(palette, null);
    }

    /** A canonical block of the newest version that the oldest lacks, of another block than {@code other}. */
    private static int missingBlock(Palette palette, String other) {
        for (int i = 0; i < palette.canonicalBlockCount(); i++) {
            String name = BlockStates.name(palette.canonicalBlocks().state(i));
            if (!palette.blocks().contains(name) && !name.equals(other)) {
                return i;
            }
        }
        throw new AssertionError("fixture: the oldest version lacks blocks of the newest");
    }

    /**
     * A canonical item of the newest version that the oldest lacks. {@link #broken} points it at nothing, which
     * keeps the test independent of other fallbacks.
     */
    private static int missingItem(Palette palette) {
        for (int i = 0; i < palette.canonicalItemCount(); i++) {
            ItemVariant variant = palette.canonicalItems().variant(i);
            if (palette.items().getDefinition(variant.identifier()) == null) {
                return i;
            }
        }
        throw new AssertionError("the oldest version has every item of the newest");
    }

    private static PaletteLayer.Builder broken(Palette vanilla, int item, String id) {
        return PaletteLayer.builder(id).itemFallback(vanilla.canonicalItems().variant(item).identifier(), "paletter:nothing");
    }

    @Test
    void missingContentFallsBackToUnknownByDefault() {
        Palette palette = PALETTER.palette(OLDEST);
        int block = blockWithoutFallback(palette);
        assertEquals("minecraft:unknown", BlockStates.name(palette.block(block).getState()));
        assertTrue(palette.blocks().missingCanonical().contains(palette.canonicalBlocks().state(block)));

        int item = missingItem(palette);
        ResolvedItem resolved = PALETTER.palette(OLDEST, broken(palette, item, "broken").build()).item(item);
        assertNotNull(resolved);
        assertEquals("minecraft:unknown", resolved.definition().getIdentifier());
        assertTrue(resolved.fallback());
    }

    @Test
    void nothingIsBundled() {
        for (DataVersion version : PALETTER.versions()) {
            VersionFallbacks fallbacks = PALETTER.vanillaData(version).fallbacks();
            assertTrue(fallbacks.blocks().isEmpty() && fallbacks.items().isEmpty() && fallbacks.unmapped().isEmpty(), version.toString());
        }
        Palette palette = PALETTER.palette(OLDEST);
        assertTrue(palette.blocks().fallbacks().isEmpty());
    }

    @Test
    void layerFallbacksAreHowConsumersReplaceTheDefault() {
        Palette palette = PALETTER.palette(OLDEST);
        int block = blockWithoutFallback(palette);
        String name = BlockStates.name(palette.canonicalBlocks().state(block));
        assertEquals("minecraft:unknown", BlockStates.name(palette.block(block).getState()));
        Palette layered = PALETTER.palette(OLDEST, PaletteLayer.builder("fallback").blockFallback(name, BlockFallback.of("minecraft:dirt")).build());
        assertEquals("minecraft:dirt", BlockStates.name(layered.block(block).getState()));
        assertEquals(BlockResolution.Kind.FALLBACK, layered.explain(palette.canonicalBlocks().state(block)).kind());
    }

    @Test
    void layerFallbacksApplyPerVersion() {
        Palette vanilla = PALETTER.palette(OLDEST);
        int block = blockWithoutFallback(vanilla);
        int missing = missingBlock(vanilla, BlockStates.name(vanilla.canonicalBlocks().state(block)));
        String name = BlockStates.name(vanilla.canonicalBlocks().state(block));
        String missingName = BlockStates.name(vanilla.canonicalBlocks().state(missing));
        String oldest = vanilla.version().name();

        PaletteLayer layer = PaletteLayer.builder("fallbacks")
            .blockFallback(name, BlockFallback.of("minecraft:dirt"))                  // every version
            .blockFallback(oldest, name, BlockFallback.of("minecraft:stone"))         // this version: wins
            .blockFallback("1.0", missingName, BlockFallback.of("minecraft:stone"))   // another version: ignored
            .build();
        Palette palette = PALETTER.palette(OLDEST, layer);
        assertEquals("minecraft:stone", BlockStates.name(palette.block(block).getState()));
        assertEquals("minecraft:unknown", BlockStates.name(palette.block(missing).getState()));

        int item = missingItem(vanilla);
        String itemName = vanilla.canonicalItems().variant(item).identifier();
        Palette withItem = PALETTER.palette(OLDEST, PaletteLayer.builder("items")
            .itemFallback(oldest, itemName, ItemFallback.of("minecraft:stick")).build());
        assertEquals("minecraft:stick", withItem.item(item).definition().getIdentifier());
    }

    @Test
    void defaultsCanBeChangedOrSwitchedOff() {
        Palette vanilla = PALETTER.palette(OLDEST);
        int block = blockWithoutFallback(vanilla);
        int item = missingItem(vanilla);

        Palette none = PALETTER.palette(OLDEST, broken(vanilla, item, "none").fallbackDefaults(FallbackDefaults.NONE).build());
        assertSame(none.blocks().air(), none.block(block));
        assertNull(none.item(item));

        Palette custom = PALETTER.palette(OLDEST, broken(vanilla, item, "custom")
            .fallbackDefaults(new FallbackDefaults(BlockFallback.of("minecraft:bedrock"), ItemFallback.of("minecraft:barrier")))
            .build());
        assertEquals("minecraft:bedrock", BlockStates.name(custom.block(block).getState()));
        assertEquals("minecraft:barrier", custom.item(item).definition().getIdentifier());

        // Layers above inherit the default of the layers below
        Palette above = custom.extend(PaletteLayer.builder("session").build());
        assertEquals("minecraft:bedrock", BlockStates.name(above.block(block).getState()));
    }

    @Test
    void sharedFallbacksAreNotMatched() {
        Palette vanilla = PALETTER.palette(OLDEST);
        // Two canonical items without a usable fallback: both resolve to the shared minecraft:unknown item
        java.util.List<Integer> missing = new java.util.ArrayList<>();
        for (int i = 0; i < vanilla.canonicalItemCount() && missing.size() < 2; i++) {
            if (vanilla.items().getDefinition(vanilla.canonicalItems().variant(i).identifier()) == null) {
                missing.add(i);
            }
        }
        assertEquals(2, missing.size(), "fixture: the oldest version lacks two items of the newest");
        PaletteLayer.Builder layer = PaletteLayer.builder("shared");
        for (int item : missing) {
            layer.itemFallback(vanilla.canonicalItems().variant(item).identifier(), "paletter:nothing");
        }
        Palette palette = PALETTER.palette(OLDEST, layer.build());
        ResolvedItem first = palette.item(missing.get(0));
        ResolvedItem second = palette.item(missing.get(1));
        assertSame(first.definition(), second.definition());
        assertEquals("minecraft:unknown", first.definition().getIdentifier());

        // The client sending minecraft:unknown is no claim on either of them. If minecraft:unknown is a canonical item
        // of its own, that direct variant matches
        int unknown = PALETTER.canonicalItem(ItemVariant.of("minecraft:unknown"));
        int matched = palette.match(first.toItemData().build());
        assertEquals(unknown, matched);
        assertTrue(matched != missing.get(0) && matched != missing.get(1));

        // A fallback reached by one canonical id only still matches it, and direct variants are unaffected
        Palette single = PALETTER.palette(OLDEST, broken(vanilla, missing.get(0), "single").build());
        int apple = PALETTER.canonicalItem(ItemVariant.of("minecraft:apple"));
        assertEquals(apple, single.match(single.item(apple).toItemData().build()));
        assertEquals(unknown == -1 ? missing.get(0) : unknown, single.match(single.item(missing.get(0)).toItemData().build()));
    }

    @Test
    void aBlockItemResolvesTheSameSpelledWithItsDefaultBlockState() {
        Palette latest = PALETTER.palette(PALETTER.latest().protocolVersion());
        Palette vanilla = PALETTER.palette(OLDEST);
        // A block item the oldest version lacks, as the newest creative inventory spells it (with a block state)
        var stack = latest.creativeLayout().stacks()
            .filter(s -> s.blockState() != null && vanilla.items().getDefinition(s.identifier()) == null)
            .findFirst().orElseThrow(() -> new AssertionError("fixture: the oldest version lacks a block item of the newest"));
        int id = vanilla.canonicalItems().index(stack.variant());
        assertTrue(id != -1);
        Palette palette = PALETTER.palette(OLDEST, broken(vanilla, id, "broken").build());
        ResolvedItem plain = palette.item(id);
        assertNotNull(plain);
        assertEquals(plain, palette.items().resolve(stack.variant()));
        assertEquals(plain, palette.items().resolve(ItemVariant.of(stack.identifier())));
    }

    @Test
    void theNewestVersionNeedsNoFallback() {
        Palette latest = PALETTER.palette(PALETTER.latest().protocolVersion());
        assertTrue(latest.blocks().missingCanonical().isEmpty());
        assertTrue(latest.items().missingCanonical().isEmpty());
        NbtMap unknown = BlockStates.of("minecraft:unknown");
        assertNotNull(latest.blocks().exact(unknown));
    }
}
