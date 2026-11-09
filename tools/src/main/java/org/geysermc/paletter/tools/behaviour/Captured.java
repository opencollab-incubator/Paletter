package org.geysermc.paletter.tools.behaviour;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code data/bds/CAPTURED.md}: the captures of BDS that carry their own evidence (the console they were measured from),
 * one line per directory. Written by the tool that made the capture, which replaces its own line when it runs again.
 */
final class Captured {
    static final String FILE = "data/bds/CAPTURED.md";
    private static final String HEADER = """
        # Captured BDS data

        Captures of Bedrock Dedicated Server that keep the evidence they were measured from. Written by the tool named in each
        line (never edit by hand); running it again replaces its line.

        | BDS | Directory | What | Command |
        |-----|-----------|------|---------|
        """;

    private Captured() {
    }

    static void record(Path root, String version, String build, String directory, String what, String command) throws IOException {
        Path file = root.resolve(FILE);
        List<String> lines = new ArrayList<>();
        if (Files.isRegularFile(file)) {
            lines.addAll(Files.readAllLines(file));
        } else {
            lines.addAll(HEADER.lines().toList());
        }
        String entry = "| " + version + " (Build ID " + build + ") | `" + directory + "` | " + what + " | `" + command.replace("|", "\\|") + "` |";
        lines.removeIf(line -> line.contains("| `" + directory + "` |"));
        lines.add(entry);
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n");
    }
}
