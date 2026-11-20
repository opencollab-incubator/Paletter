package dev.opencollab.paletter.tools.codegen.component;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Javadoc the generator writes for {@code builder()} says what the generated code does: that required fields must be
 * set only if there are some. (The constructor that checks the values is private; {@code build()} documents what it throws.)
 */
class GeneratedConstructorDocsTest {
    private static final Path GENERATED = Path.of(System.getProperty("paletter.root", ".."), "paletter/src/generated/java/dev/opencollab/paletter");
    private static final Pattern BUILDER = Pattern.compile(
        "/\\*\\*\\n((?: +\\*(?!/)[^\\n]*\\n)+) +\\*/\\n +public static [^\\n]* builder\\(\\)");

    private static List<Path> sources() throws IOException {
        List<Path> all = new ArrayList<>();
        for (String directory : List.of("block/component", "item/component")) {
            try (Stream<Path> files = Files.walk(GENERATED.resolve(directory))) {
                all.addAll(files.filter(f -> f.toString().endsWith(".java")).toList());
            }
        }
        return all;
    }

    @Test
    void builderDocMentionsRequiredFieldsOnlyIfThereAreSome() throws IOException {
        List<String> wrong = new ArrayList<>();
        int withRequired = 0;
        for (Path source : sources()) {
            String text = Files.readString(source);
            Matcher builder = BUILDER.matcher(text);
            while (builder.find()) {
                // The class this builder() belongs to is the last one declared before it; its accessors and their field
                // docs are between the two
                String before = text.substring(0, builder.start());
                Matcher declaration = Pattern.compile("(?m)^ *public (static )?final class \\w+ ").matcher(before);
                int declared = -1;
                while (declaration.find()) {
                    declared = declaration.start();
                }
                String record = before.substring(declared);
                boolean required = record.contains(" Required.");
                boolean says = builder.group(1).contains("required fields must be set");
                withRequired += required ? 1 : 0;
                if (required != says) {
                    wrong.add(source.getFileName() + ": required " + required + ", the builder doc says " + says);
                }
            }
        }
        assertTrue(withRequired > 10, "only " + withRequired + " builders with required fields found");
        assertEquals(List.of(), wrong);
    }
}
