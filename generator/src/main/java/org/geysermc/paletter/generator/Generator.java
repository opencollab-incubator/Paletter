package org.geysermc.paletter.generator;

import org.geysermc.paletter.palette.PaletteVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

public abstract class Generator<T> {
    protected abstract String outputPackage();

    protected abstract String outputClass();

    protected abstract T fromVersion(PaletteVersion version);

    protected abstract List<Field> generateFields(T value);

    protected abstract List<Method> generateMethods(T value);

    protected List<String> imports() {
        return List.of();
    }

    public final String generate(PaletteVersion version) {
        T value = fromVersion(version);
        List<Field> fields = generateFields(value);
        List<Method> methods = generateMethods(value);

        List<String> imports = imports();

        StringBuilder builder = new StringBuilder();
        builder.append("package ").append(outputPackage()).append(";\n\n");
        for (String imported : imports) {
            builder.append("import ").append(imported).append(";\n");
        }
        if (!imports.isEmpty()) {
            builder.append("\n");
        }
        builder.append("@SuppressWarnings(\"SpellCheckingInspection\")\n");
        builder.append("public final class ").append(className()).append(" {\n");
        for (Field field : fields) {
            builder.append("    ").append(field).append("\n");
        }
        if (!fields.isEmpty() && !methods.isEmpty()) {
            builder.append("\n");
        }
        for (Method method : methods) {
            for (String line : method.toString().split("\n")) {
                builder.append("    ").append(line).append("\n");
            }
        }
        builder.append("}\n");
        return builder.toString();
    }

    public final Path writeTo(Path sourceRoot, PaletteVersion version) throws IOException {
        Path directory = sourceRoot;
        for (String part : outputPackage().split("\\.")) {
            directory = directory.resolve(part);
        }
        Files.createDirectories(directory);

        Path output = directory.resolve(className() + ".java");
        Files.writeString(output, generate(version));
        return output;
    }

    private String className() {
        String className = outputClass();
        return className.endsWith(".java") ? className.substring(0, className.length() - ".java".length()) : className;
    }

    protected static String constantName(String identifier) {
        String withoutNamespace = identifier.substring(identifier.indexOf(':') + 1);
        // Some vanilla identifiers use "." as a sub-separator (e.g. "item.bed"), which isn't a valid
        // identifier character.
        return withoutNamespace.toUpperCase(Locale.ROOT).replace('.', '_');
    }

    public enum Visibility {
        PUBLIC, PROTECTED, PACKAGE_PRIVATE, PRIVATE;

        String prefix() {
            return switch (this) {
                case PUBLIC -> "public ";
                case PROTECTED -> "protected ";
                case PACKAGE_PRIVATE -> "";
                case PRIVATE -> "private ";
            };
        }
    }

    public record Parameter(String type, String name) {
        @Override
        public String toString() {
            return type + " " + name;
        }
    }

    public static final class Field {
        private final Visibility visibility;
        private final String type;
        private final String name;
        private final List<String> typeParameters = new ArrayList<>();
        private boolean isStatic;
        private boolean isFinal;
        private String defaultValue;

        public Field(Visibility visibility, String type, String name) {
            this.visibility = visibility;
            this.type = type;
            this.name = name;
        }

        public Field asStatic() {
            this.isStatic = true;
            return this;
        }

        public Field asFinal() {
            this.isFinal = true;
            return this;
        }

        public Field withTypeParameters(String... typeParameters) {
            for (String typeParameter : typeParameters) {
                this.typeParameters.add(typeParameter);
            }
            return this;
        }

        public Field withDefaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(visibility.prefix());
            if (isStatic) {
                builder.append("static ");
            }
            if (isFinal) {
                builder.append("final ");
            }
            builder.append(type);
            if (!typeParameters.isEmpty()) {
                StringJoiner joiner = new StringJoiner(", ", "<", ">");
                typeParameters.forEach(joiner::add);
                builder.append(joiner);
            }
            builder.append(" ").append(name);
            if (defaultValue != null) {
                builder.append(" = ").append(defaultValue);
            }
            builder.append(";");
            return builder.toString();
        }
    }

    public static final class Method {
        private final Visibility visibility;
        private final String returnType;
        private final String name;
        private final List<Parameter> parameters = new ArrayList<>();
        private final List<String> body = new ArrayList<>();
        private boolean isStatic;
        private boolean isFinal;

        public Method(Visibility visibility, String returnType, String name) {
            this.visibility = visibility;
            this.returnType = returnType;
            this.name = name;
        }

        public Method asStatic() {
            this.isStatic = true;
            return this;
        }

        public Method asFinal() {
            this.isFinal = true;
            return this;
        }

        public Method withParameters(Parameter... parameters) {
            for (Parameter parameter : parameters) {
                this.parameters.add(parameter);
            }
            return this;
        }

        public Method withBody(String... lines) {
            for (String line : lines) {
                this.body.add(line);
            }
            return this;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(visibility.prefix());
            if (isStatic) {
                builder.append("static ");
            }
            if (isFinal) {
                builder.append("final ");
            }
            builder.append(returnType).append(" ").append(name).append("(");
            StringJoiner joiner = new StringJoiner(", ");
            parameters.forEach(parameter -> joiner.add(parameter.toString()));
            builder.append(joiner).append(") {\n");
            for (String line : body) {
                builder.append("    ").append(line).append("\n");
            }
            builder.append("}");
            return builder.toString();
        }
    }
}
