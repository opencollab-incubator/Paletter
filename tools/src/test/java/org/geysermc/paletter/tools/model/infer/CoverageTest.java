package org.geysermc.paletter.tools.model.infer;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageTest {

    @Test
    void unreachedConstantsAreMembersOfTransforms() throws Exception {
        Class<?> transforms = Class.forName("org.geysermc.paletter.internal.component.Transforms");
        for (String name : Coverage.UNREACHED_CONSTANTS) {
            boolean exists = Arrays.stream(transforms.getDeclaredFields()).anyMatch(f -> f.getName().equals(name))
                || Arrays.stream(transforms.getDeclaredMethods()).anyMatch(m -> m.getName().equals(name));
            assertTrue(exists, "Coverage.UNREACHED_CONSTANTS names Transforms." + name + ", which does not exist");
        }
    }
}
