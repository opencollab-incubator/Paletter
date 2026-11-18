package dev.opencollab.paletter;

import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.data.PaletteDataSource;
import dev.opencollab.paletter.data.VerifiedBuild;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The release builds each bundled data version was verified against (generated {@code builds.txt}). */
class VerifiedBuildsTest {
    @Test
    void everyBundledReleaseVersionHasABuildThatServesIt() {
        PaletteDataSource bundled = PaletteDataSource.bundled();
        Paletter paletter = Paletter.bundled();
        for (DataVersion version : paletter.versions()) {
            List<VerifiedBuild> builds = paletter.verifiedBuilds(version);
            assertEquals(bundled.verifiedBuilds(version), builds);
            assertFalse(builds.isEmpty(), version + " has no verified build");
            for (VerifiedBuild build : builds) {
                // A verified build's protocol is served by the version it was verified for
                assertEquals(version, paletter.version(build.protocolVersion()), build + " of " + version);
                assertTrue(build.bdsVersion().matches("[0-9]+(\\.[0-9]+){3}"), build.bdsVersion());
            }
        }
    }

    @Test
    void aSourceWithoutTheListHasNoBuilds() {
        PaletteDataSource source = new PaletteDataSource() {
            @Override
            public List<DataVersion> versions() {
                return PaletteDataSource.bundled().versions();
            }

            @Override
            public dev.opencollab.paletter.internal.data.VanillaData load(DataVersion version) throws java.io.IOException {
                return PaletteDataSource.bundled().load(version);
            }
        };
        assertTrue(source.verifiedBuilds(Paletter.bundled().latest()).isEmpty());
    }
}
