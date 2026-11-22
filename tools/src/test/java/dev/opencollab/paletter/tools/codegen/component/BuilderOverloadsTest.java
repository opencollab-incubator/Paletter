package dev.opencollab.paletter.tools.codegen.component;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code setter(null)} unsets a nullable field and never needs a cast (docs/api.md, "Builders"): no builder has another
 * overload of one reference parameter next to a setter that takes {@code @Nullable}.
 */
class BuilderOverloadsTest {
    private static final Pattern SETTER = Pattern.compile("public @NonNull Builder (\\w+)\\(([^)]*)\\) \\{");
    private static final List<String> PRIMITIVES = List.of("boolean", "int", "float", "double", "long", "byte", "short");

    /** @return "File.Builder#name" for every setter name whose null call would be ambiguous */
    static List<String> ambiguous(Map<String, String> sources) {
        List<String> out = new ArrayList<>();
        sources.forEach((file, source) -> {
            // every builder from its declaration to the next class declaration
            String[] parts = source.split("public static final class Builder \\{");
            for (int i = 1; i < parts.length; i++) {
                String builder = parts[i].split("\n    (    )?public (static )?(final )?(sealed )?(class|interface|record) ")[0];
                Map<String, List<String>> single = new TreeMap<>();
                Matcher m = SETTER.matcher(builder);
                while (m.find()) {
                    String params = m.group(2).replaceAll("<[^<>]*(<[^<>]*>)?[^<>]*>", "<>");
                    if (!params.isEmpty() && !params.contains(",")) {
                        single.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(params);
                    }
                }
                single.forEach((name, params) -> {
                    List<String> references = params.stream().filter(p -> !PRIMITIVES.contains(p.substring(0, p.indexOf(' ')))).toList();
                    if (references.size() > 1 && references.stream().anyMatch(p -> p.startsWith("@Nullable"))) {
                        out.add(file + " " + name + references);
                    }
                });
            }
        });
        return out;
    }

    @Test
    void theSyntheticBuildersHaveOneNullableSetterPerName() {
        assertEquals(List.of(), ambiguous(ComponentGenerator.generate(ComponentGeneratorTest.inputs())));
    }

    @Test
    void theCommittedBuildersHaveOneNullableSetterPerName() throws IOException {
        Path root = Path.of(System.getProperty("paletter.root", ".."));
        assertEquals(List.of(), ambiguous(ComponentGenerator.generate(ComponentGenerator.read(root.resolve("data/model"), Main.version(root),
            Main.context(root)))));
    }

    @Test
    void theCheckFindsAnAmbiguousSetter() {
        String source = """
            public static final class Builder {
                    public @NonNull Builder uvLock(@Nullable UvLock uvLock) {
                    public @NonNull Builder uvLock(@NonNull List<String> uvLock) {
                    public @NonNull Builder uvLock(boolean uvLock) {
            """;
        assertEquals(1, ambiguous(Map.of("Geometry.java", source)).size());
    }
}
