package org.geysermc.paletter.tools.codegen.component;

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
 * The Javadoc the generator writes for the compact constructor of a record and for {@code builder()} says what the
 * generated code does: a NullPointerException only where a component is checked for null, an IllegalArgumentException
 * only where there is a range or conflict check, "copies" only where a list or map is copied, and the builder says that
 * required fields must be set only if there are some.
 */
class GeneratedConstructorDocsTest {
    private static final Path GENERATED = Path.of(System.getProperty("paletter.root", ".."), "paletter/src/generated/java/org/geysermc/paletter");
    /** A documented compact constructor: its Javadoc and its body. */
    private static final Pattern COMPACT = Pattern.compile(
        "/\\*\\*\\n((?: +\\*(?!/)[^\\n]*\\n)+) +\\*/\\n( +)public \\w+ \\{\\n((?s:.*?))\\n\\2\\}\\n");
    private static final Pattern BUILDER = Pattern.compile(
        "/\\*\\*\\n((?: +\\*(?!/)[^\\n]*\\n)+) +\\*/\\n +public static [^\\n]* builder\\(\\)");
    private static final Pattern CHECKS = Pattern.compile(
        "Check\\.(range|multipleOf|oneOf|order|conflict|stateValues)\\(|throw new IllegalArgumentException");
    /** A non-null map is copied by {@code Check.copy}, which checks it for null; a nullable one is guarded by {@code == null}. */
    private static final Pattern REQUIRED_COPY = Pattern.compile("= Check\\.copy\\(");

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
    void theConstructorDocSaysWhatTheConstructorDoes() throws IOException {
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (Path source : sources()) {
            Matcher compact = COMPACT.matcher(Files.readString(source));
            while (compact.find()) {
                String doc = compact.group(1);
                String body = compact.group(3);
                if (!doc.contains("Checks the components") && !doc.contains("Copies lists")) {
                    continue;
                }
                checked++;
                boolean nulls = body.contains("requireNonNull") || REQUIRED_COPY.matcher(body).find();
                boolean ranges = CHECKS.matcher(body).find() || body.contains("containsKey(key)");
                boolean copies = body.contains("copyOf(") || body.contains("Check.copy(");
                String name = source.getFileName().toString();
                if (doc.contains("NullPointerException") != nulls) {
                    wrong.add(name + ": NullPointerException documented " + doc.contains("NullPointerException") + ", checked " + nulls);
                }
                if (doc.contains("IllegalArgumentException") != ranges) {
                    wrong.add(name + ": IllegalArgumentException documented " + doc.contains("IllegalArgumentException") + ", checked " + ranges);
                }
                if ((doc.contains("copies lists") || doc.contains("Copies lists")) != copies) {
                    wrong.add(name + ": copying documented " + (doc.contains("copies lists") || doc.contains("Copies lists")) + ", done " + copies);
                }
            }
        }
        assertTrue(checked > 50, "only " + checked + " documented constructors found");
        assertEquals(List.of(), wrong);
    }

    @Test
    void builderDocMentionsRequiredFieldsOnlyIfThereAreSome() throws IOException {
        List<String> wrong = new ArrayList<>();
        int withRequired = 0;
        for (Path source : sources()) {
            String text = Files.readString(source);
            Matcher builder = BUILDER.matcher(text);
            while (builder.find()) {
                // The record this builder() belongs to is the last one declared before it; its field docs precede the
                // declaration, in the Javadoc that opens before the last "record Name("
                String before = text.substring(0, builder.start());
                Matcher declaration = Pattern.compile("(?m)^ *(public )?record \\w+\\(").matcher(before);
                int declared = -1;
                while (declaration.find()) {
                    declared = declaration.start();
                }
                String record = before.substring(before.lastIndexOf("/**", declared), before.length());
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
