package org.geysermc.paletter.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden files of the generator tests: the output is compared with the committed file, or, with
 * {@code ./gradlew :tools:test -PupdateGolden=true}, written over it (then review the diff). Paths are relative to the
 * tools module, the working directory of its tests.
 */
public final class Golden {
    /** Whether the tests rewrite their golden files instead of comparing. */
    public static final boolean UPDATE = Boolean.getBoolean("paletter.updateGolden");
    private static final String HINT = "if intended, run ./gradlew :tools:test -PupdateGolden=true and review the diff";

    private Golden() {
    }

    /** Compares (or writes) one golden file. */
    public static void assertFile(Path golden, String actual) throws IOException {
        if (UPDATE) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, actual, StandardCharsets.UTF_8);
        }
        assertTrue(Files.isRegularFile(golden), golden + " is missing; " + HINT);
        assertEquals(read(golden), actual, golden + " differs; " + HINT);
    }

    /**
     * Compares (or replaces) a directory of golden files: the set of files and each one's content.
     *
     * @param files the content of each file by its path relative to the directory, with {@code /}
     */
    public static void assertDirectory(Path golden, Map<String, String> files) throws IOException {
        if (UPDATE) {
            if (Files.isDirectory(golden)) {
                try (Stream<Path> walk = Files.walk(golden)) {
                    for (Path path : walk.sorted(Collections.reverseOrder()).toList()) {
                        Files.delete(path);
                    }
                }
            }
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path path = golden.resolve(file.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
            }
        }
        Set<String> committed = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(golden)) {
            walk.filter(Files::isRegularFile).forEach(path -> committed.add(golden.relativize(path).toString().replace('\\', '/')));
        }
        assertEquals(new TreeSet<>(files.keySet()), committed, "generated files vs " + golden + "; " + HINT);
        for (Map.Entry<String, String> file : files.entrySet()) {
            assertEquals(read(golden.resolve(file.getKey())), file.getValue(), file.getKey() + " differs from " + golden + "; " + HINT);
        }
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
