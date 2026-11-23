package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.internal.behaviour.vanilla.BehaviourData;
import dev.opencollab.paletter.tools.codegen.ClassSize;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated behaviour classes stay well inside the JVM's limits, so data that grows fails here and not when the classes
 * load: at most half of the 64 KB of bytecode per method, and half of the 65,535 constant pool entries per class
 * ({@link ClassSize}). Read from the compiled classes.
 */
class GeneratedBehaviourLimitsTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));

    @Test
    void everyGeneratedClassIsWellInsideTheLimits() throws IOException {
        List<String> classes;
        try (Stream<Path> files = Files.list(ROOT.resolve(BehaviourCode.DIRECTORY))) {
            classes = files.map(f -> f.getFileName().toString()).filter(f -> f.endsWith(".java")).map(f -> f.replace(".java", "")).sorted().toList();
        }
        assertTrue(classes.size() > 10, classes.toString());
        assertEquals(List.of(), ClassSize.over(BehaviourData.class, classes), "lower Chunks.METHOD_STATEMENTS / METHOD_CHARS or BehaviourConstants.WEIGHT");
    }
}
