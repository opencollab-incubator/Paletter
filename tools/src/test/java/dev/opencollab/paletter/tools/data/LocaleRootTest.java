package dev.opencollab.paletter.tools.data;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Case conversions take {@code Locale.ROOT}: without it, a machine with a Turkish locale turns {@code "int"} into
 * {@code "ınt"} on its way back and the generators break (pull request #7).
 */
class LocaleRootTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));
    private static final Pattern DEFAULT_LOCALE = Pattern.compile("\\.to(Upper|Lower)Case\\(\\)");

    @Test
    void everyCaseConversionNamesALocale() throws IOException {
        List<String> found = new ArrayList<>();
        for (String module : List.of("paletter", "paletter-recipes", "tools", "examples")) {
            Path src = ROOT.resolve(module).resolve("src");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(src)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        if (DEFAULT_LOCALE.matcher(lines.get(i)).find()) {
                            found.add(ROOT.relativize(file) + ":" + (i + 1));
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), found, "case conversions without Locale.ROOT");
    }
}
