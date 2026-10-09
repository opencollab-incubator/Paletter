package org.geysermc.paletter.test;

import org.geysermc.paletter.PaletterPalettes;

import org.geysermc.paletter.palette.PaletteVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class DownloadTest {
    @TempDir
    Path tempDirectory;

    @Test
    public void test() throws IOException {
        System.out.println(tempDirectory.toString());
        PaletterPalettes.init();
        PaletterPalettes.setCachePath(tempDirectory);
        PaletterPalettes.load(PaletteVersion.v26_50);

        Path expectedDirectory = tempDirectory.resolve("v%d".formatted(PaletteVersion.v26_50.protocolVersion()));
        System.out.println(expectedDirectory);
        assertTrue(Files.exists(expectedDirectory));
        assertTrue(Files.isDirectory(expectedDirectory));
    }
}
