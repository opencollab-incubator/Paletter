package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator.Bundle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Which bundled palette a capture resolves block hashes with. */
class CaptureReferenceTest {
    private static final List<Bundle> BUNDLES = List.of(new Bundle("26.50", 2193, false), new Bundle("26.60", 2225, false), new Bundle("26.70", 2259, true));

    @Test
    void aBundledPreviewOfTheProtocolKnowsItsOwnStates() throws IOException {
        // Blocks that changed in the preview (scaffolding got a property) hash differently from the release before
        assertEquals("26.70", EducationProbe.reference(BUNDLES, 2259).name());
    }

    @Test
    void elseTheNewestReleaseBelow() throws IOException {
        assertEquals("26.60", EducationProbe.reference(BUNDLES, 2250).name());
        assertEquals("26.60", EducationProbe.reference(BUNDLES, 2300).name());
        assertThrows(IOException.class, () -> EducationProbe.reference(BUNDLES, 100));
    }
}
