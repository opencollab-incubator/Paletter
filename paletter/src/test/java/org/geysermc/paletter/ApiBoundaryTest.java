package org.geysermc.paletter;

import org.geysermc.paletter.annotation.Internal;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the API boundary declared in {@code src/module/java/module-info.java}: no exported type may reference an
 * internal type ({@code org.geysermc.paletter.internal.*}, anything {@link Internal}, or a Paletter package that is not
 * exported) in a public or protected signature, and every exported type documents {@code @since}.
 */
class ApiBoundaryTest {
    private static final Path MODULE_INFO = Path.of("src/module/java/module-info.java");
    private static final List<Path> SOURCES = List.of(Path.of("src/main/java"), Path.of("src/generated/java"));
    private static final String ROOT = "org.geysermc.paletter";

    static Set<String> exportedPackages() throws IOException {
        Matcher matcher = Pattern.compile("^\\s*exports\\s+([\\w.]+)\\s*;", Pattern.MULTILINE)
            .matcher(Files.readString(MODULE_INFO, StandardCharsets.UTF_8));
        Set<String> packages = new TreeSet<>();
        while (matcher.find()) {
            packages.add(matcher.group(1));
        }
        return packages;
    }

    /**
     * @return every public type of the exported packages that is not {@link Internal}, nested public types included
     */
    static List<Class<?>> apiTypes() throws IOException, URISyntaxException, ClassNotFoundException {
        Path classes = Path.of(Paletter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Class<?>> types = new ArrayList<>();
        for (String pkg : exportedPackages()) {
            Path dir = classes.resolve(pkg.replace('.', '/'));
            assertTrue(Files.isDirectory(dir), "exported package " + pkg + " does not exist");
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".class")).sorted().toList()) {
                    String name = file.getFileName().toString();
                    Class<?> type = Class.forName(pkg + "." + name.substring(0, name.length() - ".class".length()), false,
                        ApiBoundaryTest.class.getClassLoader());
                    if (isApi(type)) {
                        types.add(type);
                    }
                }
            }
        }
        return types;
    }

    private static boolean isApi(Class<?> type) {
        if (type.isSynthetic() || type.isAnonymousClass() || type.isLocalClass() || type.isAnnotationPresent(Internal.class)) {
            return false;
        }
        for (Class<?> c = type; c != null; c = c.getDeclaringClass()) {
            if (!Modifier.isPublic(c.getModifiers()) || c.isAnnotationPresent(Internal.class)) {
                return false;
            }
        }
        return true;
    }

    @Test
    void exportedSignaturesReferenceNoInternalType() throws Exception {
        Set<String> exported = exportedPackages();
        List<String> violations = new ArrayList<>();
        List<Class<?>> types = apiTypes();
        for (Class<?> type : types) {
            String where = type.getName();
            check(type.getGenericSuperclass(), where + " extends", exported, violations);
            for (Type iface : type.getGenericInterfaces()) {
                check(iface, where + " implements", exported, violations);
            }
            for (TypeVariable<?> variable : type.getTypeParameters()) {
                check(variable, where + " <" + variable.getName() + ">", exported, violations);
            }
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    check(component.getGenericType(), where + " record component " + component.getName(), exported, violations);
                }
            }
            for (Field field : type.getDeclaredFields()) {
                if (visible(field) && !field.isSynthetic()) {
                    check(field.getGenericType(), where + "#" + field.getName(), exported, violations);
                }
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (visible(constructor) && !constructor.isSynthetic()) {
                    checkExecutable(constructor, where + "#<init>", exported, violations);
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (visible(method) && !method.isSynthetic() && !method.isBridge()) {
                    checkExecutable(method, where + "#" + method.getName(), exported, violations);
                }
            }
        }
        assertTrue(types.size() > 80, "found only " + types.size() + " API types");
        assertTrue(violations.isEmpty(), "Exported API references internal types:\n  " + String.join("\n  ", violations));
    }

    @Test
    void everyExportedTypeDocumentsSince() throws Exception {
        List<String> missing = new ArrayList<>();
        for (Class<?> type : apiTypes()) {
            if (type.getDeclaringClass() != null) {
                // Nested types inherit the @since of their top level type
                continue;
            }
            String relative = type.getName().replace('.', '/') + ".java";
            Path source = SOURCES.stream().map(dir -> dir.resolve(relative)).filter(Files::exists).findFirst().orElseThrow();
            String text = Files.readString(source, StandardCharsets.UTF_8);
            int declaration = indexOfDeclaration(text, type.getSimpleName());
            int docEnd = text.lastIndexOf("*/", declaration);
            int docStart = text.lastIndexOf("/**", declaration);
            if (declaration < 0 || docStart < 0 || docEnd < docStart || !text.substring(docStart, docEnd).contains("@since")) {
                missing.add(type.getName());
            }
        }
        assertTrue(missing.isEmpty(), "Exported types without @since in their javadoc:\n  " + String.join("\n  ", missing));
    }

    private static int indexOfDeclaration(String text, String simpleName) {
        Matcher matcher = Pattern.compile("^public .*\\b(class|interface|record|enum|@interface) " + Pattern.quote(simpleName) + "\\b",
            Pattern.MULTILINE).matcher(text);
        return matcher.find() ? matcher.start() : -1;
    }

    private static boolean visible(Member member) {
        int modifiers = member.getModifiers();
        return (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers))
            && !((AnnotatedElement) member).isAnnotationPresent(Internal.class);
    }

    private static void checkExecutable(Executable executable, String where, Set<String> exported, List<String> violations) {
        if (executable instanceof Method method) {
            check(method.getGenericReturnType(), where + " returns", exported, violations);
        }
        for (Type parameter : executable.getGenericParameterTypes()) {
            check(parameter, where + " parameter", exported, violations);
        }
        for (Type exception : executable.getGenericExceptionTypes()) {
            check(exception, where + " throws", exported, violations);
        }
        for (TypeVariable<?> variable : executable.getTypeParameters()) {
            check(variable, where + " <" + variable.getName() + ">", exported, violations);
        }
    }

    private static void check(Type type, String where, Set<String> exported, List<String> violations) {
        check(type, where, exported, violations, new HashSet<>());
    }

    private static void check(Type type, String where, Set<String> exported, List<String> violations, Set<Type> seen) {
        if (type == null || !seen.add(type)) {
            return;
        }
        switch (type) {
            case Class<?> c -> {
                while (c.isArray()) {
                    c = c.getComponentType();
                }
                String problem = problem(c, exported);
                if (problem != null) {
                    violations.add(where + ": " + c.getName() + " (" + problem + ")");
                }
            }
            case ParameterizedType parameterized -> {
                check(parameterized.getRawType(), where, exported, violations, seen);
                check(parameterized.getOwnerType(), where, exported, violations, seen);
                for (Type argument : parameterized.getActualTypeArguments()) {
                    check(argument, where, exported, violations, seen);
                }
            }
            case WildcardType wildcard -> {
                for (Type bound : wildcard.getUpperBounds()) {
                    check(bound, where, exported, violations, seen);
                }
                for (Type bound : wildcard.getLowerBounds()) {
                    check(bound, where, exported, violations, seen);
                }
            }
            case GenericArrayType array -> check(array.getGenericComponentType(), where, exported, violations, seen);
            case TypeVariable<?> variable -> {
                for (Type bound : variable.getBounds()) {
                    check(bound, where, exported, violations, seen);
                }
            }
            default -> {
            }
        }
    }

    private static String problem(Class<?> type, Set<String> exported) {
        if (type.isPrimitive()) {
            return null;
        }
        String pkg = type.getPackageName();
        if (pkg.startsWith(ROOT + ".internal")) {
            return "internal package";
        }
        if (pkg.equals(ROOT) || pkg.startsWith(ROOT + ".")) {
            if (!exported.contains(pkg)) {
                return "package not exported";
            }
            if (!isApi(type)) {
                return "@Internal or not public";
            }
        }
        return null;
    }
}
