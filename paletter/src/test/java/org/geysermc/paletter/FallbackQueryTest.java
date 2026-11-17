package org.geysermc.paletter;

import org.geysermc.paletter.item.BedrockItemStack;
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
 * {@link org.geysermc.paletter.item.ResolvedItem#fallback} and {@link org.geysermc.paletter.item.ResolvedItem#sent}: the cheap answer to "is this variant sent as itself".
 */
class FallbackQueryTest {
    private static final int OLDEST = 2168;
    private static final Paletter PALETTER = Paletter.bundled();

    /** Variants of the newest version that the oldest lacks. */
    private static List<BedrockItemVariant> missingInOldest() {
        Palette oldest = PALETTER.palette(OLDEST);
        List<BedrockItemVariant> missing = new ArrayList<>();
        for (BedrockItemVariant variant : PALETTER.palette(Integer.MAX_VALUE).itemVariants()) {
            if (oldest.itemDefinition(variant.type()) == null) {
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
            assertFalse(Palettes.isFallback(newest, variant), variant.toString());
            assertNull(Palettes.fallbackOf(newest, variant), variant.toString());
        }
        // Content behind an experiment the Paletter did not enable is missing even in the newest version
        assertTrue(Palettes.isFallback(PALETTER.palette(Integer.MAX_VALUE), BedrockItems.ICICLE.base()));
    }

    @Test
    void missingItemsFallBackToUnknownByDefault() {
        Palette oldest = PALETTER.palette(OLDEST);
        for (BedrockItemVariant variant : missingInOldest()) {
            assertTrue(Palettes.isFallback(oldest, variant), variant.toString());
            assertEquals(BedrockItems.UNKNOWN.base(), Palettes.fallbackOf(oldest, variant), variant.toString());
        }
    }

    @Test
    void existingItemsAreNotFallbacks() {
        Palette oldest = PALETTER.palette(OLDEST);
        assertFalse(Palettes.isFallback(oldest, BedrockItems.STICK.base()));
        assertNull(Palettes.fallbackOf(oldest, BedrockItems.STICK.base()));
        assertFalse(Palettes.isFallback(oldest, BedrockItems.DIAMOND_SWORD.base()));
    }

    @Test
    void theAnswerAgreesWithExplain() {
        Palette oldest = PALETTER.palette(OLDEST);
        int checked = 0;
        for (BedrockItemVariant variant : oldest.itemVariants()) {
            if (checked++ % 5 != 0) {
                continue;
            }
            ItemExplanation.Kind kind = oldest.explain(variant.type().identifier(), variant.aux(), null).kind();
            boolean fallback = kind == ItemExplanation.Kind.FALLBACK || kind == ItemExplanation.Kind.DEFAULT;
            assertEquals(fallback, Palettes.isFallback(oldest, variant), variant + " " + kind);
            assertEquals(fallback, Palettes.fallbackOf(oldest, variant) != null, variant + " " + kind);
            if (fallback) {
                assertEquals(oldest.explain(variant.type().identifier(), variant.aux(), null).item(), Palettes.fallbackOf(oldest, variant).type().identifier(), variant.toString());
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
        assertTrue(Palettes.isFallback(type, missing));
        // The aux of the replaced variant is kept
        assertEquals(BedrockItems.STICK.variantOf(missing.aux()), Palettes.fallbackOf(type, missing));

        BedrockItemVariant target = BedrockItems.STICK.variantOf(3);
        Palette variant = Paletter.builder()
            .fallbacks(Fallbacks.builder().item(missing.type(), ItemFallback.of(target)).build())
            .build().palette(OLDEST);
        assertEquals(target, Palettes.fallbackOf(variant, missing));
    }

    @Test
    void aForcedFallbackReplacesAnExistingItem() {
        Palette forced = Paletter.builder()
            .fallbacks(Fallbacks.builder().item(BedrockItems.DIAMOND_SWORD, ItemFallback.of(BedrockItems.STICK).forced()).build())
            .build().palette(OLDEST);
        assertTrue(Palettes.isFallback(forced, BedrockItems.DIAMOND_SWORD.base()));
        assertEquals(BedrockItems.STICK.base(), Palettes.fallbackOf(forced, BedrockItems.DIAMOND_SWORD.base()));
        assertFalse(Palettes.isFallback(forced, BedrockItems.STICK.base()));
    }

    @Test
    void anItemWithoutFallbackIsNeitherAFallbackNorSent() {
        Palette none = Paletter.builder().fallbacks(Fallbacks.NONE).build().palette(OLDEST);
        BedrockItemVariant missing = missingInOldest().getFirst();
        assertFalse(Palettes.isFallback(none, missing));
        assertNull(Palettes.fallbackOf(none, missing));
        assertNull(none.itemDefinition(missing.type()));

        // A key Paletter has never heard of
        BedrockItemVariant custom = BedrockItemType.of("paletter:nothing").base();
        assertFalse(Palettes.isFallback(none, custom));
        assertNull(Palettes.fallbackOf(none, custom));
    }

    @Test
    void anUnknownAuxTakesTheSamePath() {
        // An aux value that is not a variant of the newest data takes the same path as a known one
        Palette oldest = PALETTER.palette(OLDEST);
        assertFalse(Palettes.isFallback(oldest, BedrockItems.STICK.variantOf(99)));
        BedrockItemVariant missing = missingInOldest().getFirst();
        // The default fallback serves every variant of a missing item, canonical or not, as encode does
        BedrockItemVariant unlisted = missing.type().variantOf(99);
        assertTrue(Palettes.isFallback(oldest, unlisted));
        assertEquals("minecraft:unknown", oldest.encode(BedrockItemStack.of(unlisted)).getDefinition().getIdentifier());
    }
}
