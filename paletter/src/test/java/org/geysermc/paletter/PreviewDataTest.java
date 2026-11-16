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

/**
 * A bundled preview ({@code <name> <protocol> preview} in {@code versions.txt}) is only served with the system property
 * {@code paletter.previews}: then its protocol gets the preview's palette, else the newest release's. When no preview is
 * bundled, the copy of the data marks the newest release as one.
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
        if (previews.isEmpty()) {
            previews.add(markNewestAsPreview(directory));
        }
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

    /** Appends {@code preview} to the line of the newest version in the copy's {@code versions.txt}, and returns its parts. */
    private static String[] markNewestAsPreview(Path directory) throws IOException {
        Path index = directory.resolve("versions.txt");
        List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
        int newest = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (!line.isEmpty() && !line.startsWith("#") && (newest < 0 || protocol(line) > protocol(lines.get(newest).strip()))) {
                newest = i;
            }
        }
        assertTrue(newest >= 0, "no version bundled");
        String line = lines.get(newest).strip() + " preview";
        lines.set(newest, line);
        Files.write(index, lines, StandardCharsets.UTF_8);
        return line.split("\\s+");
    }

    private static int protocol(String line) {
        return Integer.parseInt(line.split("\\s+")[1]);
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
