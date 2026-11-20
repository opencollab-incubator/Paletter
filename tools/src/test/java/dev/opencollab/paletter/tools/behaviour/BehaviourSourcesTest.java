package dev.opencollab.paletter.tools.behaviour;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pins of the third-party behaviour dumps live in data/behaviour/sources.json only; the import and the workflow both read it. */
class BehaviourSourcesTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));

    @Test
    void theImportAndTheRecaptureWorkflowReadTheSamePins() throws IOException {
        Map<String, BehaviourSources.Source> pins = BehaviourSources.read(ROOT);
        assertEquals(List.of(BehaviourSources.CLOUDBURST_DATA, BehaviourSources.ALLAY), List.copyOf(pins.keySet()));
        String workflow = Files.readString(ROOT.resolve(".github/workflows/recapture.yml"));
        // The workflow fetches each source by its name in sources.json, and writes no commit of its own
        assertTrue(workflow.contains("data/behaviour/sources.json"), "recapture.yml does not read " + BehaviourSources.FILE);
        for (String name : pins.keySet()) {
            assertTrue(workflow.contains("fetch " + name + " "), "recapture.yml does not fetch " + name + " from the pins");
        }
        Matcher hash = Pattern.compile("\\b[0-9a-f]{40}\\b").matcher(workflow);
        while (hash.find()) {
            String line = workflow.substring(workflow.lastIndexOf('\n', hash.start()) + 1, workflow.indexOf('\n', hash.end()));
            // Action pins (uses: owner/action@sha) are the only full hashes the workflow may write
            assertTrue(line.contains("uses: "), "a commit hash outside sources.json: " + line.strip());
        }
    }

    @Test
    void aCheckoutAtAnotherCommitFails() {
        BehaviourSources.Source pin = new BehaviourSources.Source("CloudburstMC/Data", "https://github.com/CloudburstMC/Data.git", "a".repeat(40));
        assertDoesNotThrow(() -> BehaviourSources.requirePinned(pin, "a".repeat(40)));
        IOException e = assertThrows(IOException.class, () -> BehaviourSources.requirePinned(pin, "b".repeat(40)));
        assertTrue(e.getMessage().contains("b".repeat(40)) && e.getMessage().contains("a".repeat(40)), e.getMessage());
        assertFalse(e.getMessage().isBlank());
    }
}
