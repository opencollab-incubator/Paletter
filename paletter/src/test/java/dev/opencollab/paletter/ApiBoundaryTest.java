package dev.opencollab.paletter;

import dev.opencollab.paletter.annotation.Internal;
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
 * internal type ({@code dev.opencollab.paletter.internal.*}, anything {@link Internal}, or a Paletter package that is not
 * exported) in a public or protected signature, nor a type of a dependency that is not part of the API (see
 * {@link #ALLOWED_DEPENDENCIES}), and every exported type documents {@code @since}.
 * <p>
 * "Signature" covers superclasses and interfaces, type parameters and their bounds, record components, fields, constructor
 * and method parameters, return types and thrown types, nested generics and arrays included. Public methods that return
 * or take a non-API type fail the test; fix them or, for a deliberate addition to the API, extend the allow list with a
 * reason.
 */
class ApiBoundaryTest {
    private static final Path MODULE_INFO = Path.of("src/module/java/module-info.java");
    private static final List<Path> SOURCES = List.of(Path.of("src/main/java"), Path.of("src/generated/java"));
    private static final String ROOT = "dev.opencollab.paletter";

    /**
     * The packages outside Paletter and the JDK that signatures may use, each with the reason it is API. Everything else (Gson,
     * Netty, other libraries on the classpath) is an implementation detail that must not leak into a signature.
     */
    private static final List<String> ALLOWED_DEPENDENCIES = List.of(
        // The protocol types are the library's currency: ItemData, definitions, block property data, packets, NbtMap
        "org.cloudburstmc.",
        // The specialized collections the API returns (declared as api dependencies of the build)
        "it.unimi.dsi.fastutil.",
        // @Nullable and @NonNull
        "org.checkerframework.");

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

    /**
     * What is not API is package-private, or in an internal package (other packages reach it through
     * {@code internal.access}): nothing public in an exported package is marked {@link Internal}.
     */
    @Test
    void exportedPackagesHaveNoInternalTypeOrMember() throws Exception {
        Path classes = Path.of(Paletter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> internal = new ArrayList<>();
        for (String pkg : exportedPackages()) {
            try (Stream<Path> files = Files.list(classes.resolve(pkg.replace('.', '/')))) {
                for (Path file : files.filter(f -> f.toString().endsWith(".class")).sorted().toList()) {
                    String name = file.getFileName().toString();
                    Class<?> type = Class.forName(pkg + "." + name.substring(0, name.length() - ".class".length()), false,
                        ApiBoundaryTest.class.getClassLoader());
                    if (type.isAnnotationPresent(Internal.class)) {
                        internal.add(type.getName());
                    }
                    List<Member> members = new ArrayList<>(List.of(type.getDeclaredFields()));
                    members.addAll(List.of(type.getDeclaredMethods()));
                    members.addAll(List.of(type.getDeclaredConstructors()));
                    for (Member member : members) {
                        int modifiers = member.getModifiers();
                        if ((Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers))
                            && ((AnnotatedElement) member).isAnnotationPresent(Internal.class)) {
                            internal.add(type.getName() + "#" + member.getName());
                        }
                    }
                }
            }
        }
        assertTrue(internal.isEmpty(), "Public @Internal types and members in exported packages:\n  " + String.join("\n  ", internal));
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

    /**
     * Hand-written files whose members do not document {@code @since} yet: they are being reworked elsewhere and get it
     * there. Remove a file once it is done; do not add one.
     */
    private static final Set<String> MEMBERS_WITHOUT_SINCE = Set.of(
        "Fallbacks", "block/BlockFallback", "block/BlockHashes", "block/BlockProperty",
        "block/CustomBlock", "block/RegisteredBlockState", "block/VoxelShape", "block/VoxelShapes",
        "block/component/OnPlayerPlacing", "block/component/ScriptHooks", "creative/CreativeCategory",
        "creative/CreativeContent", "creative/CreativeEditor", "creative/CreativeGroup", "creative/CreativeLayout",
        "creative/Placements", "item/BedrockItemStack", "item/CustomItem", "item/ItemFallback", "item/ItemOverride",
        "item/RegisteredItem", "item/component/FrameCount", "item/component/MiningSpeed",
        "item/component/RenderOffsets", "item/component/UseOnHook", "vanilla/BedrockBlockState", "vanilla/BedrockBlockType",
        "vanilla/BedrockItemType", "vanilla/BedrockItemVariant", "vanilla/BedrockState", "vanilla/BoolState",
        "vanilla/EnumState", "vanilla/IntState", "vanilla/StringState");

    /** A Javadoc comment, the annotations after it, and the public or protected declaration it documents. */
    private static final Pattern MEMBER = Pattern.compile(
        "/\\*\\*(.*?)\\*/\\s*((?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*)(?:public|protected)\\s+([^;{=]*)", Pattern.DOTALL);

    /**
     * Every public member of the hand-written API says since when it exists (docs/api.md "Evolution"); a member added to
     * a type after its first release must not seem as old as the type. Generated sources are not checked here.
     */
    @Test
    void everyHandWrittenMemberDocumentsSince() throws IOException {
        Path root = Path.of("src/main/java").resolve(ROOT.replace('.', '/'));
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/').replaceAll("\\.java$", "");
                if (relative.startsWith("internal/") || relative.endsWith("package-info") || MEMBERS_WITHOUT_SINCE.contains(relative)) {
                    continue;
                }
                Matcher matcher = MEMBER.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    String declaration = matcher.group(3);
                    boolean type = Pattern.compile("\\b(class|interface|record|enum|@interface)\\b").matcher(declaration.split("\\(")[0]).find();
                    String annotations = matcher.group(2);
                    if (!type && !annotations.contains("Internal") && !annotations.contains("Override") && !matcher.group(1).contains("@since")) {
                        missing.add(relative + ": " + declaration.strip());
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "Public members without @since in their javadoc:\n  " + String.join("\n  ", missing));
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
            return null;
        }
        if (pkg.startsWith("java.") || pkg.startsWith("javax.") || ALLOWED_DEPENDENCIES.stream().anyMatch(pkg::startsWith)) {
            return null;
        }
        return "non-API dependency";
    }

    /**
     * Cloudburst's protocol types sit in {@code org.cloudburstmc.protocol...}; the allow list takes the whole group, because
     * its modules (protocol, nbt, math) are all reached through the one {@code api} dependency of the build.
     */
    @Test
    void allowListMatchesTheBuild() throws IOException {
        String build = Files.readString(Path.of("build.gradle.kts"), StandardCharsets.UTF_8);
        assertTrue(build.contains("api(libs.protocol.codec)"), "the Cloudburst allow list expects it to be an api dependency");
        assertTrue(build.contains("api(libs.fastutil."), "the fastutil allow list expects it to be an api dependency");
    }
}
