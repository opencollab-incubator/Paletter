package org.geysermc.paletter.tools.apidump;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.util.TraceSignatureVisitor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The API dump: a sorted plain-text listing of every public and protected class, field, constant, constructor and method
 * of the exported packages, read from the compiled classes. Members and classes annotated {@code @Internal} are left
 * out, and so is everything outside the exported packages (in particular {@code internal.*}). Nothing in it is written by
 * hand: {@code :paletter:apiDump} regenerates {@code paletter/api/paletter.api}, {@code :paletter:apiCheck} (part of
 * {@code check}) fails with the difference when the file is stale.
 * <p>
 * Format, one class block after the other, sorted by class name; inside a block fields, constructors, then methods, each
 * sorted by name and descriptor:
 * <pre>
 * public final class org.example.Thing extends org.example.Base implements java.lang.Comparable&lt;org.example.Thing&gt; {
 *     public static final int LIMIT = 4
 *     public &lt;init&gt;(java.lang.String)
 *     public java.util.List&lt;java.lang.String&gt; names()
 * }
 * </pre>
 * Types are written with their generic signature; nested classes keep their binary name ({@code Outer$Inner}). A
 * {@code @Deprecated} or {@code @Experimental} member says so in front.
 */
public final class ApiDump {
    private static final String INTERNAL = "Lorg/geysermc/paletter/annotation/Internal;";
    private static final Map<String, String> MARKERS = Map.of(
        "Ljava/lang/Deprecated;", "@Deprecated",
        "Lorg/geysermc/paletter/annotation/Experimental;", "@Experimental");

    private ApiDump() {
    }

    /**
     * @param classes the class files by internal name ({@code org/example/Thing})
     * @param packages the exported packages, dotted
     */
    public static String dump(Map<String, byte[]> classes, Set<String> packages) {
        Map<String, Model> models = new TreeMap<>();
        for (byte[] bytes : classes.values()) {
            Model model = new Model();
            new ClassReader(bytes).accept(model.visitor(), ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            models.put(model.name, model);
        }
        StringBuilder out = new StringBuilder();
        for (Model model : models.values()) {
            if (model.isApi(models, packages)) {
                model.write(out);
            }
        }
        return out.toString();
    }

    /** Reads every class file below the directories. */
    public static Map<String, byte[]> read(List<Path> directories) throws IOException {
        Map<String, byte[]> classes = new TreeMap<>();
        for (Path directory : directories) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    String name = directory.relativize(file).toString().replace('\\', '/');
                    if (name.equals("module-info.class")) {
                        continue;
                    }
                    classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
                }
            }
        }
        return classes;
    }

    /**
     * The exports of a {@code module-info.java}.
     */
    public static Set<String> exports(String moduleInfo) {
        Set<String> packages = new TreeSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^\\s*exports\\s+([\\w.]+)\\s*;", java.util.regex.Pattern.MULTILINE)
            .matcher(moduleInfo);
        while (matcher.find()) {
            packages.add(matcher.group(1));
        }
        return packages;
    }

    /**
     * A readable difference between the committed dump and a new one, or an empty string if they are the same. Every
     * line of a dump is unique within its class block, so the difference is per class: classes added or removed, and
     * for the others the lines that are gone ({@code -}) and new ({@code +}).
     */
    public static String diff(String committed, String generated) {
        Map<String, List<String>> before = blocks(committed);
        Map<String, List<String>> after = blocks(generated);
        StringBuilder out = new StringBuilder();
        Set<String> names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());
        for (String name : names) {
            List<String> old = before.get(name);
            List<String> now = after.get(name);
            if (old == null) {
                out.append("+ class added: ").append(now.getFirst()).append('\n');
                now.stream().skip(1).forEach(line -> out.append("+ ").append(line).append('\n'));
            } else if (now == null) {
                out.append("- class removed: ").append(old.getFirst()).append('\n');
                old.stream().skip(1).forEach(line -> out.append("- ").append(line).append('\n'));
            } else if (!old.equals(now)) {
                out.append("  ").append(now.getFirst()).append('\n');
                Set<String> oldLines = new LinkedHashSet<>(old.subList(1, old.size()));
                Set<String> newLines = new LinkedHashSet<>(now.subList(1, now.size()));
                if (!old.getFirst().equals(now.getFirst())) {
                    out.append("- ").append(old.getFirst()).append('\n').append("+ ").append(now.getFirst()).append('\n');
                }
                oldLines.stream().filter(line -> !newLines.contains(line)).forEach(line -> out.append("- ").append(line).append('\n'));
                newLines.stream().filter(line -> !oldLines.contains(line)).forEach(line -> out.append("+ ").append(line).append('\n'));
            }
        }
        return out.toString();
    }

    /** Class block header (without the opening brace) to the lines of the block, header first. */
    private static Map<String, List<String>> blocks(String dump) {
        Map<String, List<String>> blocks = new LinkedHashMap<>();
        List<String> current = null;
        String key = null;
        for (String line : dump.split("\n")) {
            if (line.endsWith(" {") && !line.startsWith(" ")) {
                String header = line.substring(0, line.length() - 2);
                key = header.substring(header.lastIndexOf(' ') + 1);
                // Header with extends/implements: the class name is the first word after the kind
                String[] words = header.split(" ");
                for (int i = 0; i < words.length; i++) {
                    if (words[i].equals("class") || words[i].equals("interface") || words[i].equals("enum") || words[i].equals("record")
                        || words[i].equals("@interface")) {
                        key = words[i + 1];
                        break;
                    }
                }
                current = new ArrayList<>();
                current.add(header);
                blocks.put(key, current);
            } else if (current != null && !line.equals("}") && !line.isBlank()) {
                current.add(line.strip());
            }
        }
        return blocks;
    }

    // ---------------------------------------------------------------------------------------------------------------

    private record Member(int rank, String name, String descriptor, String line) {
    }

    private static final class Model {
        String name;
        int access;
        String signature;
        String superName;
        String[] interfaces = new String[0];
        final List<String> permitted = new ArrayList<>();
        final Set<String> annotations = new LinkedHashSet<>();
        String outer;
        int innerAccess = -1;
        final List<Member> members = new ArrayList<>();

        ClassVisitor visitor() {
            return new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                    Model.this.name = name;
                    Model.this.access = access;
                    Model.this.signature = signature;
                    Model.this.superName = superName;
                    Model.this.interfaces = interfaces == null ? new String[0] : interfaces;
                }

                @Override
                public void visitPermittedSubclass(String permittedSubclass) {
                    permitted.add(permittedSubclass);
                }

                @Override
                public void visitInnerClass(String inner, String outerName, String innerName, int access) {
                    if (inner.equals(name)) {
                        outer = outerName;
                        innerAccess = access;
                    }
                }

                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    annotations.add(descriptor);
                    return null;
                }

                @Override
                public FieldVisitor visitField(int access, String fieldName, String descriptor, String signature, Object value) {
                    if (!visible(access) || (access & Opcodes.ACC_SYNTHETIC) != 0) {
                        return null;
                    }
                    Set<String> marks = new LinkedHashSet<>();
                    return new FieldVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                            marks.add(annotation);
                            return null;
                        }

                        @Override
                        public void visitEnd() {
                            if (marks.contains(INTERNAL)) {
                                return;
                            }
                            String type = signature != null ? field(signature, access) : type(Type.getType(descriptor));
                            String line = prefix(marks) + modifiers(access, false) + type + " " + fieldName
                                + (value != null && (access & Opcodes.ACC_STATIC) != 0 && (access & Opcodes.ACC_FINAL) != 0 ? " = " + constant(value, descriptor) : "");
                            members.add(new Member(0, fieldName, descriptor, line));
                        }
                    };
                }

                @Override
                public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature, String[] exceptions) {
                    if (!visible(access) || (access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0 || methodName.equals("<clinit>")) {
                        return null;
                    }
                    Set<String> marks = new LinkedHashSet<>();
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                            marks.add(annotation);
                            return null;
                        }

                        @Override
                        public void visitEnd() {
                            if (marks.contains(INTERNAL)) {
                                return;
                            }
                            boolean constructor = methodName.equals("<init>");
                            String signatureText = signature != null ? method(signature, access) : plainMethod(descriptor);
                            // "<T>(args)" then the return type; constructors have none
                            int ret = signatureText.lastIndexOf('\u0000');
                            String declaration = ret < 0 ? signatureText : signatureText.substring(0, ret);
                            String returns = ret < 0 ? "" : signatureText.substring(ret + 1);
                            int paren = declaration.indexOf('(');
                            String generics = declaration.substring(0, paren);
                            String params = declaration.substring(paren);
                            if ((access & Opcodes.ACC_VARARGS) != 0 && params.endsWith("[])")) {
                                params = params.substring(0, params.length() - 3) + "...)";
                            }
                            StringBuilder line = new StringBuilder(prefix(marks)).append(modifiers(access, true));
                            if ((Model.this.access & Opcodes.ACC_INTERFACE) != 0 && (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)) == 0) {
                                line.append("default ");
                            }
                            if (!generics.isEmpty()) {
                                line.append(generics).append(' ');
                            }
                            if (!constructor) {
                                line.append(returns).append(' ');
                            }
                            line.append(constructor ? "<init>" : methodName).append(params);
                            if (exceptions != null && exceptions.length > 0) {
                                List<String> thrown = new ArrayList<>();
                                for (String exception : exceptions) {
                                    thrown.add(exception.replace('/', '.'));
                                }
                                thrown.sort(Comparator.naturalOrder());
                                line.append(" throws ").append(String.join(", ", thrown));
                            }
                            members.add(new Member(constructor ? 1 : 2, constructor ? "" : methodName, descriptor, line.toString()));
                        }
                    };
                }
            };
        }

        private static boolean visible(int access) {
            return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0;
        }

        private int effectiveAccess() {
            return innerAccess >= 0 ? innerAccess : access;
        }

        /** Whether the class is API: public or protected (and so its enclosing classes), in an exported package, not internal. */
        boolean isApi(Map<String, Model> models, Set<String> packages) {
            if (!visible(effectiveAccess()) || annotations.contains(INTERNAL)) {
                return false;
            }
            if (outer != null) {
                Model enclosing = models.get(outer);
                return enclosing != null && enclosing.isApi(models, packages);
            }
            int slash = name.lastIndexOf('/');
            String pkg = slash < 0 ? "" : name.substring(0, slash).replace('/', '.');
            return packages.contains(pkg);
        }

        void write(StringBuilder out) {
            out.append(header()).append(" {\n");
            members.sort(Comparator.comparingInt(Member::rank).thenComparing(Member::name).thenComparing(Member::descriptor));
            for (Member member : members) {
                out.append("    ").append(member.line()).append('\n');
            }
            out.append("}\n\n");
        }

        private String header() {
            int access = effectiveAccess();
            String kind;
            if ((this.access & Opcodes.ACC_ANNOTATION) != 0) {
                kind = "@interface";
            } else if ((this.access & Opcodes.ACC_INTERFACE) != 0) {
                kind = "interface";
            } else if ((this.access & Opcodes.ACC_ENUM) != 0) {
                kind = "enum";
            } else if ("java/lang/Record".equals(superName)) {
                kind = "record";
            } else {
                kind = "class";
            }
            StringBuilder out = new StringBuilder(prefix(annotations));
            out.append((access & Opcodes.ACC_PUBLIC) != 0 ? "public " : "protected ");
            if ((access & Opcodes.ACC_STATIC) != 0 && outer != null && kind.equals("class")) {
                out.append("static ");
            }
            // An enum with constant bodies is implicitly sealed, and always extends Enum
            boolean sealed = !permitted.isEmpty() && !kind.equals("enum");
            if (sealed) {
                out.append("sealed ");
            }
            if ((this.access & Opcodes.ACC_ABSTRACT) != 0 && kind.equals("class")) {
                out.append("abstract ");
            }
            if ((this.access & Opcodes.ACC_FINAL) != 0 && kind.equals("class")) {
                out.append("final ");
            }
            out.append(kind).append(' ').append(name.replace('/', '.'));
            if (kind.equals("enum")) {
                List<String> implemented = new ArrayList<>();
                for (String iface : interfaces) {
                    implemented.add(iface.replace('/', '.'));
                }
                if (!implemented.isEmpty()) {
                    out.append(" implements ").append(String.join(", ", implemented));
                }
            } else if (signature != null) {
                TraceSignatureVisitor visitor = new TraceSignatureVisitor(this.access);
                new SignatureReader(signature).accept(visitor);
                String declaration = visitor.getDeclaration();
                // "<T> extends A<T> implements B" ("extends" is absent for Object, "implements" becomes "extends" on interfaces)
                out.append(declaration.startsWith("<") || declaration.startsWith(" ") ? "" : " ").append(declaration);
            } else {
                if (superName != null && !superName.equals("java/lang/Object") && kind.equals("class")) {
                    out.append(" extends ").append(superName.replace('/', '.'));
                }
                List<String> implemented = new ArrayList<>();
                for (String iface : interfaces) {
                    if (!iface.equals("java/lang/annotation/Annotation") || !kind.equals("@interface")) {
                        implemented.add(iface.replace('/', '.'));
                    }
                }
                if (!implemented.isEmpty()) {
                    out.append(kind.equals("interface") ? " extends " : " implements ").append(String.join(", ", implemented));
                }
            }
            if (sealed) {
                List<String> names = new ArrayList<>();
                permitted.forEach(permit -> names.add(permit.replace('/', '.')));
                names.sort(Comparator.naturalOrder());
                out.append(" permits ").append(String.join(", ", names));
            }
            return out.toString();
        }

        private static String prefix(Set<String> annotations) {
            StringBuilder out = new StringBuilder();
            MARKERS.forEach((descriptor, marker) -> {
                if (annotations.contains(descriptor)) {
                    out.append(marker).append(' ');
                }
            });
            return out.toString();
        }

        private static String modifiers(int access, boolean method) {
            StringBuilder out = new StringBuilder((access & Opcodes.ACC_PUBLIC) != 0 ? "public " : "protected ");
            if ((access & Opcodes.ACC_STATIC) != 0) {
                out.append("static ");
            }
            if ((access & Opcodes.ACC_FINAL) != 0) {
                out.append("final ");
            }
            if (method && (access & Opcodes.ACC_ABSTRACT) != 0) {
                out.append("abstract ");
            }
            return out.toString();
        }

        private static String type(Type type) {
            return type.getClassName();
        }

        private static String plainMethod(String descriptor) {
            Type method = Type.getMethodType(descriptor);
            List<String> args = new ArrayList<>();
            for (Type argument : method.getArgumentTypes()) {
                args.add(type(argument));
            }
            return "(" + String.join(", ", args) + ")\u0000" + type(method.getReturnType());
        }

        private static String field(String signature, int access) {
            TraceSignatureVisitor visitor = new TraceSignatureVisitor(access);
            new SignatureReader(signature).acceptType(visitor);
            return visitor.getDeclaration();
        }

        /** {@code <T>(args)\0return}, from a generic method signature. */
        private static String method(String signature, int access) {
            TraceSignatureVisitor visitor = new TraceSignatureVisitor(access);
            new SignatureReader(signature).accept(visitor);
            return visitor.getDeclaration() + "\u0000" + visitor.getReturnType();
        }

        private static String constant(Object value, String descriptor) {
            if (descriptor.equals("Z") && value instanceof Integer number) {
                return String.valueOf(number != 0);
            }
            if (descriptor.equals("C") && value instanceof Integer number) {
                return "'" + (char) number.intValue() + "'";
            }
            if (value instanceof String string) {
                StringBuilder out = new StringBuilder("\"");
                for (char c : string.toCharArray()) {
                    switch (c) {
                        case '"' -> out.append("\\\"");
                        case '\\' -> out.append("\\\\");
                        case '\n' -> out.append("\\n");
                        case '\r' -> out.append("\\r");
                        case '\t' -> out.append("\\t");
                        default -> out.append(c < 32 || c > 126 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
                    }
                }
                return out.append('"').toString();
            }
            if (value instanceof Long) {
                return value + "L";
            }
            if (value instanceof Float) {
                return value + "f";
            }
            return String.valueOf(value);
        }
    }
}
