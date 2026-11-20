package dev.opencollab.paletter.tools.codegen.component;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.EnumRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.EnumType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Field;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ListOf;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.MapOf;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Scalar;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ScalarKind;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Shorthand;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.States;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Type;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.UnionRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.UnionType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Vec3;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.opencollab.paletter.tools.codegen.component.ApiSource.BEDROCK_EXPERIMENT;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.BEDROCK_FORMAT;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.BEDROCK_SINCE;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.BEDROCK_UNTIL;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.BLOCK_STATE;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.BLOCK_TYPE;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.CHECK;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.FOR_REMOVAL;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.LIST;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.OBJECTS;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.REPLACED_BY;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.SERVER_ONLY;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.SINCE_PREVIEW;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.STRING;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.allFields;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.className;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.nonNull;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.nullable;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.primitive;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.sentinel;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.sentinelName;
import static dev.opencollab.paletter.tools.codegen.component.ApiSource.unionName;

/**
 * Writes the value class of a component and of each of its objects: a final class with a private constructor, record-style
 * accessors, {@code equals}, {@code hashCode} and {@code toString}, factories and a builder ({@link BuilderSource}). Not a
 * record, so that a field Bedrock adds is a new accessor and builder setter, not a new canonical constructor (docs/api.md,
 * "Evolution").
 */
final class ValueClassSource {
    private final Comp c;
    private final ApiModel model;
    private final String since;

    ValueClassSource(Comp c, ApiModel model, String since) {
        this.c = c;
        this.model = model;
        this.since = since;
    }

    private ClassName name(ObjType o) {
        return o == c.root ? className(c) : className(c).nestedClass(o.name);
    }

    private TypeName typeName(Type t, boolean boxed) {
        return ApiSource.typeName(c, t, boxed);
    }

    private TypeName recordType(Field f) {
        return ApiSource.recordType(c, f);
    }

    TypeSpec valueClass(ObjType o, boolean top) {
        ClassName self = name(o);
        TypeSpec.Builder type = TypeSpec.classBuilder(self).addModifiers(Modifier.PUBLIC, Modifier.FINAL);
        if (!top) {
            type.addModifiers(Modifier.STATIC);
        }
        type.addJavadoc("$L", Docs.bedrock(model, ComponentDocs.typeDoc(model, c, o, top, since)));
        if (top) {
            annotations(type);
        }
        if (o.removed) {
            type.addAnnotation(FOR_REMOVAL);
        } else if (top && (c.deprecated() || c.replacement != null || c.rejected != null)) {
            type.addAnnotation(Deprecated.class);
        }
        if (top && c.replacement != null) {
            List<ClassName> generated = c.replacement.by().stream().map(model::component).filter(java.util.Objects::nonNull)
                .filter(s -> s.kind == c.kind).map(ApiSource::className).toList();
            if (!generated.isEmpty()) {
                AnnotationSpec.Builder replaced = AnnotationSpec.builder(REPLACED_BY);
                generated.forEach(s -> replaced.addMember("value", "$T.class", s));
                type.addAnnotation(replaced.build());
            }
        }
        if (top && !c.standalone) {
            type.addSuperinterface(ClassName.get(c.kind.pkg(), c.kind.iface()));
        }

        // Fields, the private constructor (the builder's only way in) with its validation, and the accessors
        if (top && !c.standalone) {
            type.addField(FieldSpec.builder(STRING, "NAME", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("The component name in add-on JSON.\n").initializer("$S", c.name).build());
        } else if (top) {
            type.addField(FieldSpec.builder(STRING, "NAME", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("The key of this object in the add-on JSON {@code description}; report entries name it.\n")
                .initializer("$S", c.standalonePath).build());
        }
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE);
        for (Field f : allFields(o)) {
            type.addField(FieldSpec.builder(recordType(f), f.java, Modifier.PRIVATE, Modifier.FINAL).build());
            constructor.addParameter(recordType(f), f.java);
        }
        constructor.addCode(validation(o));
        for (Field f : allFields(o)) {
            constructor.addStatement("this.$N = $N", f.java, f.java);
        }
        type.addMethod(constructor.build());
        accessors(type, o);
        valueMethods(type, self, allFields(o));

        if (top && !c.standalone) {
            type.addMethod(MethodSpec.methodBuilder("name").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                .returns(nonNull(STRING)).addStatement("return NAME").build());
        }
        factories(type, o, top);
        ClassName builder = self.nestedClass("Builder");
        type.addMethod(MethodSpec.methodBuilder("builder").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addJavadoc(allFields(o).stream().anyMatch(f -> f.required)
                ? "Creates a builder with every optional field at its default; required fields must be set before\n"
                    + "{@code build()}.\n\n@return a builder with the defaults applied\n"
                : "Creates a builder with every field at its default.\n\n@return a builder with the defaults applied\n")
            .returns(nonNull(builder)).addStatement("return new Builder()").build());
        MethodSpec.Builder toBuilder = MethodSpec.methodBuilder("toBuilder").addModifiers(Modifier.PUBLIC)
            .addJavadoc("Creates a builder holding this value's fields, to change some of them.\n\n@return a builder with this value's fields\n").returns(nonNull(builder))
            .addStatement("Builder builder = new Builder()");
        for (Field f : allFields(o)) {
            toBuilder.addStatement("builder.$N = $N", f.java, f.java);
        }
        type.addMethod(toBuilder.addStatement("return builder").build());
        type.addType(new BuilderSource(c, model).builder(o, self));
        if (top) {
            nestedTypes(type);
        }
        return type.build();
    }

    /** The annotations of a component's class from what the model knows of it (format, experiment, versions, server only). */
    private void annotations(TypeSpec.Builder type) {
        if (c.minFormat != null) {
            type.addAnnotation(AnnotationSpec.builder(BEDROCK_FORMAT).addMember("value", "$S", c.minFormat).build());
        }
        if (!c.toggles.isEmpty()) {
            type.addAnnotation(AnnotationSpec.builder(BEDROCK_EXPERIMENT).addMember("value", "$S", c.toggles.getFirst()).build());
        }
        if (c.sincePreview != null) {
            type.addAnnotation(AnnotationSpec.builder(SINCE_PREVIEW).addMember("value", "$S", c.sincePreview).build());
        }
        if (c.bedrockSince != null) {
            type.addAnnotation(AnnotationSpec.builder(BEDROCK_SINCE).addMember("value", "$S", c.bedrockSince).build());
        }
        // The first bundled version whose BDS rejects it, when an older one still took it (it is @Deprecated already)
        String until = c.rejected != null && c.rejected.since() != Integer.MIN_VALUE ? model.dataVersions.get(c.rejected.since()) : null;
        if (until != null) {
            type.addAnnotation(AnnotationSpec.builder(BEDROCK_UNTIL).addMember("value", "$S", until).build());
        }
        if (c.serverOnly != null) {
            AnnotationSpec.Builder serverOnly = AnnotationSpec.builder(SERVER_ONLY);
            if (c.serverOnly.since() != Integer.MIN_VALUE) {
                serverOnly.addMember("since", "$L", c.serverOnly.since());
            }
            if (c.serverOnly.until() != Integer.MAX_VALUE) {
                serverOnly.addMember("until", "$L", c.serverOnly.until());
            }
            type.addAnnotation(serverOnly.build());
        }
    }

    /** The accessors, the old names of renamed fields (delegates) and the stand-ins of removed fields. */
    private void accessors(TypeSpec.Builder type, ObjType o) {
        for (Field f : allFields(o)) {
            MethodSpec.Builder accessor = MethodSpec.methodBuilder(f.java).addModifiers(Modifier.PUBLIC).returns(recordType(f))
                .addJavadoc("$L\n@return the value\n", ComponentDocs.lines(Docs.bedrock(model, ComponentDocs.fieldDoc(f)))).addStatement("return $N", f.java);
            if (f.minFormat != null) {
                accessor.addAnnotation(AnnotationSpec.builder(BEDROCK_FORMAT).addMember("value", "$S", f.minFormat).build());
            }
            if (f.sincePreview != null) {
                accessor.addAnnotation(AnnotationSpec.builder(SINCE_PREVIEW).addMember("value", "$S", f.sincePreview).build());
            }
            type.addMethod(accessor.build());
        }
        for (ApiHistory.Renamed r : renames(model, c, o)) {
            type.addMethod(MethodSpec.methodBuilder(r.oldName()).addModifiers(Modifier.PUBLIC).addAnnotation(Deprecated.class)
                .returns(recordType(r.now()))
                .addJavadoc("Returns {@link #$L()}: this is its name from before Bedrock renamed its add-on JSON key to {@code $L}.\n\n"
                    + "@return the value of {@link #$L()}\n@deprecated renamed to {@link #$L()}\n", r.now().java, r.now().json, r.now().java, r.now().java)
                .addStatement("return $N()", r.now().java).build());
        }
        for (Field f : o.removedFields) {
            type.addMethod(MethodSpec.methodBuilder(f.java).addModifiers(Modifier.PUBLIC).addAnnotation(FOR_REMOVAL).returns(standInType(c, f))
                .addJavadoc("Bedrock no longer has add-on JSON {@code $L}: this stand-in returns $L, and its builder setter does nothing.\n\n"
                    + "@return $L\n@deprecated Bedrock no longer has it; it goes in the next major release\n",
                    f.json, standInWhat(f), standInWhat(f))
                .addStatement("return $L", standInValue(f)).build());
        }
    }

    /** The nested types of a component's class: its objects, open value classes, unions and the vector record. */
    private void nestedTypes(TypeSpec.Builder type) {
        for (ObjType nested : c.types.values()) {
            type.addType(valueClass(nested, false));
        }
        for (EnumType e : c.enums.values()) {
            type.addType(OpenValueSource.enumType(c, e));
        }
        for (UnionType u : model.unions.values()) {
            if (u.owner == c) {
                type.addType(UnionSource.union(u, since));
                type.addType(sentinel(sentinelName(unionName(u)), unionName(u), false));
            }
        }
        if (c.usesVec3) {
            // A record: three floats is the shape of a vector, not of anything Bedrock can extend
            ClassName vec3 = className(c).nestedClass("Vec3");
            type.addType(TypeSpec.recordBuilder("Vec3").addModifiers(Modifier.PUBLIC).addJavadoc("An add-on JSON vector {@code [x, y, z]}.\n\n@param x the x component\n@param y the y component\n@param z the z component\n")
                .recordConstructor(MethodSpec.constructorBuilder().addParameter(float.class, "x").addParameter(float.class, "y")
                    .addParameter(float.class, "z").build())
                .addMethod(MethodSpec.methodBuilder("of").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(nonNull(vec3))
                    .addJavadoc("Creates a vector.\n\n@param x the x component\n@param y the y component\n@param z the z component\n@return the vector\n")
                    .addParameter(float.class, "x").addParameter(float.class, "y").addParameter(float.class, "z")
                    .addStatement("return new $T(x, y, z)", vec3).build())
                .build());
        }
    }

    // ---- Renamed and removed fields ---------------------------------------------------------------------------------------

    /** The renamed fields of an object of the component ({@link ApiHistory#renames}): their old names delegate. */
    static List<ApiHistory.Renamed> renames(ApiModel model, Comp c, ObjType o) {
        List<ApiHistory.Renamed> out = new ArrayList<>();
        for (ApiHistory.Renamed r : model.renames) {
            if (r.c() == c && r.owner().path.equals(o.path)) {
                if (allFields(o).stream().anyMatch(f -> f.java.equals(r.oldName()))) {
                    throw new GenerationException(r.oldKey() + " was renamed to " + r.now().path + ", but its old name " + r.oldName()
                        + " is a field of " + o.name + " now; name one of them in api.json fields");
                }
                out.add(r);
            }
        }
        return out;
    }

    /** The type of a removed field's stand-in: the field's own, nullable where it had neither a default nor a primitive type. */
    static TypeName standInType(Comp c, Field f) {
        return primitive(f) || f.defaultValue != null ? ApiSource.recordType(c, f) : nullable(ApiSource.typeName(c, f.type, true));
    }

    /** What a removed field's stand-in returns: its default, else the zero of a primitive, else null. */
    private CodeBlock standInValue(Field f) {
        if (f.defaultValue != null) {
            return ApiSource.literal(c, f.type, f.defaultValue);
        }
        if (primitive(f)) {
            return switch (((Scalar) f.type).kind()) {
                case BOOL -> CodeBlock.of("false");
                case FLOAT -> CodeBlock.of("0.0f");
                default -> CodeBlock.of("0");
            };
        }
        return CodeBlock.of("null");
    }

    private static String standInWhat(Field f) {
        return f.defaultValue != null ? "its default, " + Docs.literal(f.defaultValue) : primitive(f) ? "the zero value" : "null";
    }

    /** {@code equals}, {@code hashCode} and {@code toString} with the semantics of a record of the same fields. */
    private void valueMethods(TypeSpec.Builder type, ClassName self, List<Field> fields) {
        List<CodeBlock> equal = new ArrayList<>();
        CodeBlock.Builder hash = CodeBlock.builder();
        CodeBlock.Builder string = CodeBlock.builder().add("return ");
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            TypeName t = typeName(f.type, nullable(f));
            String n = f.java;
            if (primitive(f) && t.equals(TypeName.FLOAT)) {
                equal.add(CodeBlock.of("$T.compare($N, other.$N) == 0", Float.class, n, n));
            } else if (primitive(f)) {
                equal.add(CodeBlock.of("$N == other.$N", n, n));
            } else {
                equal.add(CodeBlock.of("$T.equals($N, other.$N)", OBJECTS, n, n));
            }
            CodeBlock h = !primitive(f) ? CodeBlock.of("$T.hashCode($N)", OBJECTS, n)
                : t.equals(TypeName.FLOAT) ? CodeBlock.of("$T.hashCode($N)", Float.class, n)
                : t.equals(TypeName.BOOLEAN) ? CodeBlock.of("$T.hashCode($N)", Boolean.class, n) : CodeBlock.of("$N", n);
            hash.addStatement(i == 0 ? "int h = $L" : "h = 31 * h + $L", h);
            string.add(i == 0 ? "$S + $N" : " + $S + $N", (i == 0 ? self.simpleName() + "[" : ", ") + n + "=", n);
        }
        string.add(fields.isEmpty() ? "$S" : " + $S", fields.isEmpty() ? self.simpleName() + "[]" : "]");
        type.addMethod(MethodSpec.methodBuilder("equals").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC).returns(TypeName.BOOLEAN)
            .addParameter(Object.class, "o")
            .addStatement(fields.isEmpty() ? CodeBlock.of("return o instanceof $T", self)
                : CodeBlock.of("return this == o || o instanceof $T other\n&& $L", self, CodeBlock.join(equal, "\n&& "))).build());
        type.addMethod(MethodSpec.methodBuilder("hashCode").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC).returns(TypeName.INT)
            .addCode(fields.isEmpty() ? CodeBlock.of("return 0;\n") : hash.addStatement("return h").build()).build());
        type.addMethod(MethodSpec.methodBuilder("toString").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC).returns(STRING)
            .addStatement(string.build()).build());
    }

    // ---- Validation ----------------------------------------------------------------------------------------------------

    /**
     * The checks of the private constructor: required values not null, lists and maps copied (so the value is immutable),
     * ranges, allowed sets and the relations between fields.
     */
    private CodeBlock validation(ObjType o) {
        CodeBlock.Builder body = CodeBlock.builder();
        String owner = c.className + (o == c.root ? "" : "." + o.name);
        for (Field f : allFields(o)) {
            String name = owner + "." + f.java;
            boolean nullable = nullable(f);
            boolean reference = !(f.type instanceof Scalar s && s.kind() != ScalarKind.STRING) || nullable;
            if (f.type instanceof ListOf) {
                if (nullable) {
                    body.addStatement("$N = $N == null ? null : $T.copyOf($N)", f.java, f.java, LIST, f.java);
                } else {
                    body.addStatement("$N = $T.copyOf($T.requireNonNull($N, $S))", f.java, LIST, OBJECTS, f.java, name);
                }
            } else if (f.type instanceof MapOf || f.type instanceof States) {
                if (nullable) {
                    body.addStatement("$N = $N == null ? null : $T.copy($N, $S)", f.java, f.java, CHECK, f.java, name);
                } else {
                    body.addStatement("$N = $T.copy($N, $S)", f.java, CHECK, f.java, name);
                }
            } else if (reference && !nullable) {
                body.addStatement("$T.requireNonNull($N, $S)", OBJECTS, f.java, name);
            }
            if (!f.reservedKeys.isEmpty()) {
                body.beginControlFlow("for ($T key : $T.of($L))", STRING, LIST, String.join(", ", f.reservedKeys.stream()
                    .map(k -> "\"" + k + "\"").toList()));
                body.beginControlFlow("if ($N.containsKey(key))", f.java);
                body.addStatement("throw new $T($S + key + $S)", IllegalArgumentException.class, name + " has the key ",
                    ", which has a field of its own");
                body.endControlFlow().endControlFlow();
            }
            if (f.type instanceof States) {
                body.addStatement("$T.stateValues($N, $S)", CHECK, f.java, name);
            }
            if (f.type instanceof UnionRef && (f.min != null || f.max != null || f.multipleOf != null || f.allowed != null)) {
                throw new GenerationException(name + " is a union with a limit; a limit of one form needs a check of that variant");
            }
            if (f.min != null || f.max != null) {
                if (nullable) {
                    body.beginControlFlow("if ($N != null)", f.java);
                }
                body.addStatement("$T.range($S, $N, $L, $L)", CHECK, name, f.java, bound(f.min), bound(f.max));
                if (nullable) {
                    body.endControlFlow();
                }
            }
            if (f.multipleOf != null) {
                if (nullable) {
                    body.beginControlFlow("if ($N != null)", f.java);
                }
                List<CodeBlock> values = f.type instanceof Vec3
                    ? List.of(CodeBlock.of("$N.x()", f.java), CodeBlock.of("$N.y()", f.java), CodeBlock.of("$N.z()", f.java))
                    : List.of(CodeBlock.of("$N", f.java));
                for (CodeBlock value : values) {
                    body.addStatement("$T.multipleOf($S, $L, $L)", CHECK, name, value, bound(f.multipleOf));
                }
                if (nullable) {
                    body.endControlFlow();
                }
            }
            if (f.allowed != null) {
                if (nullable) {
                    body.beginControlFlow("if ($N != null)", f.java);
                }
                body.addStatement("$T.oneOf($S, $N, $L)", CHECK, name, f.java, String.join(", ", f.allowed.stream().map(Docs::number).toList()));
                if (nullable) {
                    body.endControlFlow();
                }
            }
            if (f.notBelow != null) {
                List<String> unset = new ArrayList<>();
                for (Field side : List.of(f.notBelow, f)) {
                    if (nullable(side)) {
                        unset.add(side.java + " != null");
                    }
                }
                if (!unset.isEmpty()) {
                    body.beginControlFlow("if ($L)", String.join(" && ", unset));
                }
                body.addStatement("$T.order($S, $S, $N, $S, $N)", CHECK, owner, f.notBelow.java, f.notBelow.java, f.java, f.java);
                if (!unset.isEmpty()) {
                    body.endControlFlow();
                }
            }
            if (f.conflictsWith != null) {
                body.addStatement("$T.conflict($S, $S, $L, $S, $N, $S)", CHECK, owner, f.java,
                    (nullable ? f.java + " != null && " : "") + "!" + f.java + ".isEmpty()", f.conflictsWith.java, f.conflictsWith.java, f.conflictValue);
            }
        }
        return body.build();
    }

    private static String bound(Double d) {
        return d == null ? "null" : Docs.number(d) + (d == Math.rint(d) ? ".0" : "");
    }

    // ---- Factories -------------------------------------------------------------------------------------------------------

    private void factories(TypeSpec.Builder type, ObjType o, boolean top) {
        Set<String> signatures = new LinkedHashSet<>();
        if (top) {
            for (Shorthand s : c.shorthands) {
                shorthand(type, o, s.field(), s.jsonType(), signatures);
            }
        }
        if (top) {
            for (Map.Entry<String, String> form : c.elementForms.entrySet()) {
                elementForm(type, o, form.getKey(), form.getValue(), signatures);
            }
        }
        if (o.shorthand != null) {
            shorthand(type, o, o.shorthand, o.shorthandType, signatures);
        }
        if (o.blockDescriptor) {
            Field name = o.fields.stream().filter(f -> "name".equals(f.json)).findFirst().orElse(null);
            Field states = o.fields.stream().filter(f -> f.type instanceof States).findFirst().orElse(null);
            if (name != null) {
                type.addMethod(MethodSpec.methodBuilder("of").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .addJavadoc("Creates a descriptor matching every state of a block.\n\n@param block the vanilla or custom block\n@return a descriptor matching every state of a block\n")
                    .addParameter(nonNull(BLOCK_TYPE), "block").returns(nonNull(name(o)))
                    .addStatement("return builder().$N(block.identifier()).build()", name.java).build());
                if (states != null) {
                    type.addMethod(MethodSpec.methodBuilder("of").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .addJavadoc("Creates a descriptor matching one block state.\n\n@param state the block state\n@return a descriptor matching one block state (its block and every state value)\n")
                        .addParameter(nonNull(BLOCK_STATE), "state").returns(nonNull(name(o)))
                        .addStatement("return builder().$N(state.type().identifier()).$N(state.values()).build()", name.java, states.java)
                        .build());
                }
            }
        }
    }

    /** {@code of(Entry)}: the object form of a component whose value is a list of objects (api.json forms {@code listElementOf}). */
    private void elementForm(TypeSpec.Builder type, ObjType o, String jsonType, String list, Set<String> signatures) {
        Field target = o.fields.stream().filter(f -> f.java.equals(list)).findFirst().orElseThrow();
        ClassName entry = className(c).nestedClass(((ObjRef) ((ListOf) target.type).element()).type().name);
        if (!signatures.add(entry.toString())) {
            return;
        }
        StringBuilder doc = new StringBuilder("The $L form: one entry, the same as a list of that entry in {@code $L}.");
        ApiModel.Window rejecting = c.windows.stream().filter(w -> w.sent() && w.form(jsonType) != null
            && w.form(jsonType).get("status").getAsString().equals("rejected")).findFirst().orElse(null);
        if (rejecting != null) {
            Map.Entry<Integer, String> first = rejecting.since() == Integer.MIN_VALUE ? null : model.captureVersions.floorEntry(rejecting.since());
            doc.append(" BDS rejects this form since ")
                .append(first == null ? "protocol " + rejecting.since() : Docs.release(model, first))
                .append("; the list is accepted by every captured BDS.");
        }
        type.addMethod(MethodSpec.methodBuilder("of").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addJavadoc(Docs.bedrock(model, doc.toString()) + "\n\n@param entry the one entry\n@return the value with everything else at its default\n", jsonType, target.java)
            .addParameter(nonNull(entry), "entry").returns(nonNull(name(o)))
            .addStatement("return builder().$N(entry).build()", target.java).build());
    }

    private void shorthand(TypeSpec.Builder type, ObjType o, Field target, String jsonType, Set<String> signatures) {
        TypeName param = switch (jsonType) {
            case "boolean" -> TypeName.BOOLEAN;
            case "integer" -> TypeName.INT;
            case "number" -> TypeName.FLOAT;
            default -> STRING;
        };
        CodeBlock value = CodeBlock.of("value");
        if (target.type instanceof EnumRef || target.type instanceof Scalar) {
            param = typeName(target.type, false);
        } else if (target.type instanceof ListOf l && (l.element() instanceof Scalar || l.element() instanceof EnumRef)) {
            param = typeName(l.element(), false);
            value = CodeBlock.of("$T.of(value)", LIST);
        } else if (target.type instanceof ObjRef r && r.type().shorthand != null) {
            value = CodeBlock.of("$T.of(value)", className(c).nestedClass(r.type().name));
        } else {
            return;
        }
        if (!signatures.add(param.toString())) {
            return;
        }
        type.addMethod(MethodSpec.methodBuilder("of").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addJavadoc("The $L shorthand: sets {@code $L}.\n\n@param value the value of that field\n@return the value with everything else at its default\n", jsonType,
                target.json == null ? target.java : target.json)
            .addParameter(param.isPrimitive() ? param : nonNull(param), "value").returns(nonNull(name(o)))
            .addStatement("return builder().$N($L).build()", target.java, value).build());
    }
}
