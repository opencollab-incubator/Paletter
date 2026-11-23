package dev.opencollab.paletter.tools.codegen.component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The entry point of the component generator ({@code generate} and {@code check} of {@link Main}) on a temporary output
 * directory, from the committed data: writing, checking, counting a changed file once, deleting a file that carries the
 * generator's header and is no longer generated, and leaving a hand-written file alone. The constants generator has the
 * same in {@code ConstantsEntryPointTest}.
 */
class GeneratorEntryPointsTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));

    private static int changes(Path sources, boolean check) throws IOException {
        return Main.changes(ROOT, sources, check).size();
    }

    private static List<Path> javaFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test
    void componentApiWritesChecksAndDeletesStaleFiles(@TempDir Path sources) throws IOException {
        int written = changes(sources, false);
        assertTrue(written > 0);
        assertEquals(0, changes(sources, true), "generating then checking differs");
        for (Path file : javaFiles(sources)) {
            assertTrue(Files.readString(file, StandardCharsets.UTF_8).startsWith("// " + Main.GENERATOR), file + " lacks the generator header");
        }

        // A changed file counts once; check leaves it, generate rewrites it
        Path changed = javaFiles(sources).getFirst();
        String content = Files.readString(changed, StandardCharsets.UTF_8);
        Files.writeString(changed, "changed", StandardCharsets.UTF_8);
        assertEquals(1, changes(sources, true));
        assertEquals("changed", Files.readString(changed, StandardCharsets.UTF_8));
        assertEquals(1, changes(sources, false));
        assertEquals(content, Files.readString(changed, StandardCharsets.UTF_8).replace("\r\n", "\n"));

        // A file with the header that is no longer generated is stale and deleted; a hand-written one is not
        Path owned = sources.resolve(Main.OWNED.getFirst());
        Path dead = owned.resolve("Dead.java");
        Path hand = owned.resolve("Hand.java");
        Files.writeString(dead, "// " + Main.GENERATOR + "\npackage x;\n", StandardCharsets.UTF_8);
        Files.writeString(hand, "package x;\n", StandardCharsets.UTF_8);
        assertEquals(1, changes(sources, true));
        assertTrue(Files.exists(dead));
        assertEquals(1, changes(sources, false));
        assertFalse(Files.exists(dead));
        assertTrue(Files.exists(hand));
        assertEquals(0, changes(sources, true));
    }
}
