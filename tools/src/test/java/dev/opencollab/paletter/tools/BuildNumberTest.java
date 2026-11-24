package dev.opencollab.paletter.tools;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildNumberTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));

    @Test
    void aBuildIsFourDottedNumbersStartingWithOne() {
        assertEquals("1.26.63.1", BuildNumber.require("1.26.63.1"));
        // Older builds have leading zeros in their names
        assertEquals("1.21.73.01", BuildNumber.require("1.21.73.01"));
        assertEquals(new BuildNumber("1.21.73.01", 21, 73, 1), BuildNumber.parse("1.21.73.01"));
        for (String bad : new String[] {"1.26.63", "1.26.63.1.2", "2.26.63.1", "26.63.1.0", "1.26.63.1234567890", "1.26.63.1\\..\\..\\x",
            "1.26.63.1/..", "../1.26.63.1", "1.26.63.a", " 1.26.63.1", "1.26.70-beta24", ""}) {
            assertThrows(IllegalArgumentException.class, () -> BuildNumber.parse(bad), bad);
            assertFalse(BuildNumber.isBuild(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> BuildNumber.parse(null));
        assertFalse(BuildNumber.isBuild(null));
    }

    @Test
    void theDataVersionRoundsThePatchDownToTens() {
        assertEquals("26.70", BuildNumber.parse("1.26.70.23").dataVersion());
        assertEquals("26.60", BuildNumber.parse("1.26.63.1").dataVersion());
        assertEquals("26.40", BuildNumber.parse("1.26.45.1").dataVersion());
        assertEquals("21.130", BuildNumber.parse("1.21.131.1").dataVersion());
        assertTrue(BuildNumber.parse("1.26.65.1").inLine("26.60"));
        assertFalse(BuildNumber.parse("1.26.70.1").inLine("26.60"));
        assertFalse(BuildNumber.parse("1.25.63.1").inLine("26.60"));
        assertFalse(BuildNumber.parse("1.26.63.1").inLine("1.26.60"));
    }

    @Test
    void aPreviewReportsItsRevisionAsBeta() {
        assertEquals("1.26.70-beta24", BuildNumber.parse("1.26.70.24").reported(true));
        assertEquals("1.26.63.1", BuildNumber.parse("1.26.63.1").reported(false));
        assertEquals("1.26.70.24", BuildNumber.parseReported("1.26.70-beta24").text());
        assertEquals("1.26.63.1", BuildNumber.parseReported("1.26.63.1").text());
        assertTrue(BuildNumber.isReported("1.26.70-beta24"));
        assertFalse(BuildNumber.isReported("1.26.70-rc1"));
        assertFalse(BuildNumber.isReported("unknown"));
        assertThrows(IllegalArgumentException.class, () -> BuildNumber.parseReported("1.26.70-rc1"));
    }

    /** The regex agrees with the parser, and the workflows check builds with exactly it. */
    @Test
    void theWorkflowsUseTheSameFormat() throws IOException {
        Pattern regex = Pattern.compile(BuildNumber.REGEX);
        for (String text : List.of("1.26.63.1", "1.21.73.01", "1.26.63", "2.26.63.1", "1.26.63.1.2", "1.26.70-beta24")) {
            assertEquals(BuildNumber.isBuild(text), regex.matcher(text).matches(), text);
        }
        for (String workflow : List.of("workflows/recapture.yml", "workflows/bds-data.yml", "actions/setup-tools/action.yml")) {
            String text = Files.readString(ROOT.resolve(".github").resolve(workflow));
            Matcher m = Pattern.compile("(?m)^\\s*BUILD_RE: '([^']*)'$").matcher(text);
            assertTrue(m.find(), workflow + " defines no BUILD_RE");
            assertEquals(BuildNumber.REGEX, m.group(1), workflow);
            assertFalse(text.contains("[0-9]{1,9}(\\.[0-9]{1,9}){3}") || text.contains("[0-9]+(\\.[0-9]+)+"),
                workflow + " checks a build with its own regex instead of BUILD_RE");
        }
    }
}
