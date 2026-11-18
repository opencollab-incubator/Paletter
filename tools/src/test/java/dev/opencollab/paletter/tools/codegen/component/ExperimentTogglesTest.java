package dev.opencollab.paletter.tools.codegen.component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExperimentTogglesTest {
    private static Path data(Path root) throws IOException {
        Path data = Files.createDirectories(root.resolve("data"));
        Files.writeString(data.resolve("versions.txt"), "# comment\n1.0 100\n1.1 110\n1.2 120 preview\n");
        return data;
    }

    private static void capture(Path bds, String build, int protocol, String toggles) throws IOException {
        Files.writeString(Files.createDirectories(bds.resolve(build)).resolve("experiments.json"),
            "{\"protocol\": " + protocol + ", \"toggles\": " + toggles + "}");
    }

    @Test
    void aToggleIsSinceTheFirstCaptureThatHasItCurrentAndUntilTheFirstReleaseThatDoesNot(@TempDir Path root) throws IOException {
        Path bds = root.resolve("bds");
        capture(bds, "1.0.1", 101, "{\"never\": {\"status\": \"retired\"}, \"drop\": {\"status\": \"current\", \"name\": \"Drop\"}, \"kept\": {\"status\": \"current\"}}");
        capture(bds, "1.1.1", 111, "{\"drop\": {\"status\": \"retired\"}, \"kept\": {\"status\": \"current\"}, \"late\": {\"status\": \"current\"}}");
        assertEquals(List.of(new ExperimentToggles.Toggle("drop", "Drop", "1.0", false, "1.1"),
            new ExperimentToggles.Toggle("kept", null, "1.0", false, null),
            new ExperimentToggles.Toggle("late", null, "1.1", false, null)), ExperimentToggles.read(data(root), bds), "a toggle never current has no constant");
    }

    @Test
    void aPreviewHasItsOwnToggleButEndsNothing(@TempDir Path root) throws IOException {
        Path bds = root.resolve("bds");
        capture(bds, "1.1.1", 111, "{\"old\": {\"status\": \"current\"}}");
        capture(bds, "1.2.1", 121, "{\"old\": {\"status\": \"retired\"}, \"next\": {\"status\": \"current\"}}");
        assertEquals(List.of(new ExperimentToggles.Toggle("next", null, "1.2", true, null), new ExperimentToggles.Toggle("old", null, "1.1", false, null)),
            ExperimentToggles.read(data(root), bds));
    }

    @Test
    void aToggleThatIsCurrentAgainIsOpenAgain(@TempDir Path root) throws IOException {
        Path bds = root.resolve("bds");
        capture(bds, "1.0.1", 101, "{\"x\": {\"status\": \"current\"}}");
        capture(bds, "1.1.1", 111, "{\"x\": {\"status\": \"retired\"}}");
        capture(bds, "1.1.2", 112, "{\"x\": {\"status\": \"current\"}}");
        ExperimentToggles.Toggle x = ExperimentToggles.read(data(root), bds).getFirst();
        assertEquals("1.0", x.since());
        assertNull(x.until());
    }

    @Test
    void namesMapTheDocsExperimentNameToTheId(@TempDir Path root) throws IOException {
        Path bds = root.resolve("bds");
        capture(bds, "1.0.1", 101, "{\"upcoming\": {\"status\": \"current\", \"name\": \"Upcoming Creator Features\"}, \"gone\": {\"status\": \"retired\"}}");
        assertEquals(Map.of("Upcoming Creator Features", "upcoming"), ExperimentToggles.names(ExperimentToggles.read(data(root), bds)));
    }

    @Test
    void aNameOfTwoIdsIsAnError() {
        assertThrows(IllegalStateException.class, () -> ExperimentToggles.names(List.of(
            new ExperimentToggles.Toggle("a", "Same", "1.0", false, null), new ExperimentToggles.Toggle("b", "Same", "1.0", false, null))));
    }
}
