package dev.opencollab.paletter;

import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.item.BedrockItemStack;
import dev.opencollab.paletter.item.ItemFallback;
import dev.opencollab.paletter.item.ResolvedItem;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import dev.opencollab.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResolvedItem#fallback} and {@link ResolvedItem#variant} of {@link Palette#resolve}: the cheap answer to "is this
 * variant sent as itself".
 */
class FallbackQueryTest {
    private static final int OLDEST = 2168;
    private static final Paletter PALETTER = Paletter.bundled();

    /** Variants of the newest version that the oldest lacks. */
    private static List<BedrockItemVariant> missingInOldest() {
        Palette oldest = PALETTER.palette(OLDEST);
        List<BedrockItemVariant> missing = new ArrayList<>();
        for (BedrockItemVariant variant : PALETTER.palette(Integer.MAX_VALUE).itemVariants()) {
            if (oldest.itemDefinition(variant.type().identifier()) == null) {
                missing.add(variant);
            }
        }
        assertFalse(missing.isEmpty(), "fixture: the oldest version lacks items of the newest");
        return missing;
    }

    @Test
    void theNewestVersionSendsEverythingAsItself() {
        Palette newest = Palettes.palette(Integer.MAX_VALUE);
        for (BedrockItemVariant variant : newest.itemVariants()) {
            assertFalse(newest.resolve(variant).fallback(), variant.toString());
            assertEquals(variant, newest.resolve(variant).variant(), variant.toString());
        }
        // Content behind an experiment the Paletter did not enable is missing even in the newest version
        assertTrue(PALETTER.palette(Integer.MAX_VALUE).resolve(BedrockItems.ICICLE.variant()).fallback());
    }

    @Test
    void missingItemsFallBackToUnknownByDefault() {
        Palette oldest = PALETTER.palette(OLDEST);
        for (BedrockItemVariant variant : missingInOldest()) {
            assertTrue(oldest.resolve(variant).fallback(), variant.toString());
            assertEquals(BedrockItems.UNKNOWN.variant(), oldest.resolve(variant).variant(), variant.toString());
        }
    }

    @Test
    void existingItemsAreNotFallbacks() {
        Palette oldest = PALETTER.palette(OLDEST);
        assertFalse(oldest.resolve(BedrockItems.STICK.variant()).fallback());
        assertEquals(BedrockItems.STICK.variant(), oldest.resolve(BedrockItems.STICK.variant()).variant());
        assertFalse(oldest.resolve(BedrockItems.DIAMOND_SWORD.variant()).fallback());
    }

    @Test
    void theAnswerAgreesWithExplain() {
        Palette oldest = PALETTER.palette(OLDEST);
        int checked = 0;
        for (BedrockItemVariant variant : oldest.itemVariants()) {
            if (checked++ % 5 != 0) {
                continue;
            }
            ResolutionKind kind = oldest.explain(variant.type().identifier(), variant.aux(), null).kind();
            boolean fallback = kind == ResolutionKind.FALLBACK || kind == ResolutionKind.DEFAULT;
            assertEquals(fallback, oldest.resolve(variant).fallback(), variant + " " + kind);
            assertEquals(fallback, !variant.equals(oldest.resolve(variant).variant()), variant + " " + kind);
            if (fallback) {
                assertEquals(oldest.explain(variant.type().identifier(), variant.aux(), null).item(), oldest.resolve(variant).variant().type().identifier(), variant.toString());
            }
        }
        assertTrue(checked > 100);
    }

    @Test
    void aFallbacksEntryNamesItsTarget() {
        BedrockItemVariant missing = missingInOldest().getFirst();
        Palette type = Paletter.builder()
            .fallbacks(Fallbacks.builder().item(missing.type(), ItemFallback.of(BedrockItems.STICK.variant())).build())
            .build().palette(OLDEST);
        assertTrue(type.resolve(missing).fallback());
        // The aux of the replaced variant is kept
        assertEquals(BedrockItems.STICK.variant(missing.aux()), type.resolve(missing).variant());

        BedrockItemVariant target = BedrockItems.STICK.variant(3);
        Palette variant = Paletter.builder()
            .fallbacks(Fallbacks.builder().item(missing.type(), ItemFallback.of(target)).build())
            .build().palette(OLDEST);
        assertEquals(target, variant.resolve(missing).variant());
    }

    @Test
    void aForcedFallbackReplacesAnExistingItem() {
        Palette forced = Paletter.builder()
            .fallbacks(Fallbacks.builder().item(BedrockItems.DIAMOND_SWORD, ItemFallback.of(BedrockItems.STICK.variant()).forced()).build())
            .build().palette(OLDEST);
        assertTrue(forced.resolve(BedrockItems.DIAMOND_SWORD.variant()).fallback());
        assertEquals(BedrockItems.STICK.variant(), forced.resolve(BedrockItems.DIAMOND_SWORD.variant()).variant());
        assertFalse(forced.resolve(BedrockItems.STICK.variant()).fallback());
        assertEquals(ResolutionKind.FALLBACK, forced.resolve(BedrockItems.DIAMOND_SWORD.variant()).kind());
        assertEquals(ResolutionKind.EXACT, forced.resolve(BedrockItems.STICK.variant()).kind());
    }

    @Test
    void aResolutionSaysHowItWasResolved() {
        Palette oldest = Paletter.bundled().palette(OLDEST);
        BedrockItemVariant missing = missingInOldest().getFirst();
        assertEquals(ResolutionKind.DEFAULT, oldest.resolve(missing).kind());
        assertTrue(oldest.resolve(missing).fallback());
        assertEquals(oldest.resolve(missing), oldest.resolve(missing.type().variant(missing.aux())));
        for (dev.opencollab.paletter.vanilla.BedrockBlockState state : List.of(
                dev.opencollab.paletter.vanilla.BedrockBlocks.STONE.defaultState(), dev.opencollab.paletter.vanilla.BedrockBlocks.AIR.defaultState())) {
            assertEquals(ResolutionKind.EXACT, oldest.resolve(state).kind(), state.toString());
        }
        Palette none = Paletter.builder().fallbacks(Fallbacks.NONE).build().palette(OLDEST);
        boolean defaults = false;
        for (dev.opencollab.paletter.vanilla.BedrockBlockType type : oldest.report().unknownBlocks()) {
            defaults = true;
            assertEquals(ResolutionKind.DEFAULT, oldest.resolve(type.defaultState()).kind(), type.toString());
            assertEquals(ResolutionKind.MISSING, none.resolve(type.defaultState()).kind(), type.toString());
        }
        assertTrue(defaults, "the oldest version lacks blocks of the newest");
    }

    @Test
    void anItemWithoutFallbackIsNeitherAFallbackNorSent() {
        Palette none = Paletter.builder().fallbacks(Fallbacks.NONE).build().palette(OLDEST);
        BedrockItemVariant missing = missingInOldest().getFirst();
        assertNull(none.resolve(missing, null));
        assertThrows(IllegalArgumentException.class, () -> none.resolve(missing));
        assertNull(none.itemDefinition(missing.type().identifier()));

        // A key Paletter has never heard of
        BedrockItemVariant custom = Access.vanilla().itemType("paletter:nothing").variant();
        assertNull(none.resolve(custom, null));
    }

    @Test
    void anUnknownAuxTakesTheSamePath() {
        // An aux value that is not a variant of the newest data takes the same path as a known one
        Palette oldest = PALETTER.palette(OLDEST);
        assertFalse(oldest.resolve(BedrockItems.STICK.variant(99)).fallback());
        BedrockItemVariant missing = missingInOldest().getFirst();
        // The default fallback serves every variant of a missing item, canonical or not, as encode does
        BedrockItemVariant unlisted = missing.type().variant(99);
        assertTrue(oldest.resolve(unlisted).fallback());
        assertEquals("minecraft:unknown", oldest.encode(BedrockItemStack.of(unlisted)).getDefinition().getIdentifier());
    }
}
