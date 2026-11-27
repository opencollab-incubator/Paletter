package dev.opencollab.paletter.vanilla;

import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.internal.CanonicalItems;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.internal.item.ItemVariant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The named variants of the item types: the getters of {@link BedItemType} and the like, and {@link PotionItemType#withEffect}. */
class ItemVariantsTest {
    /** What a fresh JVM does with a constant: the main of {@link #referencingAConstantLoadsNoData()}. */
    public static final class Reference {
        public static void main(String[] args) {
            System.out.println("VARIANT " + BedrockItems.POTION.withEffect(BedrockPotionTypes.LONG_NIGHTVISION).aux());
        }
    }

    private static final List<String> DATA = List.of("VanillaIndex", "PaletteDataSource", "PaletteDataCodec", "VanillaData");

    /** What a fresh JVM does with item variants: the main of {@link #usingItemVariantsLoadsNoData}. */
    public static final class KeyReference {
        public static void main(String[] args) {
            BedrockItemVariant base = BedrockItems.POTION.base();
            BedrockItemVariant damaged = BedrockItems.POTION.variant(7);
            BedrockItemVariant custom = BedrockItemType.custom("example:ruby").variant(3);
            System.out.println("KEYS " + base.aux() + damaged.aux() + custom.aux());
        }
    }

    /** Runs the main of a class in a fresh JVM, with its class loading logged. */
    private static String run(Path temp, Class<?> main) throws IOException, InterruptedException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // The class path goes through an argument file: it can exceed the command line limit of Windows
        Path arguments = temp.resolve("java.args");
        String classPath = System.getProperty("java.class.path").replace("\\", "\\\\");
        Files.writeString(arguments, String.join("\n", "-verbose:class", "-cp", "\"" + classPath + "\"", main.getName()));
        Process process = new ProcessBuilder(java, "@" + arguments).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    @Test
    void referencingAConstantLoadsNoData(@TempDir Path temp) throws IOException, InterruptedException {
        String output = run(temp, Reference.class);
        assertTrue(output.contains("VARIANT 6"), output);
        // A fresh JVM that only reads a constant loads the constants classes, never the index or the data
        assertTrue(output.contains(BedrockPotionTypes.class.getName()), "the constants class was not loaded");
        for (String data : DATA) {
            assertFalse(output.contains("." + data + " source:"), data + " was loaded by referencing a constant");
        }
    }

    /** Keys made before any data is loaded, then checked against what the data gives: the main of {@link #keysMadeBeforeLoadingAreTheSame}. */
    public static final class PreLoad {
        static void check(boolean ok, String what) {
            if (!ok) {
                throw new IllegalStateException(what);
            }
        }

        public static void main(String[] args) {
            BedrockItemVariant bed = BedrockItems.BED.base();
            BedrockItemVariant potion = BedrockItems.POTION.variant(19);
            BedrockItemVariant preview = BedrockItems.BRITTLE_ICE.base();
            BedrockItemVariant previewDamaged = BedrockItems.BRITTLE_ICE.variant(3);

            Paletter paletter = Paletter.bundled();
            dev.opencollab.paletter.Palette palette = paletter.palette(paletter.latest().protocolVersion());
            check(bed == BedrockItems.BED.white(), "BED.base() is BED.white()");
            check(bed == BedrockItems.BED.base(), "BED.variant() is stable");
            check(potion == BedrockItems.POTION.withEffect(BedrockPotionTypes.WATER_BREATHING), "POTION.variant(19) is the named variant");
            check(potion == BedrockItems.POTION.variant(19), "POTION.variant(19) is stable");
            check(preview == BedrockItems.BRITTLE_ICE.base(), "an absent type's base is stable");
            check(previewDamaged.type() == BedrockItems.BRITTLE_ICE, "variants are of their own type, not the fallback's");
            check(palette.resolve(bed).equals(palette.resolve(BedrockItems.BED.base())), "bed resolves like its type");
            check(palette.resolve(potion).equals(palette.resolve(BedrockItems.POTION.variant(19))), "potion resolves like after loading");
            check(palette.resolve(preview).equals(palette.resolve(BedrockItems.BRITTLE_ICE.base())), "absent type resolves like its type");
            check(palette.resolve(previewDamaged).equals(palette.resolve(BedrockItems.BRITTLE_ICE.variant(3))), "absent variant resolves like after loading");
            System.out.println("PRELOAD OK");
        }
    }

    @Test
    void keysMadeBeforeLoadingAreTheSame(@TempDir Path temp) throws IOException, InterruptedException {
        String output = run(temp, PreLoad.class);
        assertTrue(output.contains("PRELOAD OK"), output);
    }

    @Test
    void aTypeAsAKeyMeansAuxZeroEverywhere() {
        var bed = BedrockItems.BED;
        assertEquals(dev.opencollab.paletter.item.ItemFallback.of(bed.base()), dev.opencollab.paletter.item.ItemFallback.of(bed.base()));
        var white = dev.opencollab.paletter.item.BedrockItemStack.of(bed.base());
        var silver = dev.opencollab.paletter.item.BedrockItemStack.of(bed.variant(8));
        assertTrue(dev.opencollab.paletter.creative.ItemMatcher.of(bed.base()).matches(white));
        assertFalse(dev.opencollab.paletter.creative.ItemMatcher.of(bed.base()).matches(silver));
        assertTrue(dev.opencollab.paletter.creative.ItemMatcher.of(bed.base()).matches(white));
        assertFalse(dev.opencollab.paletter.creative.ItemMatcher.of(bed.base()).matches(silver));
        assertTrue(dev.opencollab.paletter.creative.ItemMatcher.anyVariantOf(bed).matches(white));
        assertTrue(dev.opencollab.paletter.creative.ItemMatcher.anyVariantOf(bed).matches(silver));
    }

    @Test
    void aVariantWithoutAnIdResolvesToTheRightId() {
        CanonicalItems canonical = Access.palette().canonicalItems(Paletter.bundled());
        // Named constants and the base have no id of their own: the type's link knows it
        assertEquals(canonical.index(ItemVariant.of("minecraft:potion", 19)), BedrockItems.POTION.withEffect(BedrockPotionTypes.WATER_BREATHING).canonicalIn(canonical));
        assertEquals(canonical.index(ItemVariant.of("minecraft:bed", 0)), BedrockItems.BED.base().canonicalIn(canonical));
        assertEquals(canonical.index(ItemVariant.of("minecraft:apple", 0)), BedrockItems.APPLE.base().canonicalIn(canonical));
        // A variant the data does not have has none
        assertEquals(-1, BedrockItems.POTION.variant(9999).canonicalIn(canonical));
        // An absent type's variants have no id where the data lacks the item: they resolve through the fallbacks
        BedrockItemType absent = BedrockItems.BRITTLE_ICE;
        assertFalse(absent.isAvailable());
        assertEquals(-1, absent.base().canonicalIn(canonical));
        assertEquals(absent, absent.variant(3).type());
    }

    @Test
    void usingItemVariantsLoadsNoData(@TempDir Path temp) throws IOException, InterruptedException {
        String output = run(temp, KeyReference.class);
        assertTrue(output.contains("KEYS 073"), output);
        for (String data : DATA) {
            assertFalse(output.contains("." + data + " source:"), data + " was loaded by using an item variant");
        }
    }

    @Test
    void aTypeHasItsBaseVariant() {
        assertEquals(BedrockItems.POTION, BedrockItems.POTION.base().type());
        assertSame(BedrockItems.POTION.base(), BedrockItems.POTION.variant(0));
        Paletter paletter = Paletter.bundled();
        dev.opencollab.paletter.Palette palette = paletter.palette(paletter.latest().protocolVersion());
        assertSame(palette.resolve(BedrockItems.POTION.base()), palette.resolve(BedrockItems.POTION.base()));
        assertEquals(dev.opencollab.paletter.item.BedrockItemStack.of(BedrockItems.POTION.base()), dev.opencollab.paletter.item.BedrockItemStack.of(BedrockItems.POTION.base()));
    }

    @Test
    void anUnnamedAuxIsAVariantNotNull() {
        BedrockItemVariant odd = BedrockItems.POTION.variant(9999);
        assertEquals(9999, odd.aux());
        assertEquals(odd, BedrockItems.POTION.variant(9999));
        assertFalse(BedrockItems.POTION.hasVariant(9999));
    }

    @Test
    void aGetterIsTheVariantOfItsType() {
        BedrockItemVariant longNightVision = BedrockItems.POTION.withEffect(BedrockPotionTypes.LONG_NIGHTVISION);
        assertEquals(BedrockItems.POTION, longNightVision.type());
        assertEquals(6, longNightVision.aux());
        assertSame(BedrockItems.POTION.variant(6), longNightVision);
        assertSame(BedrockItems.BED.variant(8), BedrockItems.BED.silver());
        assertSame(BedrockItems.BED.variant(3), BedrockItems.BED.lightBlue());
        assertSame(BedrockItems.ARROW.variant(6), BedrockItems.ARROW.withEffect(BedrockPotionTypes.NIGHTVISION));
    }

    @Test
    void aTippedArrowHasNoWaterVariant() {
        assertThrows(IllegalArgumentException.class, () -> BedrockItems.ARROW.withEffect(BedrockPotionTypes.WATER));
    }

    @Test
    void everyGetterIsInTheNewestPalette() throws Exception {
        Paletter paletter = Paletter.bundled();
        List<String> missing = new ArrayList<>();
        int count = 0;
        for (BedrockItemType type : List.of(BedrockItems.BED, BedrockItems.BANNER)) {
            for (Method method : type.getClass().getMethods()) {
                if (method.getDeclaringClass() != type.getClass() || method.getParameterCount() != 0
                    || method.getReturnType() != BedrockItemVariant.class) {
                    continue;
                }
                BedrockItemVariant variant = (BedrockItemVariant) method.invoke(type);
                count++;
                // Deprecated and preview getters may be absent; everything else is the data's own variant
                boolean optional = method.isAnnotationPresent(Deprecated.class)
                    || method.isAnnotationPresent(dev.opencollab.paletter.annotation.SincePreview.class);
                boolean named = variant.type().hasVariant(variant.aux());
                if (!named && !optional) {
                    missing.add(type.identifier() + "." + method.getName());
                } else if (named) {
                    assertSame(variant, variant.type().variant(variant.aux()), method.getName());
                    assertTrue(variant.canonicalIn(Access.palette().canonicalItems(paletter)) >= 0, method.getName());
                }
            }
        }
        assertTrue(count > 0);
        assertEquals(List.of(), missing);
    }

    @Test
    void variantsOfOtherTypesAreNotNamed() {
        assertNotNull(BedrockItems.BANNER.black());
        assertFalse(BedrockItems.SUSPICIOUS_STEW.variants().isEmpty());
        // Types without named variants are plain types, with no getters
        assertSame(BedrockItemType.class, BedrockItems.SUSPICIOUS_STEW.getClass());
        assertSame(BedrockItemType.class, BedrockItems.GOAT_HORN.getClass());
    }
}
