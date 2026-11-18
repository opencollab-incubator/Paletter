package dev.opencollab.paletter.tools.codegen.component;

import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import java.util.Locale;
import java.util.Set;

/**
 * The Java names of the generated API: the identifiers the model's names become, and the words that cannot be one.
 */
final class JavaNames {
    private JavaNames() {
    }

    static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto",
        "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected",
        "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null", "record", "var", "yield", "sealed", "permits");
    static final Set<String> JAVA_LANG = Set.of("Record", "Throwable", "Object", "String", "Class", "Enum", "Exception", "Error",
        "Integer", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Character", "Number", "Void", "Math", "System", "Thread",
        "Runtime", "Process", "Module", "Package", "Iterable", "Comparable", "Override", "Deprecated");

    static String pascal(String s) {
        StringBuilder out = new StringBuilder();
        for (String part : s.split("[^A-Za-z0-9]+")) {
            if (!part.isEmpty()) {
                out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return out.toString();
    }

    static String camel(String s) {
        String p = pascal(s);
        return p.isEmpty() ? p : Character.toLowerCase(p.charAt(0)) + p.substring(1);
    }

    static String constant(String value) {
        String c = value.replaceFirst("^minecraft:", "").replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        if (c.isEmpty() || Character.isDigit(c.charAt(0))) {
            c = "_" + c;
        }
        return c;
    }

    static void javaName(String name, String where) {
        if (name == null || name.isEmpty() || !name.matches("[A-Za-z_][A-Za-z0-9_]*") || KEYWORDS.contains(name)) {
            throw new GenerationException("not a Java identifier: '" + name + "' for " + where + "; rename it in api.json");
        }
    }
}
