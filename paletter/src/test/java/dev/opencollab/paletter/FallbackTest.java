package dev.opencollab.paletter;

import dev.opencollab.paletter.item.ResolvedItem;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.block.BlockFallback;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.data.VersionFallbacks;
import dev.opencollab.paletter.item.BedrockItemStack;
import dev.opencollab.paletter.item.ItemFallback;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockBlockType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import dev.opencollab.paletter.vanilla.BedrockBlocks;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.vanilla.BedrockItems;
import dev.opencollab.paletter.internal.item.ItemVariant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Content of the newest version that an older one lacks: Paletter bundles no fallbacks, so it is the
 * {@code minecraft:unknown} default, which the {@link Fallbacks} policy of a Paletter overrides.
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

    /** Points the item at nothing, which keeps a test independent of other fallbacks. */
    private static Fallbacks.Builder broken(Palette vanilla, int item) {
        return Fallbacks.builder().item(BedrockItemType.fromIdentifier(Ids.canonicalItems(vanilla).variant(item).identifier()), ItemFallback.of(BedrockItemType.fromIdentifier("paletter:nothing")));
    }

    private static Paletter with(Fallbacks.Builder fallbacks) {
        return Paletter.builder().fallbacks(fallbacks.build()).build();
    }

    @Test
    void missingContentFallsBackToUnknownByDefault() {
        Palette palette = PALETTER.palette(OLDEST);
        int block = blockWithoutFallback(palette);
        assertEquals("minecraft:unknown", BlockStates.name(Ids.blockAt(palette, block).getState()));
        assertTrue(palette.blocks().missingCanonical().contains(Ids.canonicalBlocks(palette).state(block)));

        int item = missingItem(palette);
        ResolvedItem resolved = with(broken(palette, item)).palette(OLDEST).items().item(item);
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
    void fallbacksAreHowConsumersReplaceTheDefault() {
        Palette palette = PALETTER.palette(OLDEST);
        int block = blockWithoutFallback(palette);
        String name = BlockStates.name(Ids.canonicalBlocks(palette).state(block));
        assertEquals("minecraft:unknown", BlockStates.name(Ids.blockAt(palette, block).getState()));
        Palette layered = with(Fallbacks.builder().block(BedrockBlockType.fromIdentifier(name), BlockFallback.of(BedrockBlocks.DIRT))).palette(OLDEST);
        assertEquals("minecraft:dirt", BlockStates.name(Ids.blockAt(layered, block).getState()));
        assertEquals(ResolutionKind.FALLBACK, layered.explain(Ids.canonicalBlocks(palette).state(block)).kind());
    }

    @Test
    void targetsAreKeysAndMayBeExactStates() {
        Palette vanilla = PALETTER.palette(OLDEST);
        int block = blockWithoutFallback(vanilla);
        String name = BlockStates.name(Ids.canonicalBlocks(vanilla).state(block));
        BedrockBlockState exact = BedrockBlocks.STONE.states().getLast();
        Palette palette = with(Fallbacks.builder().block(BedrockBlockType.fromIdentifier(name), BlockFallback.of(exact))).palette(OLDEST);
        assertEquals(exact.toNbt(), Ids.blockAt(palette, block).getState());

        // A key needs no existence: a custom one is fine, it just resolves to nothing
        assertEquals(BedrockBlockType.fromIdentifier("paletter:nothing"), BlockFallback.of(BedrockBlockType.fromIdentifier("paletter:nothing")).target());
        assertEquals(BedrockBlocks.STONE, BlockFallback.of(exact).target());
        assertEquals(BlockFallback.of(exact).forced(), BlockFallback.of(exact).forced());
        assertEquals(BedrockItems.APPLE, ItemFallback.of(BedrockItems.APPLE.variant(0)).target());
    }

    @Test
    void identifiersWorkLikeTypes() {
        Palette vanilla = PALETTER.palette(OLDEST);
        int block = blockWithoutFallback(vanilla);
        String name = BlockStates.name(Ids.canonicalBlocks(vanilla).state(block));
        Palette withBlock = with(Fallbacks.builder().block(name, BlockFallback.of(BedrockBlocks.STONE))).palette(OLDEST);
        assertEquals("minecraft:stone", BlockStates.name(Ids.blockAt(withBlock, block).getState()));

        int item = missingItem(vanilla);
        String itemName = Ids.canonicalItems(vanilla).variant(item).identifier();
        Palette withItem = with(Fallbacks.builder().item(itemName, ItemFallback.of(BedrockItems.STICK))).palette(OLDEST);
        assertEquals("minecraft:stick", Ids.itemAt(withItem, item).definition().getIdentifier());

        assertThrows(NullPointerException.class, () -> Fallbacks.builder().block((String) null, BlockFallback.of(BedrockBlocks.STONE)));
        assertThrows(NullPointerException.class, () -> Fallbacks.builder().item((String) null, ItemFallback.of(BedrockItems.STICK)));
        assertThrows(NullPointerException.class, () -> Fallbacks.builder().block(name, (BlockFallback) null));
        assertThrows(NullPointerException.class, () -> Fallbacks.builder().item(itemName, (ItemFallback) null));
    }

    @Test
    void fallbacksApplyPerVersion() {
        Palette vanilla = PALETTER.palette(OLDEST);
        int block = blockWithoutFallback(vanilla);
        int missing = missingBlock(vanilla, BlockStates.name(Ids.canonicalBlocks(vanilla).state(block)));
        String name = BlockStates.name(Ids.canonicalBlocks(vanilla).state(block));
        String missingName = BlockStates.name(Ids.canonicalBlocks(vanilla).state(missing));
        DataVersion oldest = vanilla.version();

        Fallbacks fallbacks = Fallbacks.builder()
            .block(BedrockBlockType.fromIdentifier(name), BlockFallback.of(BedrockBlocks.DIRT))                  // every version
            .block(oldest, BedrockBlockType.fromIdentifier(name), BlockFallback.of(BedrockBlocks.STONE))         // this version: wins
            .block(DataVersion.of("1.0", 1), BedrockBlockType.fromIdentifier(missingName), BlockFallback.of(BedrockBlocks.STONE))   // another version: ignored
            .build();
        Palette palette = Paletter.builder().fallbacks(fallbacks).build().palette(OLDEST);
        assertEquals("minecraft:stone", BlockStates.name(Ids.blockAt(palette, block).getState()));
        assertEquals("minecraft:unknown", BlockStates.name(Ids.blockAt(palette, missing).getState()));

        int item = missingItem(vanilla);
        String itemName = Ids.canonicalItems(vanilla).variant(item).identifier();
        Palette withItem = with(Fallbacks.builder().item(oldest, BedrockItemType.fromIdentifier(itemName), ItemFallback.of(BedrockItems.STICK))).palette(OLDEST);
        assertEquals("minecraft:stick", Ids.itemAt(withItem, item).definition().getIdentifier());
    }

    @Test
    void defaultsCanBeChangedOrSwitchedOff() {
        Palette vanilla = PALETTER.palette(OLDEST);
        int block = blockWithoutFallback(vanilla);
        int item = missingItem(vanilla);

        Palette none = with(broken(vanilla, item).defaultBlock(null).defaultItem(null)).palette(OLDEST);
        Palette noneConstant = Paletter.builder().fallbacks(Fallbacks.NONE).build().palette(OLDEST);
        assertSame(noneConstant.blocks().air(), Ids.blockAt(noneConstant, block));
        assertSame(none.blocks().air(), Ids.blockAt(none, block));
        assertNull(Ids.itemAt(none, item));

        Palette custom = with(broken(vanilla, item)
            .defaultBlock(BlockFallback.of(BedrockBlocks.BEDROCK)).defaultItem(ItemFallback.of(BedrockItems.BARRIER))).palette(OLDEST);
        assertEquals("minecraft:bedrock", BlockStates.name(Ids.blockAt(custom, block).getState()));
        assertEquals("minecraft:barrier", Ids.itemAt(custom, item).definition().getIdentifier());

        // Session layers inherit the policy
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
        Fallbacks.Builder fallbacks = Fallbacks.builder();
        for (int item : missing) {
            fallbacks.item(BedrockItemType.fromIdentifier(Ids.canonicalItems(vanilla).variant(item).identifier()), ItemFallback.of(BedrockItemType.fromIdentifier("paletter:nothing")));
        }
        Palette palette = with(fallbacks).palette(OLDEST);
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
        Palette single = with(broken(vanilla, missing.get(0))).palette(OLDEST);
        int apple = Ids.item(PALETTER, ItemVariant.of("minecraft:apple"));
        assertEquals(apple, Ids.match(single, Ids.itemAt(single, apple).toItemData().build()));
        assertEquals(unknown == -1 ? missing.get(0) : unknown, Ids.match(single, Ids.itemAt(single, missing.get(0)).toItemData().build()));
    }

    private static ItemVariant spelled(BedrockItemStack stack) {
        return stack.variant().toItemVariant().withBlockState(stack.blockState() == null ? null : stack.blockState().toNbt());
    }

    @Test
    void aBlockItemResolvesTheSameSpelledWithItsDefaultBlockState() {
        Palette latest = PALETTER.palette(PALETTER.latest().protocolVersion());
        Palette vanilla = PALETTER.palette(OLDEST);
        // A block item the oldest version lacks, as the newest creative inventory spells it (with a block state)
        var stack = latest.creativeLayout().stacks()
            .filter(s -> s.blockState() != null && vanilla.items().getDefinition(s.type().identifier()) == null)
            .findFirst().orElseThrow(() -> new AssertionError("fixture: the oldest version lacks a block item of the newest"));
        int id = Ids.canonicalItems(vanilla).index(spelled(stack));
        assertTrue(id != -1);
        Palette palette = with(broken(vanilla, id)).palette(OLDEST);
        ResolvedItem plain = Ids.itemAt(palette, id);
        assertNotNull(plain);
        assertEquals(plain, palette.items().resolve(spelled(stack)));
        assertEquals(plain, palette.items().resolve(ItemVariant.of(stack.type().identifier())));
    }

    @Test
    void theNewestVersionNeedsNoFallback() {
        // With the experiments on: the newest version has content behind y_2026_drop_4
        Palette latest = Palettes.palette(PALETTER.latest().protocolVersion());
        assertTrue(latest.blocks().missingCanonical().isEmpty());
        assertTrue(latest.items().missingCanonical().isEmpty());
        NbtMap unknown = BlockStates.of("minecraft:unknown");
        assertNotNull(latest.blocks().exact(unknown));
    }
}
