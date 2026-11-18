package dev.opencollab.paletter.tools.bds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapturePlatformTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));

    @Test
    void committedCapturesComeFromWindows() throws IOException {
        assertEquals(CapturePlatform.WINDOWS, CapturePlatform.single(ROOT.resolve("data/bds")));
    }

    @Test
    void mixedPlatformsAreRefused(@TempDir Path bds) throws IOException {
        capture(bds.resolve("1.0.0.1/vanilla"), "windows");
        capture(bds.resolve("1.0.1.1/vanilla"), "linux");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CapturePlatform.single(bds));
        assertTrue(e.getMessage().contains("1.0.1.1/vanilla"), e.getMessage());
    }

    @Test
    void onlyTheSamePlatformCompares() {
        assertNull(CapturePlatform.mismatch("x", "windows", "windows"));
        assertTrue(CapturePlatform.mismatch("x", "windows", "linux").contains("not compared"));
    }

    private static void capture(Path directory, String platform) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("capture.json"), "{\"bdsVersion\": \"1\", \"platform\": \"" + platform + "\"}");
    }
}
