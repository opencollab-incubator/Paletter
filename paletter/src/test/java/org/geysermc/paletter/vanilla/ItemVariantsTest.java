package org.geysermc.paletter.vanilla;

import org.geysermc.paletter.Paletter;
import org.junit.jupiter.api.BeforeAll;
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

    @BeforeAll
    static void dataLoaded() {
        // Identity of named variants holds once the data is linked; the load-nothing tests run in a fresh JVM
        VanillaIndex.get();
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
        org.geysermc.paletter.Palette palette = paletter.palette(paletter.latest().protocolVersion());
        assertSame(palette.resolve(BedrockItems.POTION.variant()), palette.resolve(BedrockItems.POTION));
        assertEquals(org.geysermc.paletter.item.BedrockItemStack.of(BedrockItems.POTION.variant()), org.geysermc.paletter.item.BedrockItemStack.of(BedrockItems.POTION));
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
                || field.isAnnotationPresent(org.geysermc.paletter.annotation.SincePreview.class);
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
