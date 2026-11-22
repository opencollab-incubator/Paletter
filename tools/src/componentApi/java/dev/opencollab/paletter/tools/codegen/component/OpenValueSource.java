package dev.opencollab.paletter.tools.codegen.component;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.EnumType;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;

import javax.lang.model.element.Modifier;
import java.util.Set;

import static dev.opencollab.paletter.tools.codegen.component.ApiSource.CHECK;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.LIST;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.MAP;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.OBJECTS;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.STRING;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.className;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.nonNull;

/**
 * Writes an open value class: an add-on JSON string field with documented values, a constant per documented value and
 * {@code of} for any other string, so a value Bedrock adds can be built and sent before Paletter knows it (docs/api.md,
 * "Evolution"). Known values are interned: {@code of} returns the constant, so {@code ==} works for them.
 */
final class OpenValueSource {
    /** The private static fields of an open value class; no constant may take these names. */
    static final Set<String> VALUE_CLASS_FIELDS = Set.of("KNOWN", "BY_VALUE");

    private OpenValueSource() {
    }

    static TypeSpec enumType(Comp c, EnumType e) {
        ClassName self = className(c).nestedClass(e.name);
        TypeName list = ParameterizedTypeName.get(LIST, self);
        TypeSpec.Builder type = TypeSpec.classBuilder(self).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("A value of an add-on JSON string: a constant per documented value, {@link #of} for any other.\n"
                + "{@link #value()} is the add-on JSON string. Not an enum: Bedrock adds values, so a {@code switch} over\n"
                + "{@link #value()} needs a {@code default}.\n");
        for (int i = 0; i < e.values.size(); i++) {
            if (VALUE_CLASS_FIELDS.contains(e.constants.get(i))) {
                throw new GenerationException(c.id + " enum " + e.name + " has a value named " + e.constants.get(i) + ", a name its class uses; rename it in api.json");
            }
            type.addField(FieldSpec.builder(self, e.constants.get(i), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("{@code $L}\n", Docs.escape(e.values.get(i))).initializer("new $T($S)", self, e.values.get(i)).build());
        }
        for (int i = 0; i < e.removedValues.size(); i++) {
            if (VALUE_CLASS_FIELDS.contains(e.removedConstants.get(i))) {
                throw new GenerationException(c.id + " enum " + e.name + " has a value named " + e.removedConstants.get(i) + ", a name its class uses; rename it in api.json");
            }
            type.addField(FieldSpec.builder(self, e.removedConstants.get(i), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addAnnotation(Deprecated.class)
                .addJavadoc("{@code $L}\n\n@deprecated Bedrock no longer documents this value; the constant stays so that callers compile,\n"
                    + "and the value is sent like any value of {@link #of} the encoders do not know.\n", Docs.escape(e.removedValues.get(i)))
                .initializer("new $T($S)", self, e.removedValues.get(i)).build());
        }
        type.addField(FieldSpec.builder(list, "KNOWN", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.of($L)", LIST, String.join(", ", e.constants)).build());
        type.addField(FieldSpec.builder(ParameterizedTypeName.get(MAP, STRING, self), "BY_VALUE", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.index(KNOWN, $T::value)", CHECK, self).build());
        type.addField(STRING, "value", Modifier.PRIVATE, Modifier.FINAL);
        type.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).addParameter(STRING, "value").addStatement("this.value = value").build());
        type.addMethod(MethodSpec.methodBuilder("of").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(nonNull(self))
            .addJavadoc("Returns the value of an add-on JSON string: the constant of a documented value, else a new value.\n\n"
                + "@param value the value in add-on JSON\n@return the constant of the value, or a value Paletter does not know\n")
            .addParameter(nonNull(STRING), "value")
            .addStatement("$T known = BY_VALUE.get($T.requireNonNull(value, $S))", self, OBJECTS, e.name + ".value")
            .addStatement("return known != null ? known : new $T(value)", self).build());
        type.addMethod(MethodSpec.methodBuilder("known").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(nonNull(list))
            .addJavadoc("Returns the documented values, the constants of this class.\n\n@return the constants, in the order of the documentation\n")
            .addStatement("return KNOWN").build());
        type.addMethod(MethodSpec.methodBuilder("value").addModifiers(Modifier.PUBLIC).returns(nonNull(STRING))
            .addJavadoc("Returns the value in add-on JSON.\n\n@return the value in add-on JSON\n").addStatement("return value").build());
        type.addMethod(MethodSpec.methodBuilder("isKnown").addModifiers(Modifier.PUBLIC).returns(TypeName.BOOLEAN)
            .addJavadoc("Returns whether the value is documented, one of the constants.\n\n@return whether this is one of {@link #known()}\n")
            .addStatement("return BY_VALUE.get(value) == this").build());
        type.addMethod(MethodSpec.methodBuilder("equals").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC).returns(TypeName.BOOLEAN)
            .addParameter(Object.class, "o").addStatement("return this == o || o instanceof $T other && value.equals(other.value)", self).build());
        type.addMethod(MethodSpec.methodBuilder("hashCode").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC).returns(TypeName.INT)
            .addStatement("return value.hashCode()").build());
        type.addMethod(MethodSpec.methodBuilder("toString").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC).returns(STRING)
            .addStatement("return value").build());
        return type.build();
    }
}
