package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.block.BlockFallback;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.block.BlockResolution;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.FallbackDefaults;
import org.geysermc.paletter.internal.data.VersionFallbacks;
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
        for (int i = 0; i < Ids.blockCount(palette); i++) {
            String name = BlockStates.name(Ids.canonicalBlocks(palette).state(i));
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
        for (int i = 0; i < Ids.itemCount(palette); i++) {
            ItemVariant variant = Ids.canonicalItems(palette).variant(i);
            if (palette.items().getDefinition(variant.identifier()) == null) {
                return i;
            }
        }
        throw new AssertionError("the oldest version has every item of the newest");
    }

    private static PaletteLayer.Builder broken(Palette vanilla, int item, String id) {
        return PaletteLayer.builder(id).itemFallback(Ids.canonicalItems(vanilla).variant(item).identifier(), "paletter:nothing");
    }

    @Test
    void missingContentFallsBackToUnknownByDefault() {
        Palette palette = PALETTER.palette(OLDEST);
        int block = blockWithoutFallback(palette);
        assertEquals("minecraft:unknown", BlockStates.name(Ids.blockAt(palette, block).getState()));
        assertTrue(palette.blocks().missingCanonical().contains(Ids.canonicalBlocks(palette).state(block)));

        int item = missingItem(palette);
        ResolvedItem resolved = PALETTER.palette(OLDEST, broken(palette, item, "broken").build()).items().item(item);
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
        String name = BlockStates.name(Ids.canonicalBlocks(palette).state(block));
        assertEquals("minecraft:unknown", BlockStates.name(Ids.blockAt(palette, block).getState()));
        Palette layered = PALETTER.palette(OLDEST, PaletteLayer.builder("fallback").blockFallback(name, BlockFallback.of("minecraft:dirt")).build());
        assertEquals("minecraft:dirt", BlockStates.name(Ids.blockAt(layered, block).getState()));
        assertEquals(BlockResolution.Kind.FALLBACK, layered.explain(Ids.canonicalBlocks(palette).state(block)).kind());
    }

    @Test
    void layerFallbacksApplyPerVersion() {
        Palette vanilla = PALETTER.palette(OLDEST);
        int block = blockWithoutFallback(vanilla);
        int missing = missingBlock(vanilla, BlockStates.name(Ids.canonicalBlocks(vanilla).state(block)));
        String name = BlockStates.name(Ids.canonicalBlocks(vanilla).state(block));
        String missingName = BlockStates.name(Ids.canonicalBlocks(vanilla).state(missing));
        String oldest = vanilla.version().name();

        PaletteLayer layer = PaletteLayer.builder("fallbacks")
            .blockFallback(name, BlockFallback.of("minecraft:dirt"))                  // every version
            .blockFallback(oldest, name, BlockFallback.of("minecraft:stone"))         // this version: wins
            .blockFallback("1.0", missingName, BlockFallback.of("minecraft:stone"))   // another version: ignored
            .build();
        Palette palette = PALETTER.palette(OLDEST, layer);
        assertEquals("minecraft:stone", BlockStates.name(Ids.blockAt(palette, block).getState()));
        assertEquals("minecraft:unknown", BlockStates.name(Ids.blockAt(palette, missing).getState()));

        int item = missingItem(vanilla);
        String itemName = Ids.canonicalItems(vanilla).variant(item).identifier();
        Palette withItem = PALETTER.palette(OLDEST, PaletteLayer.builder("items")
            .itemFallback(oldest, itemName, ItemFallback.of("minecraft:stick")).build());
        assertEquals("minecraft:stick", Ids.itemAt(withItem, item).definition().getIdentifier());
    }

    @Test
    void defaultsCanBeChangedOrSwitchedOff() {
        Palette vanilla = PALETTER.palette(OLDEST);
        int block = blockWithoutFallback(vanilla);
        int item = missingItem(vanilla);

        Palette none = PALETTER.palette(OLDEST, broken(vanilla, item, "none").fallbackDefaults(FallbackDefaults.NONE).build());
        assertSame(none.blocks().air(), Ids.blockAt(none, block));
        assertNull(Ids.itemAt(none, item));

        Palette custom = PALETTER.palette(OLDEST, broken(vanilla, item, "custom")
            .fallbackDefaults(new FallbackDefaults(BlockFallback.of("minecraft:bedrock"), ItemFallback.of("minecraft:barrier")))
            .build());
        assertEquals("minecraft:bedrock", BlockStates.name(Ids.blockAt(custom, block).getState()));
        assertEquals("minecraft:barrier", Ids.itemAt(custom, item).definition().getIdentifier());

        // Layers above inherit the default of the layers below
        Palette above = custom.extend(PaletteLayer.builder("session").build());
        assertEquals("minecraft:bedrock", BlockStates.name(Ids.blockAt(above, block).getState()));
    }

    @Test
    void sharedFallbacksAreNotMatched() {
        Palette vanilla = PALETTER.palette(OLDEST);
        // Two canonical items without a usable fallback: both resolve to the shared minecraft:unknown item
        java.util.List<Integer> missing = new java.util.ArrayList<>();
        for (int i = 0; i < Ids.itemCount(vanilla) && missing.size() < 2; i++) {
            if (vanilla.items().getDefinition(Ids.canonicalItems(vanilla).variant(i).identifier()) == null) {
                missing.add(i);
            }
        }
        assertEquals(2, missing.size(), "fixture: the oldest version lacks two items of the newest");
        PaletteLayer.Builder layer = PaletteLayer.builder("shared");
        for (int item : missing) {
            layer.itemFallback(Ids.canonicalItems(vanilla).variant(item).identifier(), "paletter:nothing");
        }
        Palette palette = PALETTER.palette(OLDEST, layer.build());
        ResolvedItem first = Ids.itemAt(palette, missing.get(0));
        ResolvedItem second = Ids.itemAt(palette, missing.get(1));
        assertSame(first.definition(), second.definition());
        assertEquals("minecraft:unknown", first.definition().getIdentifier());

        // The client sending minecraft:unknown is no claim on either of them. If minecraft:unknown is a canonical item
        // of its own, that direct variant matches
        int unknown = Ids.item(PALETTER, ItemVariant.of("minecraft:unknown"));
        int matched = Ids.match(palette, first.toItemData().build());
        assertEquals(unknown, matched);
        assertTrue(matched != missing.get(0) && matched != missing.get(1));

        // A fallback reached by one canonical id only still matches it, and direct variants are unaffected
        Palette single = PALETTER.palette(OLDEST, broken(vanilla, missing.get(0), "single").build());
        int apple = Ids.item(PALETTER, ItemVariant.of("minecraft:apple"));
        assertEquals(apple, Ids.match(single, Ids.itemAt(single, apple).toItemData().build()));
        assertEquals(unknown == -1 ? missing.get(0) : unknown, Ids.match(single, Ids.itemAt(single, missing.get(0)).toItemData().build()));
    }

    @Test
    void aBlockItemResolvesTheSameSpelledWithItsDefaultBlockState() {
        Palette latest = PALETTER.palette(PALETTER.latest().protocolVersion());
        Palette vanilla = PALETTER.palette(OLDEST);
        // A block item the oldest version lacks, as the newest creative inventory spells it (with a block state)
        var stack = latest.creativeLayout().stacks()
            .filter(s -> s.blockState() != null && vanilla.items().getDefinition(s.identifier()) == null)
            .findFirst().orElseThrow(() -> new AssertionError("fixture: the oldest version lacks a block item of the newest"));
        int id = Ids.canonicalItems(vanilla).index(stack.variant());
        assertTrue(id != -1);
        Palette palette = PALETTER.palette(OLDEST, broken(vanilla, id, "broken").build());
        ResolvedItem plain = Ids.itemAt(palette, id);
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
