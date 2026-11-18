package dev.opencollab.paletter.tools.codegen.component;

import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.data.DataVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The build gate of the per protocol component encoding: every bundled release has a spec capture of a BDS release of its
 * own, so the shape of its custom item and block components was observed, not extrapolated from another version. A capture
 * of a preview build stands for no release.
 */
class BundledVersionsCapturedTest {
    @Test
    void everyBundledVersionHasASpecCapture() throws IOException {
        Paletter paletter = Paletter.bundled();
        List<DataVersion> captured = new ArrayList<>();
        for (Path capture : ComponentConformanceTest.captures()) {
            if (!ComponentConformanceTest.isPreview(capture)) {
                captured.add(ComponentConformanceTest.versionOf(ComponentConformanceTest.protocolOf(capture), false));
            }
        }
        List<String> missing = paletter.versions().stream().filter(version -> !captured.contains(version)).map(DataVersion::toString).toList();
        assertTrue(missing.isEmpty(), "no spec capture of a release (data/bds/<version>/spec, run spec-capture) for the bundled version(s) " + missing
            + "; captured: " + captured);
    }
}
