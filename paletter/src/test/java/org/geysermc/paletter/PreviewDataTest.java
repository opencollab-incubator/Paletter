package org.geysermc.paletter;

import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A bundled preview ({@code <name> <protocol> preview} in {@code versions.txt}) is only served with the system property
 * {@code paletter.previews}: then its protocol gets the preview's palette, else the newest release's.
 */
class PreviewDataTest {
    private static final String PROPERTY = "paletter.previews";

    @Test
    void previewIsServedOnlyOnRequest(@TempDir Path directory) throws IOException {
        List<String[]> previews = new ArrayList<>();
        for (String line : lines(directory)) {
            String[] parts = line.split("\\s+");
            if (parts.length > 2 && parts[2].equals("preview")) {
                previews.add(parts);
            }
        }
        assumeTrue(!previews.isEmpty(), "no preview bundled");
        String before = System.getProperty(PROPERTY);
        try {
            for (String[] preview : previews) {
                DataVersion version = new DataVersion(preview[0], Integer.parseInt(preview[1]));
                System.clearProperty(PROPERTY);
                Paletter releases = Paletter.builder().data(PaletteDataSource.directory(directory)).build();
                assertFalse(releases.versions().contains(version), "served without " + PROPERTY + ": " + version);
                assertTrue(releases.palette(version.protocolVersion()).version().protocolVersion() < version.protocolVersion());

                System.setProperty(PROPERTY, "true");
                Paletter withPreviews = Paletter.builder().data(PaletteDataSource.directory(directory)).build();
                assertTrue(withPreviews.versions().contains(version), "not served with " + PROPERTY + ": " + version);
                assertEquals(version, withPreviews.palette(version.protocolVersion()).version());
                assertEquals(version, withPreviews.latest());
            }
        } finally {
            if (before == null) {
                System.clearProperty(PROPERTY);
            } else {
                System.setProperty(PROPERTY, before);
            }
        }
    }

    /** Copies the bundled files into the directory and returns the lines of {@code versions.txt}. */
    private static List<String> lines(Path directory) throws IOException {
        List<String> lines = new ArrayList<>();
        try (InputStream stream = PreviewDataTest.class.getResourceAsStream("/paletter/versions.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    lines.add(line.strip());
                }
            }
        }
        List<String> files = new ArrayList<>(List.of("versions.txt", "properties.nbt", "behaviour.nbt", "voxel_shapes.nbt", "renames.txt"));
        lines.forEach(line -> files.add(line.split("\\s+")[0] + ".nbt"));
        for (String file : files) {
            try (InputStream stream = PreviewDataTest.class.getResourceAsStream("/paletter/" + file)) {
                if (stream != null) {
                    Files.copy(stream, directory.resolve(file));
                }
            }
        }
        return lines;
    }
}
