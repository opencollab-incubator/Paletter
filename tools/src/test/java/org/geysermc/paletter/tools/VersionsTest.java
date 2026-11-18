package org.geysermc.paletter.tools;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VersionsTest {
    @Test
    void numberByNumberAndShorterFirst() {
        List<String> versions = new ArrayList<>(List.of("1.26.70.23", "1.26.63.1", "1.26.9.0", "1.26.70", "1.21.130.4", "1.26.63.1"));
        versions.sort(Versions.ORDER);
        assertEquals(List.of("1.21.130.4", "1.26.9.0", "1.26.63.1", "1.26.63.1", "1.26.70", "1.26.70.23"), versions);
    }

    @Test
    void buildNumbersAreFourDottedNumbers() {
        assertEquals("1.26.63.1", Versions.requireBuild("1.26.63.1"));
        // Older builds have leading zeros in their names
        assertEquals("1.21.73.01", Versions.requireBuild("1.21.73.01"));
        assertThrows(IllegalArgumentException.class, () -> Versions.requireBuild("1.26.63.1234567890"));
        for (String bad : new String[] {"1.26.63", "1.26.63.1.2", "1.26.63.1\\..\\..\\x", "1.26.63.1/..", "../1.26.63.1", "1.26.63.a", " 1.26.63.1", ""}) {
            assertThrows(IllegalArgumentException.class, () -> Versions.requireBuild(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> Versions.requireBuild(null));
        assertEquals("1.26.30", Versions.requireBound("1.26.30"));
        assertEquals("1.26.30.5", Versions.requireBound("1.26.30.5"));
        assertThrows(IllegalArgumentException.class, () -> Versions.requireBound("1.26.30\\x"));
    }

    @Test
    void dashesSplitAndWordsCountAsZero() {
        assertEquals(-1, Integer.signum(Versions.compare("1.26.70-preview", "1.26.70.1")));
        assertEquals(0, Versions.compare("1.26.70-preview", "1.26.70.0"));
    }

    @Test
    void aPreviewReportsItsLastNumberAsBeta() {
        assertEquals("1.26.70-beta24", Versions.reported("1.26.70.24", true));
        assertEquals("1.26.63.1", Versions.reported("1.26.63.1", false));
        assertThrows(IllegalArgumentException.class, () -> Versions.reported("1.26.70-beta24", true));
    }
}
