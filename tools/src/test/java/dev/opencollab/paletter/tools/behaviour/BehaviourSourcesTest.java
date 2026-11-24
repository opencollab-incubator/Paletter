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
    void theCommittedPinsRead() throws IOException {
        Map<String, BehaviourSources.Source> pins = BehaviourSources.read(ROOT);
        assertEquals(List.of(BehaviourSources.CLOUDBURST_DATA, BehaviourSources.ALLAY), List.copyOf(pins.keySet()));
    }

    /** The recapture fetches exactly the pins, with argument arrays; the workflow writes no commit of its own. */
    @Test
    void theFetchClonesEachPinWithArgumentArrays(@org.junit.jupiter.api.io.TempDir Path root, @org.junit.jupiter.api.io.TempDir Path into)
        throws Exception {
        String cloudburst = "a".repeat(40);
        String allay = "b".repeat(40);
        Files.createDirectories(root.resolve("data/behaviour"));
        Files.writeString(root.resolve(BehaviourSources.FILE), "{\"sources\": {"
            + "\"CloudburstMC/Data\": {\"repository\": \"https://github.com/CloudburstMC/Data.git\", \"commit\": \"" + cloudburst + "\"},"
            + "\"AllayMC/Allay\": {\"repository\": \"https://github.com/AllayMC/Allay.git\", \"commit\": \"" + allay + "\"}}}");
        List<List<String>> commands = new java.util.ArrayList<>();
        Map<String, Path> checkouts = BehaviourSources.fetch(root, into, commands::add);
        Path cb = into.resolve("CloudburstMC-Data");
        Path al = into.resolve("AllayMC-Allay");
        assertEquals(Map.of("CloudburstMC/Data", cb, "AllayMC/Allay", al), checkouts);
        assertEquals(List.of(
            List.of("clone", "--quiet", "--filter=blob:none", "--no-checkout", "https://github.com/CloudburstMC/Data.git", cb.toString()),
            List.of("-C", cb.toString(), "update-ref", "HEAD", cloudburst),
            List.of("clone", "--quiet", "--filter=blob:none", "--no-checkout", "https://github.com/AllayMC/Allay.git", al.toString()),
            List.of("-C", al.toString(), "update-ref", "HEAD", allay)), commands);
        for (String workflow : List.of("recapture.yml", "bds-data.yml")) {
            Matcher hash = Pattern.compile("\\b[0-9a-f]{40}\\b").matcher(Files.readString(ROOT.resolve(".github/workflows").resolve(workflow)));
            String text = Files.readString(ROOT.resolve(".github/workflows").resolve(workflow));
            while (hash.find()) {
                String line = text.substring(text.lastIndexOf('\n', hash.start()) + 1, text.indexOf('\n', hash.end()));
                // Action pins (uses: owner/action@sha) are the only full hashes a workflow may write
                assertTrue(line.contains("uses: "), workflow + ": a commit hash outside sources.json: " + line.strip());
            }
        }
    }

    @Test
    void aSourceOutsideGitHubOrAShortCommitIsRefused(@org.junit.jupiter.api.io.TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("data/behaviour"));
        for (String pin : List.of("\"repository\": \"https://example.com/CloudburstMC/Data.git\", \"commit\": \"" + "a".repeat(40) + "\"",
            "\"repository\": \"https://github.com/CloudburstMC/Data.git\", \"commit\": \"abc123\"",
            "\"repository\": \"https://github.com/CloudburstMC/Data.git; rm -rf /\", \"commit\": \"" + "a".repeat(40) + "\"")) {
            Files.writeString(root.resolve(BehaviourSources.FILE), "{\"sources\": {\"CloudburstMC/Data\": {" + pin + "}}}");
            assertThrows(IOException.class, () -> BehaviourSources.fetch(root, root, arguments -> {
                throw new AssertionError("nothing is run for a bad pin");
            }), pin);
        }
    }

    @Test
    void aMissingPinIsAClearError(@org.junit.jupiter.api.io.TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("data/behaviour"));
        Files.writeString(root.resolve(BehaviourSources.FILE), "{\"sources\": {\"CloudburstMC/Data\": {\"repository\": "
            + "\"https://github.com/CloudburstMC/Data.git\", \"commit\": \"" + "a".repeat(40) + "\"}}}");
        IOException e = assertThrows(IOException.class, () -> BehaviourSources.read(root));
        assertTrue(e.getMessage().contains("no pin for " + BehaviourSources.ALLAY), e.getMessage());
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
