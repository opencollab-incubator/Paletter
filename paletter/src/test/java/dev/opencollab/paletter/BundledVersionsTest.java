package dev.opencollab.paletter;

import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.data.PaletteDataSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The static version lookups answer from the bundled data's release versions, as {@link Paletter#bundled()} would. */
class BundledVersionsTest {
    private static List<DataVersion> releases() {
        return PaletteDataSource.bundled().versions().stream().filter(version -> !version.isPreview()).toList();
    }

    @Test
    void theBundledVersionsAreTheReleaseVersionsOfTheBundledData() {
        assertEquals(releases(), Paletter.bundledVersions());
    }

    @Test
    void aProtocolFindsTheNewestVersionNotNewerThanIt() {
        List<DataVersion> releases = releases();
        DataVersion oldest = releases.getFirst();
        DataVersion newest = releases.getLast();
        assertEquals(oldest, Paletter.bundledVersion(oldest.protocolVersion()));
        assertEquals(newest, Paletter.bundledVersion(newest.protocolVersion()));
        assertEquals(newest, Paletter.bundledVersion(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> Paletter.bundledVersion(oldest.protocolVersion() - 1));
    }
}
