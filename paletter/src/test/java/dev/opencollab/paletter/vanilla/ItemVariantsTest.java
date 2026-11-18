package dev.opencollab.paletter.vanilla;

import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.internal.CanonicalItems;
import dev.opencollab.paletter.internal.item.ItemVariant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The named variant constants of {@link BedrockItemVariants}. */
class ItemVariantsTest {
    /** What a fresh JVM does with a constant: the main of {@link #referencingAConstantLoadsNoData()}. */
    public static final class Reference {
        public static void main(String[] args) {
            System.out.println("VARIANT " + BedrockItemVariants.POTION_LONG_NIGHTVISION.aux());
        }
    }

    private static final List<String> DATA = List.of("VanillaIndex", "PaletteDataSource", "PaletteDataCodec", "VanillaData");

    /** What a fresh JVM does with item keys: the main of {@link #usingItemKeysLoadsNoData}. */
    public static final class KeyReference {
        public static void main(String[] args) {
            ItemKey type = BedrockItems.POTION;
            ItemKey base = BedrockItems.POTION.variant();
            ItemKey damaged = BedrockItems.POTION.variant(7);
            ItemKey custom = BedrockItemType.custom("example:ruby").variant(3);
            System.out.println("KEYS " + type.variant().aux() + base.variant().aux() + damaged.variant().aux() + custom.variant().aux());
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
        assertTrue(output.contains(BedrockItemVariants.class.getName()), "the constants class was not loaded");
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
            BedrockItemVariant bed = BedrockItems.BED.variant();
            BedrockItemVariant potion = BedrockItems.POTION.variant(19);
            BedrockItemVariant preview = BedrockItems.BRITTLE_ICE.variant();
            BedrockItemVariant previewDamaged = BedrockItems.BRITTLE_ICE.variant(3);

            Paletter paletter = Paletter.bundled();
            dev.opencollab.paletter.Palette palette = paletter.palette(paletter.latest().protocolVersion());
            check(bed == BedrockItemVariants.BED_WHITE, "BED.variant() is BED_WHITE");
            check(bed == BedrockItems.BED.variant(), "BED.variant() is stable");
            check(potion == BedrockItemVariants.POTION_WATER_BREATHING, "POTION.variant(19) is the named constant");
            check(potion == BedrockItems.POTION.variant(19), "POTION.variant(19) is stable");
            check(preview == BedrockItems.BRITTLE_ICE.variant(), "an absent type's base is stable");
            check(previewDamaged.type() == BedrockItems.BRITTLE_ICE, "variants are of their own type, not the fallback's");
            check(palette.resolve(bed).equals(palette.resolve(BedrockItems.BED)), "bed resolves like its type");
            check(palette.resolve(potion).equals(palette.resolve(BedrockItems.POTION.variant(19))), "potion resolves like after loading");
            check(palette.resolve(preview).equals(palette.resolve(BedrockItems.BRITTLE_ICE)), "absent type resolves like its type");
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
        assertEquals(dev.opencollab.paletter.item.ItemFallback.of(bed), dev.opencollab.paletter.item.ItemFallback.of(bed.variant()));
        var white = dev.opencollab.paletter.item.BedrockItemStack.of(bed);
        var silver = dev.opencollab.paletter.item.BedrockItemStack.of(bed.variant(8));
        assertTrue(dev.opencollab.paletter.creative.ItemMatcher.of(bed).matches(white));
        assertFalse(dev.opencollab.paletter.creative.ItemMatcher.of(bed).matches(silver));
        assertTrue(dev.opencollab.paletter.creative.ItemMatcher.of(bed.variant()).matches(white));
        assertFalse(dev.opencollab.paletter.creative.ItemMatcher.of(bed.variant()).matches(silver));
        assertTrue(dev.opencollab.paletter.creative.ItemMatcher.anyVariantOf(bed).matches(white));
        assertTrue(dev.opencollab.paletter.creative.ItemMatcher.anyVariantOf(bed).matches(silver));
    }

    @Test
    void aVariantWithoutAnIdResolvesToTheRightId() {
        CanonicalItems canonical = Paletter.bundled().canonicalItems();
        // Named constants and the base have no id of their own: the type's link knows it
        assertEquals(canonical.index(ItemVariant.of("minecraft:potion", 19)), BedrockItemVariants.POTION_WATER_BREATHING.canonicalIn(canonical));
        assertEquals(canonical.index(ItemVariant.of("minecraft:bed", 0)), BedrockItems.BED.variant().canonicalIn(canonical));
        assertEquals(canonical.index(ItemVariant.of("minecraft:apple", 0)), BedrockItems.APPLE.variant().canonicalIn(canonical));
        // A variant the data does not have has none
        assertEquals(-1, BedrockItems.POTION.variant(9999).canonicalIn(canonical));
        // An absent type's variants have no id where the data lacks the item: they resolve through the fallbacks
        BedrockItemType absent = BedrockItems.BRITTLE_ICE;
        assertFalse(absent.isAvailable());
        assertEquals(-1, absent.variant().canonicalIn(canonical));
        assertEquals(absent, absent.variant(3).type());
    }

    @Test
    void usingItemKeysLoadsNoData(@TempDir Path temp) throws IOException, InterruptedException {
        String output = run(temp, KeyReference.class);
        assertTrue(output.contains("KEYS 0073"), output);
        for (String data : DATA) {
            assertFalse(output.contains("." + data + " source:"), data + " was loaded by using an item key");
        }
    }

    @Test
    void aTypeIsItsBaseVariant() {
        assertSame(BedrockItems.POTION.variant(), BedrockItems.POTION.variant().variant());
        assertEquals(BedrockItems.POTION, BedrockItems.POTION.variant().type());
        assertSame(BedrockItems.POTION.variant(), BedrockItems.POTION.variant(0));
        Paletter paletter = Paletter.bundled();
        dev.opencollab.paletter.Palette palette = paletter.palette(paletter.latest().protocolVersion());
        assertSame(palette.resolve(BedrockItems.POTION.variant()), palette.resolve(BedrockItems.POTION));
        assertEquals(dev.opencollab.paletter.item.BedrockItemStack.of(BedrockItems.POTION.variant()), dev.opencollab.paletter.item.BedrockItemStack.of(BedrockItems.POTION));
    }

    @Test
    void anUnnamedAuxIsAVariantNotNull() {
        BedrockItemVariant odd = BedrockItems.POTION.variant(9999);
        assertEquals(9999, odd.aux());
        assertEquals(odd, BedrockItems.POTION.variant(9999));
        assertFalse(BedrockItems.POTION.hasVariant(9999));
    }

    @Test
    void aConstantIsTheVariantOfItsType() {
        assertEquals(BedrockItems.POTION, BedrockItemVariants.POTION_LONG_NIGHTVISION.type());
        assertEquals(6, BedrockItemVariants.POTION_LONG_NIGHTVISION.aux());
        assertSame(BedrockItems.POTION.variant(6), BedrockItemVariants.POTION_LONG_NIGHTVISION);
        assertSame(BedrockItems.BED.variant(8), BedrockItemVariants.BED_SILVER);
        assertSame(BedrockItems.ARROW.variant(6), BedrockItemVariants.ARROW_NIGHTVISION);
    }

    @Test
    void everyConstantIsInTheNewestPalette() throws IllegalAccessException {
        Paletter paletter = Paletter.bundled();
        List<String> missing = new ArrayList<>();
        int count = 0;
        for (Field field : BedrockItemVariants.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !(field.get(null) instanceof BedrockItemVariant variant)) {
                continue;
            }
            count++;
            // Deprecated and preview constants may be absent; everything else is the data's own variant
            boolean optional = field.isAnnotationPresent(Deprecated.class)
                || field.isAnnotationPresent(dev.opencollab.paletter.annotation.SincePreview.class);
            boolean named = variant.type().hasVariant(variant.aux());
            if (!named && !optional) {
                missing.add(field.getName());
            } else if (named) {
                assertSame(variant, variant.type().variant(variant.aux()), field.getName());
                assertTrue(variant.canonicalIn(paletter.canonicalItems()) >= 0, field.getName());
            }
        }
        assertTrue(count > 0);
        assertEquals(List.of(), missing);
    }

    @Test
    void variantsOfOtherTypesAreNotNamed() {
        assertNotNull(BedrockItemVariants.BANNER_BLACK);
        assertFalse(BedrockItems.SUSPICIOUS_STEW.variants().isEmpty());
        assertEquals(0, java.util.Arrays.stream(BedrockItemVariants.class.getFields())
            .filter(field -> field.getName().startsWith("SUSPICIOUS_STEW") || field.getName().startsWith("GOAT_HORN")).count());
    }
}
