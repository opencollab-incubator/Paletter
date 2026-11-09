package org.geysermc.paletter.tools.codegen.component;

import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.data.DataVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The build gate of the per protocol component encoding: every bundled data version has a spec capture of BDS, so the
 * shape of its custom item and block components was observed, not extrapolated from another version.
 */
class BundledVersionsCapturedTest {
    @Test
    void everyBundledVersionHasASpecCapture() throws IOException {
        Paletter paletter = Paletter.bundled();
        List<Integer> captured = new ArrayList<>();
        for (Path capture : ComponentConformanceTest.captures()) {
            captured.add(ComponentConformanceTest.protocolOf(capture));
        }
        List<String> missing = new ArrayList<>();
        for (DataVersion version : paletter.versions()) {
            boolean has = captured.stream().anyMatch(protocol -> paletter.version(protocol).equals(version));
            if (!has) {
                missing.add(version.toString());
            }
        }
        assertTrue(missing.isEmpty(), "no spec capture (data/bds/<version>/spec, run spec-capture) for the bundled version(s) " + missing
            + "; captured protocols: " + captured);
    }
}
