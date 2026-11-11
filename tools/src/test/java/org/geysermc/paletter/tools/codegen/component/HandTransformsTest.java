package org.geysermc.paletter.tools.codegen.component;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The table of hand transforms names methods the runtime has, and a key covers the fields below it. */
class HandTransformsTest {

    @Test
    void everyTransformIsAPublicStaticMethodOfTransforms() throws ClassNotFoundException {
        Class<?> transforms = Class.forName("org.geysermc.paletter.internal.component.Transforms");
        for (Map.Entry<String, String> e : HandTransforms.BY_PATH.entrySet()) {
            boolean exists = Arrays.stream(transforms.getMethods()).filter(m -> m.getName().equals(e.getValue()))
                .map(Method::getModifiers).anyMatch(Modifier::isStatic);
            assertTrue(exists, e.getKey() + " names Transforms." + e.getValue() + ", which is not a public static method");
        }
    }

    @Test
    void aKeyCoversItsPathAndEverythingBelow() {
        Map<String, String> table = Map.of("c:a", "x", "c:[]", "y", "d:", "z");
        assertEquals("c:a", HandTransforms.covering(table, "c", "a"));
        assertEquals("c:a", HandTransforms.covering(table, "c", "a.b[].c"));
        assertEquals("c:[]", HandTransforms.covering(table, "c", "[].size"));
        assertEquals("d:", HandTransforms.covering(table, "d", "anything"));
        assertEquals("d:", HandTransforms.covering(table, "d", ""));
        assertNull(HandTransforms.covering(table, "c", "b"));
        assertNull(HandTransforms.covering(table, "c", ""));
    }
}
