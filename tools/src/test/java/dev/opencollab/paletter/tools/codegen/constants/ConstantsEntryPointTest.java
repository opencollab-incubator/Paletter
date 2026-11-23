package dev.opencollab.paletter.tools.codegen.constants;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code generate} and {@code check} of {@link Main} on a temporary root, from the committed data: writing, checking, an
 * outdated file, deleting an enum that carries the generator's header and is no longer generated, and leaving a
 * hand-written file alone. The sources are written and compared by {@code GeneratedSources}, which has its own test.
 */
class ConstantsEntryPointTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));

    @Test
    void constantsWriteCheckAndDeleteStaleFiles(@TempDir Path root) throws IOException {
        ConstantsGenerator.Result result = ConstantsGenerator.generate(ROOT);
        assertEquals(0, Main.run(root, result, false));
        assertEquals(0, Main.run(root, result, true));
        for (String file : result.files().keySet()) {
            assertTrue(Files.exists(root.resolve(file)), file);
        }

        // A changed file fails the check, and the check leaves it
        Path changed = root.resolve(ConstantsGenerator.PACKAGE_DIR + "/BedrockBlocks.java");
        Files.writeString(changed, "changed", StandardCharsets.UTF_8);
        assertEquals(1, Main.run(root, result, true));
        assertEquals("changed", Files.readString(changed, StandardCharsets.UTF_8));
        assertEquals(0, Main.run(root, result, false));
        assertEquals(0, Main.run(root, result, true));

        // An enum with the header that is no longer generated is stale and deleted; a hand-written file is not
        Path dead = root.resolve(ConstantsGenerator.STATE_DIR + "/Dead.java");
        Path hand = root.resolve(ConstantsGenerator.STATE_DIR + "/Hand.java");
        Files.writeString(dead, ConstantsSource.GENERATOR + " from the bundled data and\npackage x;\n", StandardCharsets.UTF_8);
        Files.writeString(hand, "package x;\n", StandardCharsets.UTF_8);
        assertEquals(List.of(dead), Main.stale(root, result.files()));
        assertEquals(1, Main.run(root, result, true));
        assertEquals(0, Main.run(root, result, false));
        assertFalse(Files.exists(dead));
        assertTrue(Files.exists(hand));
    }
}
