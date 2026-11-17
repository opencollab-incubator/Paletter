package org.geysermc.paletter;

import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.item.ItemFallback;
import org.geysermc.paletter.item.ResolvedItem;
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
            assertFalse(newest.resolve(variant).fallback(), variant.toString());
            assertEquals(variant, newest.resolve(variant).variant(), variant.toString());
        }
        // Content behind an experiment the Paletter did not enable is missing even in the newest version
        assertTrue(PALETTER.palette(Integer.MAX_VALUE).resolve(BedrockItems.ICICLE.base()).fallback());
    }

    @Test
    void missingItemsFallBackToUnknownByDefault() {
        Palette oldest = PALETTER.palette(OLDEST);
        for (BedrockItemVariant variant : missingInOldest()) {
            assertTrue(oldest.resolve(variant).fallback(), variant.toString());
            assertEquals(BedrockItems.UNKNOWN.base(), oldest.resolve(variant).variant(), variant.toString());
        }
    }

    @Test
    void existingItemsAreNotFallbacks() {
        Palette oldest = PALETTER.palette(OLDEST);
        assertFalse(oldest.resolve(BedrockItems.STICK.base()).fallback());
        assertEquals(BedrockItems.STICK.base(), oldest.resolve(BedrockItems.STICK.base()).variant());
        assertFalse(oldest.resolve(BedrockItems.DIAMOND_SWORD.base()).fallback());
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
            .fallbacks(Fallbacks.builder().item(missing.type(), ItemFallback.of(BedrockItems.STICK)).build())
            .build().palette(OLDEST);
        assertTrue(type.resolve(missing).fallback());
        // The aux of the replaced variant is kept
        assertEquals(BedrockItems.STICK.variantOf(missing.aux()), type.resolve(missing).variant());

        BedrockItemVariant target = BedrockItems.STICK.variantOf(3);
        Palette variant = Paletter.builder()
            .fallbacks(Fallbacks.builder().item(missing.type(), ItemFallback.of(target)).build())
            .build().palette(OLDEST);
        assertEquals(target, variant.resolve(missing).variant());
    }

    @Test
    void aForcedFallbackReplacesAnExistingItem() {
        Palette forced = Paletter.builder()
            .fallbacks(Fallbacks.builder().item(BedrockItems.DIAMOND_SWORD, ItemFallback.of(BedrockItems.STICK).forced()).build())
            .build().palette(OLDEST);
        assertTrue(forced.resolve(BedrockItems.DIAMOND_SWORD.base()).fallback());
        assertEquals(BedrockItems.STICK.base(), forced.resolve(BedrockItems.DIAMOND_SWORD.base()).variant());
        assertFalse(forced.resolve(BedrockItems.STICK.base()).fallback());
    }

    @Test
    void anItemWithoutFallbackIsNeitherAFallbackNorSent() {
        Palette none = Paletter.builder().fallbacks(Fallbacks.NONE).build().palette(OLDEST);
        BedrockItemVariant missing = missingInOldest().getFirst();
        assertNull(none.resolve(missing));
        assertNull(none.itemDefinition(missing.type()));

        // A key Paletter has never heard of
        BedrockItemVariant custom = BedrockItemType.of("paletter:nothing").base();
        assertNull(none.resolve(custom));
    }

    @Test
    void anUnknownAuxTakesTheSamePath() {
        // An aux value that is not a variant of the newest data takes the same path as a known one
        Palette oldest = PALETTER.palette(OLDEST);
        assertFalse(oldest.resolve(BedrockItems.STICK.variantOf(99)).fallback());
        BedrockItemVariant missing = missingInOldest().getFirst();
        // The default fallback serves every variant of a missing item, canonical or not, as encode does
        BedrockItemVariant unlisted = missing.type().variantOf(99);
        assertTrue(oldest.resolve(unlisted).fallback());
        assertEquals("minecraft:unknown", oldest.encode(BedrockItemStack.of(unlisted)).getDefinition().getIdentifier());
    }
}
