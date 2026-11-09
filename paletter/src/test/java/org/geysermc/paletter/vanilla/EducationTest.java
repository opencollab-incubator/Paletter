package org.geysermc.paletter.vanilla;

import org.geysermc.paletter.annotation.Education;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code @Education} on the generated constants and {@code isEducation()} at runtime say the same, and come from BDS data. */
class EducationTest {

    @Test
    void theAnnotationAndTheQueryAgreeForEveryConstant() throws Exception {
        int items = 0;
        int blocks = 0;
        for (Field field : BedrockItems.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == BedrockItemType.class) {
                BedrockItemType type = (BedrockItemType) field.get(null);
                assertEquals(field.isAnnotationPresent(Education.class), type.isEducation(), field.getName());
                items += type.isEducation() ? 1 : 0;
            }
        }
        for (Field field : BedrockBlocks.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == BedrockBlockType.class) {
                BedrockBlockType type = (BedrockBlockType) field.get(null);
                assertEquals(field.isAnnotationPresent(Education.class), type.isEducation(), field.getName());
                blocks += type.isEducation() ? 1 : 0;
            }
        }
        // What BDS adds to the creative inventory of an Education Edition world (data/bds/*/education.json)
        assertEquals(172, items);
        assertEquals(164, blocks);
    }

    @Test
    void knownEducationContent() {
        assertTrue(BedrockItems.BALLOON.isEducation());
        assertTrue(BedrockItems.ELEMENT_1.isEducation());
        assertTrue(BedrockItems.HARD_GLASS.isEducation());
        assertTrue(BedrockBlocks.ELEMENT_1.isEducation());
        assertTrue(BedrockBlocks.COLORED_TORCH_RED.isEducation());
        assertFalse(BedrockItems.APPLE.isEducation());
        assertFalse(BedrockBlocks.STONE.isEducation());
        // Keys that are not constants never are
        assertFalse(BedrockItemType.of("example:balloon").isEducation());
        assertFalse(BedrockBlockType.of("example:element_1").isEducation());
    }
}
