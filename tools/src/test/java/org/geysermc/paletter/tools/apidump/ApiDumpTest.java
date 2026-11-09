package org.geysermc.paletter.tools.apidump;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The API dump generator on a small synthetic class set ({@code src/test/resources/api-dump-fixture}: generics, varargs,
 * constants, nested classes, a sealed interface with a record, an enum with a constant body, an annotation type,
 * {@code @Internal} members, classes that are not public and packages that are not exported), compiled here and compared
 * with the golden dump {@code src/test/resources/api-dump-golden/fixture.api}.
 * <p>
 * After an intended change of the format: {@code ./gradlew :tools:test -PupdateGolden=true}, then review the diff.
 */
class ApiDumpTest {
    private static final Path FIXTURE = Path.of("src/test/resources/api-dump-fixture");
    private static final Path GOLDEN = Path.of("src/test/resources/api-dump-golden/fixture.api");
    private static final Set<String> EXPORTS = Set.of("fix.api", "org.geysermc.paletter.annotation");

    private static Map<String, byte[]> compile(Path output) throws IOException {
        List<String> arguments = new ArrayList<>(List.of("-d", output.toString(), "-parameters", "--release", "21"));
        try (Stream<Path> files = Files.walk(FIXTURE)) {
            files.filter(f -> f.toString().endsWith(".java")).sorted().forEach(f -> arguments.add(f.toString()));
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, arguments.toArray(String[]::new)), "the fixture compiles");
        return ApiDump.read(List.of(output));
    }

    @Test
    void theDumpOfTheFixtureMatchesTheGoldenFile(@TempDir Path temp) throws IOException {
        String dump = ApiDump.dump(compile(temp), EXPORTS);
        if (Boolean.getBoolean("paletter.updateGolden")) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, dump, StandardCharsets.UTF_8);
        }
        assertEquals(Files.readString(GOLDEN, StandardCharsets.UTF_8).replace("\r\n", "\n"), dump,
            "differs from the golden file; if intended, run with -PupdateGolden=true");
    }

    @Test
    void whatIsNotApiIsLeftOut(@TempDir Path temp) throws IOException {
        String dump = ApiDump.dump(compile(temp), EXPORTS);
        // Not exported, not public, @Internal, synthetic
        for (String left : List.of("fix.internal", "fix.api.sub", "Secret", "Plumbed", "hidden", "packagePrivate", "plumbing",
            "lambda$", "secret", "Hidden", "Private", "$VALUES", "bridge")) {
            assertFalse(dump.contains(left), left + " is not API:\n" + dump);
        }
        // Public, protected, constants, generics
        for (String listed : List.of("public static final int LIMIT = 4", "protected int level", "protected static final class fix.api.Widget$Guarded",
            "public static class fix.api.Widget$Nested", "public sealed interface fix.api.Shape permits fix.api.Circle, fix.api.Square",
            "@Deprecated public void old()", "@Experimental public java.lang.String[] experimental(int[][])")) {
            assertTrue(dump.contains(listed), listed + " is API:\n" + dump);
        }
    }

    @Test
    void theOrderDoesNotDependOnTheOrderOfTheClasses(@TempDir Path temp) throws IOException {
        Map<String, byte[]> classes = compile(temp);
        List<String> names = new ArrayList<>(classes.keySet());
        java.util.Collections.reverse(names);
        Map<String, byte[]> reversed = new java.util.LinkedHashMap<>();
        names.forEach(name -> reversed.put(name, classes.get(name)));
        assertEquals(ApiDump.dump(classes, EXPORTS), ApiDump.dump(reversed, EXPORTS));
    }

    @Test
    void exportsAreReadFromTheModuleDescriptor() {
        assertEquals(Set.of("a.b", "a.b.c"), ApiDump.exports("""
            module a {
                requires transitive x.y;
                exports a.b;
                // exports not.this;
                exports a.b.c ;
                opens nothing;
            }
            """));
    }

    @Test
    void theDifferenceNamesWhatChanged() {
        String before = """
            public class a.A {
                public int gone()
                public int same()
            }

            public class a.Removed {
                public int x
            }

            """;
        String after = """
            public class a.A {
                public int same()
                public int added()
            }

            public class a.Added {
                public int y
            }

            """;
        assertEquals("""
            - class removed: public class a.Removed
            - public int x
            + class added: public class a.Added
            + public int y
              public class a.A
            - public int gone()
            + public int added()
            """.lines().sorted().toList(), ApiDump.diff(before, after).lines().sorted().toList());
        assertEquals("", ApiDump.diff(before, before));
    }
}
