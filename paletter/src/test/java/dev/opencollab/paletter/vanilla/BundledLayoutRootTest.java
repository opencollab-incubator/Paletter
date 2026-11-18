package dev.opencollab.paletter.vanilla;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BedrockBlockState#canonicalIn} checks a state's id (only states of the bundled constants have one) before its layout's
 * root. That order is right because no bundled constant has a root of its own: only the constants of a previews-on Paletter
 * do ({@link PreviewBlocks}), and those have no id.
 */
class BundledLayoutRootTest {
    @Test
    void noBundledConstantHasALayoutRoot() {
        assertTrue(BedrockBlockType.vanillaTypes().size() > 1000);
        for (BedrockBlockType type : BedrockBlockType.vanillaTypes()) {
            assertNull(type.layout().root, type.identifier());
        }
    }
}
