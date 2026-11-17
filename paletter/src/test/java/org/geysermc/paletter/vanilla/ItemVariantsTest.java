package org.geysermc.paletter.vanilla;

import org.geysermc.paletter.Paletter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
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

    @Test
    void referencingAConstantLoadsNoData() throws IOException, InterruptedException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-verbose:class", "-cp", System.getProperty("java.class.path"), Reference.class.getName())
            .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        assertTrue(output.contains("VARIANT 6"), output);
        // A fresh JVM that only reads a constant loads the constants classes, never the index or the data
        assertTrue(output.contains(BedrockItemVariants.class.getName()), "the constants class was not loaded");
        for (String data : List.of("VanillaIndex", "PaletteDataSource", "PaletteDataCodec", "VanillaData")) {
            assertFalse(output.contains("." + data + " source:"), data + " was loaded by referencing a constant");
        }
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
            BedrockItemVariant known = variant.type().variant(variant.aux());
            // Deprecated and preview constants may be absent; everything else is the data's own variant
            boolean optional = field.isAnnotationPresent(Deprecated.class)
                || field.isAnnotationPresent(org.geysermc.paletter.annotation.SincePreview.class);
            if (known == null && !optional) {
                missing.add(field.getName());
            } else if (known != null) {
                assertSame(variant, known, field.getName());
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
