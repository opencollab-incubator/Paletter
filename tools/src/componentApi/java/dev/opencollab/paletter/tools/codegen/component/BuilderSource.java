package dev.opencollab.paletter.tools.codegen.component;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Field;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ListOf;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Scalar;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ScalarKind;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Type;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.UnionRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.UnionType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Variant;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Vec3;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;

import static dev.opencollab.paletter.tools.codegen.component.ApiSource.BLOCK_TYPE;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.FOR_REMOVAL;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.ITEM_TYPE;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.LIST;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.OBJECTS;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.STRING;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.allFields;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.className;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.nonNull;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.nullable;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.primitive;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.unionName;
import static dev.opencollab.paletter.tools.codegen.component.UnionSource.meaning;

/** Writes the builder of a value class: a setter per field, its overloads, and {@code build()} with the required checks. */
final class BuilderSource {
    /** Javadoc of a builder setter: the summary, then the one parameter and the builder it returns. */
    private static final String SETTER = "$L\n\n@param $L the value\n@return this builder\n";

    private final Comp c;
    private final ApiModel model;

    BuilderSource(Comp c, ApiModel model) {
        this.c = c;
        this.model = model;
    }

    private TypeName typeName(Type t, boolean boxed) {
        return ApiSource.typeName(c, t, boxed);
    }

    private TypeName recordType(Field f) {
        return ApiSource.recordType(c, f);
    }

    TypeSpec builder(ObjType o, ClassName self) {
        ClassName builder = self.nestedClass("Builder");
        TypeSpec.Builder type = TypeSpec.classBuilder(builder).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("Builds {@link $T}: defaults applied, required fields and ranges checked by {@link #build()}.\n", self);
        for (Field f : allFields(o)) {
            FieldSpec.Builder field = FieldSpec.builder(nullable(typeName(f.type, true)), f.java, Modifier.PRIVATE);
            if (f.defaultValue != null) {
                field.initializer(ApiSource.literal(c, f.type, f.defaultValue));
            }
            type.addField(field.build());
        }
        type.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        for (Field f : allFields(o)) {
            setters(type, builder, f);
        }
        for (ApiHistory.Renamed r : ValueClassSource.renames(model, c, o)) {
            type.addMethod(MethodSpec.methodBuilder(r.oldName()).addModifiers(Modifier.PUBLIC).addAnnotation(Deprecated.class)
                .returns(nonNull(builder)).addParameter(recordType(r.now()), r.oldName())
                .addJavadoc("Sets {@link #$L}: this is its name from before Bedrock renamed its add-on JSON key to {@code $L}.\n\n"
                    + "@param $L the value\n@return this builder\n@deprecated renamed to {@link #$L}\n", r.now().java, r.now().json, r.oldName(), r.now().java)
                .addStatement("return $N($N)", r.now().java, r.oldName()).build());
        }
        for (Field f : o.removedFields) {
            type.addMethod(MethodSpec.methodBuilder(f.java).addModifiers(Modifier.PUBLIC).addAnnotation(FOR_REMOVAL)
                .returns(nonNull(builder)).addParameter(ValueClassSource.standInType(c, f), f.java)
                .addJavadoc("Does nothing: Bedrock no longer has add-on JSON {@code $L}.\n\n@param $L ignored\n@return this builder\n"
                    + "@deprecated Bedrock no longer has it; it goes in the next major release\n", f.json, f.java)
                .addStatement("return this").build());
        }
        MethodSpec.Builder build = MethodSpec.methodBuilder("build").addModifiers(Modifier.PUBLIC).returns(nonNull(self))
            .addJavadoc("Builds the value from the fields set and the defaults.\n\n@return the value\n@throws IllegalStateException if a required field is not set\n@throws IllegalArgumentException if a value is out of range\n");
        String owner = c.className + (o == c.root ? "" : "." + o.name);
        for (Field f : allFields(o)) {
            if (f.required) {
                build.beginControlFlow("if ($N == null)", f.java)
                    .addStatement("throw new $T($S)", IllegalStateException.class, owner + "." + f.java + " is required").endControlFlow();
            }
        }
        build.addStatement("return new $T($L)", self, String.join(", ", allFields(o).stream().map(f -> f.java).toList()));
        type.addMethod(build.build());
        return type.build();
    }

    private void setters(TypeSpec.Builder type, ClassName builder, Field f) {
        String json = f.json == null ? f.java : f.json;
        // A null for a @NonNull field fails here, not later in build(); a null for a nullable one unsets it
        boolean nonNull = !primitive(f) && !nullable(f);
        MethodSpec.Builder main = MethodSpec.methodBuilder(f.java).addModifiers(Modifier.PUBLIC).returns(nonNull(builder))
            .addJavadoc(nonNull ? SETTER + "@throws NullPointerException if the value is null\n" : SETTER, "Sets {@code " + json + "}.", f.java)
            .addParameter(recordType(f), f.java);
        if (nonNull) {
            main.addStatement("this.$N = $T.requireNonNull($N, $S)", f.java, OBJECTS, f.java,
                String.join(".", builder.enclosingClassName().simpleNames()) + "." + f.java);
        } else {
            main.addStatement("this.$N = $N", f.java, f.java);
        }
        type.addMethod(main.addStatement("return this").build());
        if (f.type instanceof UnionRef u) {
            unionSetters(type, builder, f, u.union(), json);
        }
        if (f.type instanceof ListOf l) {
            // At least one value, so that a null or an empty list goes to the List overload: no ambiguous call
            varargs(type, builder, f, json, typeName(l.element(), false), typeName(l.element(), true), "values.add(first)",
                CodeBlock.of("$N.add(x)", "values"), "the values");
            if (l.element() instanceof Scalar s && s.kind() == ScalarKind.STRING && f.descriptor != null) {
                ClassName handle = f.descriptor.equals("item") ? ITEM_TYPE : BLOCK_TYPE;
                varargs(type, builder, f, json, handle, STRING, "values.add(first.identifier())",
                    CodeBlock.of("$N.add(x.identifier())", "values"), "the identifiers of vanilla or custom " + f.descriptor + "s");
            }
        }
        if (f.type instanceof Scalar s && s.kind() == ScalarKind.STRING && f.descriptor != null) {
            ClassName handle = f.descriptor.equals("item") ? ITEM_TYPE : BLOCK_TYPE;
            type.addMethod(MethodSpec.methodBuilder(f.java).addModifiers(Modifier.PUBLIC).returns(nonNull(builder))
                .addJavadoc(SETTER, "Sets {@code " + json + "} to a vanilla or custom " + f.descriptor + ".", f.java)
                .addParameter(nonNull(handle), f.java).addStatement("return $N($N.identifier())", f.java, f.java).build());
        }
        if (f.type instanceof Vec3) {
            type.addMethod(MethodSpec.methodBuilder(f.java).addModifiers(Modifier.PUBLIC).returns(nonNull(builder))
                .addJavadoc("Sets {@code $L}.\n\n@param x the x component\n@param y the y component\n@param z the z component\n@return this builder\n", json).addParameter(float.class, "x").addParameter(float.class, "y").addParameter(float.class, "z")
                .addStatement("return $N(new $T(x, y, z))", f.java, className(c).nestedClass("Vec3")).build());
        }
        if (f.type instanceof ObjRef r && r.type().shorthand != null) {
            Field target = r.type().shorthand;
            TypeName param = typeName(target.type, false);
            type.addMethod(MethodSpec.methodBuilder(f.java).addModifiers(Modifier.PUBLIC).returns(nonNull(builder))
                .addJavadoc(SETTER, "Sets {@code " + json + "} with its shorthand: {@code " + target.json + "}.", target.java)
                .addParameter(param.isPrimitive() ? param : nonNull(param), target.java)
                .addStatement("return $N($T.of($N))", f.java, className(c).nestedClass(r.type().name), target.java).build());
        }
    }

    /**
     * The overloads of a union field, one set per form: the value as it is ({@code uvLock(true)}), a list also as varargs of at
     * least one value ({@code uvLock()} would mean nothing) and, for item identifiers, as vanilla or custom items. A form
     * api.json {@code noOverload} lists gets none (a string that is an alias or a query, not the obvious value): it is set
     * with the factory of the union.
     */
    private void unionSetters(TypeSpec.Builder type, ClassName builder, Field f, UnionType u, String json) {
        for (Variant v : u.variants) {
            if (u.noOverload.contains(v.name)) {
                continue;
            }
            TypeName param = typeName(v.type, false);
            type.addMethod(MethodSpec.methodBuilder(f.java).addModifiers(Modifier.PUBLIC).returns(nonNull(builder))
                .addJavadoc(SETTER, "Sets {@code " + json + "} to " + meaning(v) + ".", f.java)
                .addParameter(param.isPrimitive() ? param : nonNull(param), f.java)
                .addStatement("return $N($T.$N($N))", f.java, unionName(u), v.factory(), f.java).build());
            if (v.type instanceof ListOf l) {
                varargs(type, builder, f, json, typeName(l.element(), false), typeName(l.element(), true), "values.add(first)",
                    CodeBlock.of("$N.add(x)", "values"), meaning(v));
                if (l.element() instanceof Scalar s && s.kind() == ScalarKind.STRING && f.descriptor != null) {
                    ClassName handle = f.descriptor.equals("item") ? ITEM_TYPE : BLOCK_TYPE;
                    varargs(type, builder, f, json, handle, STRING, "values.add(first.identifier())",
                        CodeBlock.of("$N.add(x.identifier())", "values"), "the identifiers of vanilla or custom " + f.descriptor + "s");
                }
            }
        }
    }

    /**
     * {@code name(T first, T... rest)}: a list of at least one value, passed on as the list. Not {@code name(T...)}: then
     * {@code name(null)} would be ambiguous with the {@code List} overload, which takes an empty list and a null.
     */
    private void varargs(TypeSpec.Builder type, ClassName builder, Field f, String json, TypeName element, TypeName stored, String first,
                         CodeBlock each, String meaning) {
        TypeName param = element.isPrimitive() ? element : nonNull(element);
        type.addMethod(MethodSpec.methodBuilder(f.java).addModifiers(Modifier.PUBLIC).returns(nonNull(builder)).varargs()
            .addJavadoc("Sets {@code $L} to $L (at least one value).\n\n@param first the first value\n@param rest the other values\n@return this builder\n", json, meaning)
            .addParameter(param, "first").addParameter(com.palantir.javapoet.ArrayTypeName.of(param), "rest")
            .addStatement("$T<$T> values = new $T<>()", LIST, stored, ArrayList.class)
            .addStatement(first)
            .beginControlFlow("for ($T x : rest)", element).addStatement(each).endControlFlow()
            .addStatement("return $N(values)", f.java).build());
    }
}
