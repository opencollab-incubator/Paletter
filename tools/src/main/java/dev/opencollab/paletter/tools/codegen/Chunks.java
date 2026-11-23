package dev.opencollab.paletter.tools.codegen;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * How the data generators (behaviour values, block definitions, voxel shapes) split long lists of statements so that no
 * method nears the JVM's 64 KB of bytecode and no class its constant pool limit: methods {@code part0}, {@code part1}, ...
 * of at most {@link #METHOD_STATEMENTS} statements or {@link #METHOD_CHARS} source characters, {@link #CLASS_METHODS} per
 * class. The limits tests of the generated classes check the result.
 */
public final class Chunks {
    public static final int METHOD_STATEMENTS = 60;
    /** The most source characters in one method: a long chain is about its length in bytecode. */
    public static final int METHOD_CHARS = 16_000;
    public static final int CLASS_METHODS = 8;

    private Chunks() {
    }

    /**
     * @return classes {@code name}, {@code namePart2}, ... whose methods {@code part0}, ... hold the statements in order;
     * {@code load} of the first calls them all
     */
    public static List<TypeSpec> chunked(String name, String doc, List<CodeBlock> statements, ClassName parameter, String argument) {
        List<List<CodeBlock>> methods = new ArrayList<>();
        List<CodeBlock> current = new ArrayList<>();
        int chars = 0;
        for (CodeBlock statement : statements) {
            int length = statement.toString().length();
            if (!current.isEmpty() && (current.size() == METHOD_STATEMENTS || chars + length > METHOD_CHARS)) {
                methods.add(current);
                current = new ArrayList<>();
                chars = 0;
            }
            current.add(statement);
            chars += length;
        }
        if (!current.isEmpty()) {
            methods.add(current);
        }
        List<TypeSpec.Builder> classes = new ArrayList<>();
        MethodSpec.Builder load = MethodSpec.methodBuilder("load").addModifiers(Modifier.STATIC).addParameter(parameter, argument);
        for (int m = 0; m < methods.size(); m++) {
            if (m % CLASS_METHODS == 0) {
                classes.add(TypeSpec.classBuilder(classes.isEmpty() ? name : name + "Part" + (classes.size() + 1)).addModifiers(Modifier.FINAL)
                    .addJavadoc(doc + (classes.isEmpty() ? "" : " (continued)") + "\n")
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build()));
            }
            String className = classes.size() == 1 ? name : name + "Part" + classes.size();
            classes.getLast().addMethod(MethodSpec.methodBuilder("part" + m).addModifiers(Modifier.STATIC).addParameter(parameter, argument)
                .addCode(CodeBlock.join(methods.get(m), "")).build());
            if (className.equals(name)) {
                load.addStatement("part$L($N)", m, argument);
            } else {
                load.addStatement("$N.part$L($N)", className, m, argument);
            }
        }
        if (classes.isEmpty()) {
            classes.add(TypeSpec.classBuilder(name).addModifiers(Modifier.FINAL).addJavadoc(doc + "\n")
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build()));
        }
        classes.getFirst().addMethod(load.build());
        return classes.stream().map(TypeSpec.Builder::build).toList();
    }
}
