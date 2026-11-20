package dev.opencollab.paletter.tools;

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
    void boundsAreOneToFourDottedNumbers() {
        assertEquals("1.26.30", Versions.requireBound("1.26.30"));
        assertEquals("1.26.30.5", Versions.requireBound("1.26.30.5"));
        assertThrows(IllegalArgumentException.class, () -> Versions.requireBound("1.26.30\\x"));
    }

    @Test
    void dashesSplitAndWordsCountAsZero() {
        assertEquals(-1, Integer.signum(Versions.compare("1.26.70-preview", "1.26.70.1")));
        assertEquals(0, Versions.compare("1.26.70-preview", "1.26.70.0"));
    }
}
