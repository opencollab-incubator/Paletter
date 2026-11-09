package org.geysermc.paletter;

import org.geysermc.paletter.item.ItemFallback;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.geysermc.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Palette#isFallback} and {@link Palette#fallbackOf}: the cheap answer to "is this variant sent as itself".
 */
class FallbackQueryTest {
    private static final int OLDEST = 2168;
    private static final Paletter PALETTER = Paletter.bundled();

    /** Variants of the newest version that the oldest lacks. */
    private static List<BedrockItemVariant> missingInOldest() {
        Palette oldest = PALETTER.palette(OLDEST);
        List<BedrockItemVariant> missing = new ArrayList<>();
        for (BedrockItemVariant variant : PALETTER.palette(Integer.MAX_VALUE).itemVariants()) {
            if (oldest.item(variant) == null) {
                missing.add(variant);
            }
        }
        assertFalse(missing.isEmpty(), "fixture: the oldest version lacks items of the newest");
        return missing;
    }

    @Test
    void theNewestVersionSendsEverythingAsItself() {
        Palette newest = PALETTER.palette(Integer.MAX_VALUE);
        for (BedrockItemVariant variant : newest.itemVariants()) {
            assertFalse(newest.isFallback(variant), variant.toString());
            assertNull(newest.fallbackOf(variant), variant.toString());
        }
    }

    @Test
    void missingItemsFallBackToUnknownByDefault() {
        Palette oldest = PALETTER.palette(OLDEST);
        for (BedrockItemVariant variant : missingInOldest()) {
            assertTrue(oldest.isFallback(variant), variant.toString());
            assertEquals(BedrockItems.UNKNOWN.base(), oldest.fallbackOf(variant), variant.toString());
        }
    }

    @Test
    void existingItemsAreNotFallbacks() {
        Palette oldest = PALETTER.palette(OLDEST);
        assertFalse(oldest.isFallback(BedrockItems.STICK.base()));
        assertNull(oldest.fallbackOf(BedrockItems.STICK.base()));
        assertFalse(oldest.isFallback(BedrockItems.DIAMOND_SWORD.base()));
    }

    @Test
    void theAnswerAgreesWithExplain() {
        Palette oldest = PALETTER.palette(OLDEST);
        int checked = 0;
        for (BedrockItemVariant variant : oldest.itemVariants()) {
            if (checked++ % 5 != 0) {
                continue;
            }
            ItemExplanation.Kind kind = oldest.explain(variant).kind();
            boolean fallback = kind == ItemExplanation.Kind.FALLBACK || kind == ItemExplanation.Kind.DEFAULT;
            assertEquals(fallback, oldest.isFallback(variant), variant + " " + kind);
            assertEquals(fallback, oldest.fallbackOf(variant) != null, variant + " " + kind);
            if (fallback) {
                assertEquals(oldest.explain(variant).item(), oldest.fallbackOf(variant).type().identifier(), variant.toString());
            }
        }
        assertTrue(checked > 100);
    }

    @Test
    void aFallbacksEntryNamesItsTarget() {
        BedrockItemVariant missing = missingInOldest().getFirst();
        Palette type = Paletter.builder()
            .fallbacks(Fallbacks.builder().item(missing.type(), ItemFallback.of(BedrockItems.STICK)).build())
            .build().palette(OLDEST);
        assertTrue(type.isFallback(missing));
        // The aux of the replaced variant is kept
        assertEquals(BedrockItems.STICK.variantOf(missing.aux()), type.fallbackOf(missing));

        BedrockItemVariant target = BedrockItems.STICK.variantOf(3);
        Palette variant = Paletter.builder()
            .fallbacks(Fallbacks.builder().item(missing.type(), ItemFallback.of(target)).build())
            .build().palette(OLDEST);
        assertEquals(target, variant.fallbackOf(missing));
    }

    @Test
    void aForcedFallbackReplacesAnExistingItem() {
        Palette forced = Paletter.builder()
            .fallbacks(Fallbacks.builder().item(BedrockItems.DIAMOND_SWORD, ItemFallback.of(BedrockItems.STICK).forced()).build())
            .build().palette(OLDEST);
        assertTrue(forced.isFallback(BedrockItems.DIAMOND_SWORD.base()));
        assertEquals(BedrockItems.STICK.base(), forced.fallbackOf(BedrockItems.DIAMOND_SWORD.base()));
        assertFalse(forced.isFallback(BedrockItems.STICK.base()));
    }

    @Test
    void anItemWithoutFallbackIsNeitherAFallbackNorSent() {
        Palette none = Paletter.builder().fallbacks(Fallbacks.NONE).build().palette(OLDEST);
        BedrockItemVariant missing = missingInOldest().getFirst();
        assertFalse(none.isFallback(missing));
        assertNull(none.fallbackOf(missing));
        assertNull(none.item(missing));

        // A key Paletter has never heard of
        BedrockItemVariant custom = BedrockItemType.of("paletter:nothing").base();
        assertFalse(none.isFallback(custom));
        assertNull(none.fallbackOf(custom));
    }

    @Test
    void anUnknownAuxTakesTheSamePath() {
        // An aux value that is not a variant of the newest data takes the same path as a known one
        Palette oldest = PALETTER.palette(OLDEST);
        assertFalse(oldest.isFallback(BedrockItems.STICK.variantOf(99)));
        BedrockItemVariant missing = missingInOldest().getFirst();
        // The default fallback only serves the variants of the newest data; encode gives air for this one too
        assertFalse(oldest.isFallback(missing.type().variantOf(99)));
    }
}
