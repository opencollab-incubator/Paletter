package dev.opencollab.paletter.tools.codegen.component;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ListOf;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.MapOf;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Scalar;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ScalarKind;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.UnionType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Variant;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;

import static dev.opencollab.paletter.tools.codegen.component.ApiSource.BLOCK_TYPE;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.CHECK;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.ITEM_TYPE;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.LIST;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.OBJECTS;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.STRING;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.nonNull;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.sentinelName;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.typeName;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.unionName;

/** Writes a union: the sealed interface of an add-on JSON field that takes one of several forms. */
final class UnionSource {
    private UnionSource() {
    }

    /** The sealed interface of a union: one record per JSON form, and a factory named after each (and one taking vanilla constants for a list of identifiers). */
    static TypeSpec union(UnionType u, String since) {
        ClassName self = unionName(u);
        Comp owner = u.owner;
        StringBuilder doc = new StringBuilder("The value of an add-on JSON field that takes one of several forms:\n");
        doc.append("<ul>\n");
        for (Variant v : u.variants) {
            doc.append("    <li>{@link ").append(v.name).append("}: ").append(meaning(v)).append("</li>\n");
        }
        doc.append("</ul>\n<p>\nBedrock may add forms, so a {@code switch} over it needs a {@code default}; the compiler enforces it\n"
            + "(docs/api.md, \"Evolution\").\n");
        doc.append("<p>\nUsed by: ").append(String.join(", ", u.users.stream().sorted().map(x -> "{@code " + x + "}").toList())).append(".\n");
        if (u.owner == null) {
            doc.append("\n@since ").append(since).append('\n');
        }
        TypeSpec.Builder type = TypeSpec.interfaceBuilder(self).addModifiers(Modifier.PUBLIC, Modifier.SEALED).addJavadoc("$L", doc.toString());
        if (u.owner != null) {
            type.addModifiers(Modifier.STATIC);
        }
        for (Variant v : u.variants) {
            type.addPermittedSubclass(self.nestedClass(v.name));
        }
        type.addPermittedSubclass(sentinelName(self));
        for (Variant v : u.variants) {
            ClassName variant = self.nestedClass(v.name);
            TypeName value = typeName(owner, v.type, false);
            boolean reference = !value.isPrimitive();
            CodeBlock.Builder check = CodeBlock.builder();
            if (v.type instanceof ListOf) {
                check.addStatement("value = $T.copyOf($T.requireNonNull(value, $S))", LIST, OBJECTS, u.name + "." + v.name + ".value");
            } else if (v.type instanceof MapOf) {
                check.addStatement("value = $T.copy(value, $S)", CHECK, u.name + "." + v.name + ".value");
            } else if (reference) {
                check.addStatement("$T.requireNonNull(value, $S)", OBJECTS, u.name + "." + v.name + ".value");
            }
            MethodSpec.Builder constructor = check.isEmpty() ? MethodSpec.constructorBuilder()
                : MethodSpec.compactConstructorBuilder().addModifiers(Modifier.PUBLIC).addCode(check.build())
                    .addJavadoc("Checks the value and copies a list or map, so the form is immutable.\n\n@param value the value of the form\n@throws NullPointerException if the value is null\n");
            type.addType(TypeSpec.recordBuilder(variant).addModifiers(Modifier.PUBLIC, Modifier.STATIC).addSuperinterface(self)
                .addJavadoc("The form that is $L.\n\n@param value $L\n", meaning(v), meaning(v))
                .recordConstructor(constructor.addParameter(reference ? nonNull(value) : value, "value").build()).build());
            type.addMethod(MethodSpec.methodBuilder(v.factory()).addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addJavadoc("Creates the form that is $L.\n\n@param value $L\n@return the form that is $L\n", meaning(v), meaning(v), meaning(v))
                .addParameter(reference ? nonNull(value) : value, "value").returns(nonNull(self))
                .addStatement("return new $T(value)", variant).build());
            if (u.descriptor != null && v.type instanceof ListOf l && l.element() instanceof Scalar s && s.kind() == ScalarKind.STRING) {
                ClassName handle = u.descriptor.equals("item") ? ITEM_TYPE : BLOCK_TYPE;
                type.addMethod(MethodSpec.methodBuilder(v.factory()).addModifiers(Modifier.PUBLIC, Modifier.STATIC).varargs()
                    .addJavadoc("Creates the form that is $L, of vanilla or custom $Ls (at least one).\n\n@param first the first one\n@param rest the others\n@return the form that is $L\n", meaning(v), u.descriptor, meaning(v))
                    .addParameter(nonNull(handle), "first")
                    .addParameter(com.palantir.javapoet.ArrayTypeName.of(nonNull(handle)), "rest").returns(nonNull(self))
                    .addStatement("$T<$T> identifiers = new $T<>()", LIST, STRING, ArrayList.class)
                    .addStatement("identifiers.add(first.identifier())")
                    .beginControlFlow("for ($T type : rest)", handle).addStatement("identifiers.add(type.identifier())").endControlFlow()
                    .addStatement("return new $T(identifiers)", variant).build());
            }
        }
        return type.build();
    }

    /** What a form means: the words api.json gives it, else its JSON type ("a list of strings"). */
    static String meaning(Variant v) {
        return v.doc != null ? v.doc : formName(v.json);
    }

    /** "a boolean", "a list of strings": how a JSON type reads in a sentence. */
    static String formName(String json) {
        if (json.startsWith("array<")) {
            return "a list of " + json.substring(6, json.length() - 1) + "s";
        }
        if (json.startsWith("map<")) {
            return "a map of " + json.substring(4, json.length() - 1) + "s";
        }
        return (json.matches("[aeiou].*") ? "an " : "a ") + json;
    }
}
