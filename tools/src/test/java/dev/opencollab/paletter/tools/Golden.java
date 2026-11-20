package dev.opencollab.paletter.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
