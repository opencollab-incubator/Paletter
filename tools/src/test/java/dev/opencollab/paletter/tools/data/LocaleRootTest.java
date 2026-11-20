package dev.opencollab.paletter.tools.data;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
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

    /** A conversion that writes a number in the default locale's digits: {@code %d}, {@code %f}, {@code %e}, {@code %g} (not {@code %s}, {@code %x}). */
    private static final Pattern NUMBER_CONVERSION = Pattern.compile("%[-#+ 0,(]*\\d*(?:\\.\\d+)?[dfeEgG]");
    /** The literals of a Java file in order, with the comments: a text block, a string, a character, a comment. */
    private static final Pattern TOKEN = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|\"(?:[^\"\\\\\\n]|\\\\.)*\"|'(?:[^'\\\\\\n]|\\\\.)*'|//.*|/\\*[\\s\\S]*?\\*/");
    private static final Pattern CONSTANT = Pattern.compile("\\b([A-Z][A-Z0-9_]*)\\.formatted\\(");

    private static List<Path> sources() throws IOException {
        List<Path> result = new ArrayList<>();
        for (String module : List.of("paletter", "paletter-recipes", "tools", "examples")) {
            Path src = ROOT.resolve(module).resolve("src");
            if (Files.isDirectory(src)) {
                try (Stream<Path> files = Files.walk(src)) {
                    files.filter(f -> f.toString().endsWith(".java")).forEach(result::add);
                }
            }
        }
        return result;
    }

    @Test
    void everyCaseConversionNamesALocale() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : sources()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (DEFAULT_LOCALE.matcher(lines.get(i)).find()) {
                    found.add(ROOT.relativize(file) + ":" + (i + 1));
                }
            }
        }
        assertEquals(List.of(), found, "case conversions without Locale.ROOT");
    }

    /**
     * {@code String.format(pattern, ...)} and {@code pattern.formatted(...)} write {@code %d} and {@code %f} in the digits of the
     * default locale ({@code ar-EG}: Arabic-Indic), which ends up in file names and JSON; they take {@code String.format(Locale.ROOT, ...)}.
     * The pattern is the literal at the call, or the constant of the file the call is made on.
     */
    @Test
    void everyNumberFormatNamesALocale() throws IOException {
        List<String> found = new ArrayList<>();
        // Not the tests: what they format is their own expected text, no file name or JSON a tool writes
        for (Path file : sources().stream().filter(f -> !ROOT.relativize(f).toString().replace('\\', '/').contains("/src/test/")).toList()) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, String> constants = new HashMap<>();
            List<int[]> literals = new ArrayList<>();
            Matcher token = TOKEN.matcher(text);
            while (token.find()) {
                if (token.group().startsWith("\"")) {
                    literals.add(new int[] {token.start(), token.end()});
                    Matcher name = Pattern.compile("\\b([A-Z][A-Z0-9_]*)\\s*=\\s*$").matcher(text.substring(Math.max(0, token.start() - 80), token.start()));
                    if (name.find()) {
                        constants.put(name.group(1), token.group());
                    }
                }
            }
            for (int[] literal : literals) {
                String pattern = text.substring(literal[0], literal[1]);
                boolean formatted = text.startsWith(".formatted(", literal[1]);
                boolean format = text.substring(Math.max(0, literal[0] - 40), literal[0]).matches("(?s).*String\\.format\\(\\s*$");
                if ((formatted || format) && NUMBER_CONVERSION.matcher(pattern).find()) {
                    found.add(ROOT.relativize(file) + ":" + lineOf(text, literal[1]));
                }
            }
            Matcher constant = CONSTANT.matcher(text);
            while (constant.find()) {
                String pattern = constants.get(constant.group(1));
                if (pattern != null && NUMBER_CONVERSION.matcher(pattern).find()) {
                    found.add(ROOT.relativize(file) + ":" + lineOf(text, constant.start()) + " (" + constant.group(1) + ")");
                }
            }
        }
        assertEquals(List.of(), found, "number formats without Locale.ROOT: use String.format(Locale.ROOT, ...)");
    }

    private static int lineOf(String text, int offset) {
        return (int) text.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
    }
}
