package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ComponentRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.EnumRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Field;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Kind;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ListOf;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.MapOf;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Scalar;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ScalarKind;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.States;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Type;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.UnionRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.UnionType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Variant;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Vec3;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes the public component API with JavaPoet: per component its value class ({@link ValueClassSource}, with
 * {@link BuilderSource}, {@link OpenValueSource} and {@link UnionSource}), the top-level unions and the sealed component
 * interfaces. Holds what those emitters share: the generated file, the annotations and the Java type of a model type.
 */
final class ApiSource {
    /** The file comment of every generated file (also how {@link Main} recognizes files it owns). */
    static final String HEADER = Main.GENERATOR + " from data/model. Do not edit; see docs/data-pipeline.md.\n"
        + "Documentation text is derived from the Minecraft Creator documentation by Microsoft (CC BY 4.0); see\n"
        + "data/model/ATTRIBUTION.md.";
    static final ClassName NON_NULL = ClassName.get("org.checkerframework.checker.nullness.qual", "NonNull");
    static final ClassName NULLABLE = ClassName.get("org.checkerframework.checker.nullness.qual", "Nullable");
    /** The hand-written value of a Molang expression field ({@link ApiModel.MolangRef}). */
    static final ClassName MOLANG = ClassName.get("dev.opencollab.paletter", "Molang");
    static final ClassName BEDROCK_FORMAT = ClassName.get("dev.opencollab.paletter.annotation", "BedrockFormat");
    /** The shared vector of every vec3 field (hand-written, next to Molang). */
    static final ClassName VEC3 = ClassName.get("dev.opencollab.paletter", "Vec3");
    static final ClassName BEDROCK_EXPERIMENT = ClassName.get("dev.opencollab.paletter.annotation", "BedrockExperiment");
    static final ClassName SERVER_ONLY = ClassName.get("dev.opencollab.paletter.annotation", "ServerOnly");
    static final ClassName SINCE_PREVIEW = ClassName.get("dev.opencollab.paletter.annotation", "SincePreview");
    static final ClassName BEDROCK_SINCE = ClassName.get("dev.opencollab.paletter.annotation", "BedrockSince");
    static final ClassName BEDROCK_UNTIL = ClassName.get("dev.opencollab.paletter.annotation", "BedrockUntil");
    static final ClassName REPLACED_BY = ClassName.get("dev.opencollab.paletter.annotation", "ReplacedBy");
    static final ClassName CHECK = ClassName.get("dev.opencollab.paletter.internal.component", "Check");
    static final ClassName ITEM_TYPE = ClassName.get("dev.opencollab.paletter.vanilla", "BedrockItemType");
    static final ClassName BLOCK_TYPE = ClassName.get("dev.opencollab.paletter.vanilla", "BedrockBlockType");
    static final ClassName BLOCK_STATE = ClassName.get("dev.opencollab.paletter.vanilla", "BedrockBlockState");
    static final ClassName LIST = ClassName.get(List.class);
    static final ClassName MAP = ClassName.get(Map.class);
    static final ClassName OBJECTS = ClassName.get("java.util", "Objects");
    static final ClassName STRING = ClassName.get(String.class);

    /** {@code @Deprecated(forRemoval = true)}: what left the model, until its history entry is deleted in a major release. */
    static final AnnotationSpec FOR_REMOVAL = AnnotationSpec.builder(Deprecated.class).addMember("forRemoval", "true").build();

    private ApiSource() {
    }

    static String file(String pkg, TypeSpec type) {
        return JavaFile.builder(pkg, type).addFileComment("$L", HEADER).indent("    ").skipJavaLangImports(true).build().toString();
    }

    static void write(ApiModel model, String since, Map<String, String> files) {
        for (Comp c : allComponents(model)) {
            files.put(c.kind.pkg().replace('.', '/') + "/" + c.className + ".java", file(c.kind.pkg(), new ValueClassSource(c, model, since).valueClass(c.root, true)));
        }
        for (UnionType u : model.unions.values()) {
            if (u.owner == null) {
                files.put(u.kind.pkg().replace('.', '/') + "/" + u.name + ".java", file(u.kind.pkg(), UnionSource.union(u, since)));
                ClassName future = sentinelName(unionName(u));
                files.put(u.kind.pkg().replace('.', '/') + "/" + future.simpleName() + ".java", file(u.kind.pkg(), sentinel(future, unionName(u), false)));
            }
        }
        for (Kind kind : Kind.values()) {
            List<Comp> members = allComponents(model).stream().filter(c -> c.kind == kind && !c.standalone).toList();
            files.put(kind.pkg().replace('.', '/') + "/" + kind.iface() + ".java", file(kind.pkg(), iface(kind, members, model, since)));
            ClassName iface = ClassName.get(kind.pkg(), kind.iface());
            files.put(kind.pkg().replace('.', '/') + "/" + sentinelName(iface).simpleName() + ".java", file(kind.pkg(), sentinel(sentinelName(iface), iface, true)));
        }
    }

    /** @return the generated components and the stand-ins of those that left the model ({@link StandIns}) */
    static List<Comp> allComponents(ApiModel model) {
        List<Comp> all = new ArrayList<>(model.components);
        all.addAll(model.removedComponents);
        return all;
    }

    /**
     * The class that keeps a sealed type open: a sealed type Bedrock can add variants to also permits it. It is not
     * public, so no {@code case} can name it, and a switch over the sealed type outside its package needs a
     * {@code default}, which the compiler enforces (docs/api.md, "Evolution"). It has no instances.
     */
    static ClassName sentinelName(ClassName sealed) {
        String name = "Future" + sealed.simpleName();
        return sealed.enclosingClassName() == null ? ClassName.get(sealed.packageName(), name)
            : sealed.topLevelClassName().nestedClass(name);
    }

    static TypeSpec sentinel(ClassName self, ClassName sealed, boolean named) {
        boolean nested = self.enclosingClassName() != null;
        TypeSpec.Builder type = TypeSpec.classBuilder(self).addModifiers(Modifier.FINAL).addSuperinterface(sealed)
            .addJavadoc("Never instantiated: it makes a switch over {@code $L} need a {@code default}, as Bedrock adds\n"
                + "variants in minor releases (docs/api.md, \"Evolution\").\n", String.join(".", sealed.simpleNames()))
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        if (nested) {
            type.addModifiers(Modifier.PRIVATE, Modifier.STATIC);
        }
        if (named) {
            type.addMethod(MethodSpec.methodBuilder("name").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                .returns(STRING.annotated(AnnotationSpec.builder(NON_NULL).build()))
                .addStatement("throw new $T()", AssertionError.class).build());
        }
        return type.build();
    }

    /** The sealed sub-interface of the hand-written wire-only components of a kind (items and blocks have them). */
    static String wireOnly(Kind kind) {
        return kind == Kind.BLOCK ? "WireOnlyBlockComponent" : "WireOnlyItemComponent";
    }

    static ClassName className(Comp c) {
        return ClassName.get(c.kind.pkg(), c.className);
    }

    // ---- Interfaces ------------------------------------------------------------------------------------------------

    private static TypeSpec iface(Kind kind, List<Comp> members, ApiModel model, String since) {
        String what = switch (kind) {
            case ITEM -> "A custom item component";
            case BLOCK -> "A custom block component";
            case TRAIT -> "A custom block trait";
        };
        StringBuilder doc = new StringBuilder(what).append(", mirroring Bedrock's add-on JSON: one class per component, generated\n"
            + "from the component model in {@code data/model} (creator docs plus what Bedrock Dedicated Server sends). Paletter\n"
            + "compiles them to the wire format of each palette's protocol.\n");
        List<String> omitted = new ArrayList<>();
        String prefix = kind == Kind.TRAIT ? "block:trait:" : kind == Kind.ITEM ? "item:" : "block:minecraft:";
        model.omitted.forEach((id, reason) -> {
            if (id.startsWith(prefix) && (kind == Kind.TRAIT || !id.startsWith("block:trait:"))) {
                StringBuilder item = new StringBuilder("    <li>{@code " + id.substring(id.indexOf(':') + 1) + "}: " + Docs.escape(reason));
                Replacements.Replacement replacement = model.replacements.get(id);
                if (replacement != null) {
                    item.append("; ").append(ComponentDocs.replacementText(model, kind, replacement));
                }
                String url = Docs.learnUrl(model.pages.get(id));
                if (url != null) {
                    item.append(" (<a href=\"").append(url).append("\">Microsoft Learn</a>)");
                }
                omitted.add(item.append("</li>\n").toString());
            }
        });
        if (!omitted.isEmpty()) {
            doc.append("<p>\nDocumented but not generated:\n<ul>\n");
            omitted.forEach(doc::append);
            doc.append("</ul>\n");
        }
        if (kind == Kind.ITEM) {
            doc.append("<p>\nHand-written wire-only components, for fields with no add-on JSON source: see {@link WireOnlyItemComponent}.\n");
        }
        doc.append("<p>\nBedrock adds components in minor releases, so a {@code switch} over it needs a {@code default}; the compiler\n"
            + "enforces it (docs/api.md, \"Evolution\").\n");
        String list = Docs.learnUrl(model.lists.get(kind == Kind.ITEM ? "item" : kind == Kind.BLOCK ? "block" : "trait"));
        if (list != null) {
            doc.append("\n@see <a href=\"").append(list).append("\">Microsoft Learn: ")
                .append(kind == Kind.ITEM ? "item components" : kind == Kind.BLOCK ? "block components" : "block states and traits")
                .append("</a>");
        }
        doc.append("\n@since ").append(since).append('\n');
        TypeSpec.Builder type = TypeSpec.interfaceBuilder(kind.iface()).addModifiers(Modifier.PUBLIC, Modifier.SEALED)
            .addJavadoc("$L", Docs.bedrock(model, doc.toString()));
        members.stream().map(ApiSource::className).sorted((a, b) -> a.simpleName().compareTo(b.simpleName())).forEach(type::addPermittedSubclass);
        if (kind == Kind.ITEM || kind == Kind.BLOCK) {
            type.addPermittedSubclass(ClassName.get(kind.pkg(), wireOnly(kind)));
        }
        type.addPermittedSubclass(sentinelName(ClassName.get(kind.pkg(), kind.iface())));
        type.addMethod(MethodSpec.methodBuilder("name").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addJavadoc("Returns the component name in add-on JSON.\n\n@return the name, e.g. {@code minecraft:durability}\n")
            .returns(STRING.annotated(AnnotationSpec.builder(NON_NULL).build())).build());
        return type.build();
    }

    // ---- Java types --------------------------------------------------------------------------------------------------

    static List<Field> allFields(ObjType o) {
        return o.fields;
    }

    static boolean nullable(Field f) {
        return f.nullable();
    }

    static boolean primitive(Field f) {
        return !nullable(f) && f.type instanceof Scalar s && s.kind() != ScalarKind.STRING;
    }

    /** The type of a field in its value class: primitive, or annotated {@code @NonNull} or {@code @Nullable}. */
    static TypeName recordType(Comp c, Field f) {
        TypeName t = typeName(c, f.type, nullable(f));
        if (primitive(f)) {
            return t;
        }
        return nullable(f) ? nullable(t) : nonNull(t);
    }

    static TypeName nonNull(TypeName t) {
        return t.annotated(AnnotationSpec.builder(NON_NULL).build());
    }

    static TypeName nullable(TypeName t) {
        return t.annotated(AnnotationSpec.builder(NULLABLE).build());
    }

    /** The Java type of a model type; scalars boxed if {@code boxed}. */
    static TypeName typeName(Comp c, Type t, boolean boxed) {
        return switch (t) {
            case Scalar s -> switch (s.kind()) {
                case BOOL -> boxed ? ClassName.get(Boolean.class) : TypeName.BOOLEAN;
                case INT -> boxed ? ClassName.get(Integer.class) : TypeName.INT;
                case FLOAT -> boxed ? ClassName.get(Float.class) : TypeName.FLOAT;
                case STRING -> STRING;
            };
            case EnumRef e -> e.type().external != null ? ClassName.bestGuess(e.type().external) : className(c).nestedClass(e.type().name);
            case ObjRef r -> className(c).nestedClass(r.type().name);
            case Vec3 v -> VEC3;
            case ListOf l -> ParameterizedTypeName.get(LIST, typeName(c, l.element(), true));
            case MapOf m -> ParameterizedTypeName.get(MAP, STRING, typeName(c, m.value(), true));
            case States s -> ParameterizedTypeName.get(MAP, STRING, ClassName.get(Object.class));
            case ComponentRef r -> ClassName.get(c.kind.pkg(), r.className());
            case UnionRef u -> unionName(u.union());
            case ApiModel.MolangRef m -> MOLANG;
        };
    }

    static ClassName unionName(UnionType u) {
        return u.owner != null ? className(u.owner).nestedClass(u.name) : ClassName.get(u.kind.pkg(), u.name);
    }

    // ---- Literals ----------------------------------------------------------------------------------------------------

    /** A JSON value (a default) as a Java expression of the field's type. */
    static CodeBlock literal(Comp c, Type t, JsonElement v) {
        return switch (t) {
            case Scalar s -> switch (s.kind()) {
                case BOOL -> CodeBlock.of("$L", v.getAsBoolean());
                case INT -> CodeBlock.of("$L", v.getAsLong() == v.getAsDouble() ? v.getAsLong() : (long) v.getAsDouble());
                case FLOAT -> CodeBlock.of("$L", Docs.floatLiteral(v.getAsDouble()));
                case STRING -> CodeBlock.of("$S", v.getAsString());
            };
            case EnumRef e -> {
                int i = e.type().values.indexOf(v.getAsString());
                if (i < 0) {
                    throw new GenerationException(c.id + ": the default " + v + " is no documented value of " + e.type().name + " "
                        + e.type().values + "; fix the docs default with an erratum (fixtures.json) or api.json noDefaults");
                }
                yield CodeBlock.of("$T.$L", typeName(c, e, false), e.type().constants.get(i));
            }
            case Vec3 vec -> {
                List<String> xyz = new ArrayList<>();
                if (v.isJsonArray()) {
                    v.getAsJsonArray().forEach(x -> xyz.add(Docs.floatLiteral(x.getAsDouble())));
                } else {
                    for (String k : List.of("x", "y", "z")) {
                        xyz.add(Docs.floatLiteral(v.getAsJsonObject().has(k) ? v.getAsJsonObject().get(k).getAsDouble() : 0));
                    }
                }
                yield CodeBlock.of("new $T($L)", VEC3, String.join(", ", xyz));
            }
            case ListOf l -> {
                List<CodeBlock> items = new ArrayList<>();
                v.getAsJsonArray().forEach(x -> items.add(literal(c, l.element(), x)));
                yield CodeBlock.of("$T.of($L)", LIST, CodeBlock.join(items, ", "));
            }
            case MapOf m -> {
                List<CodeBlock> items = new ArrayList<>();
                v.getAsJsonObject().entrySet().forEach(e -> items.add(CodeBlock.of("$S, $L", e.getKey(), literal(c, m.value(), e.getValue()))));
                yield IndexSource.mapOf(items);
            }
            case States s -> CodeBlock.of("$T.of()", MAP);
            case ComponentRef r -> CodeBlock.of("null");
            case ApiModel.MolangRef m -> CodeBlock.of("$T.of($S)", MOLANG, v.isJsonObject() ? v.getAsJsonObject().get("expression").getAsString() : v.getAsString());
            case UnionRef u -> {
                Variant form = u.union().variants.stream().filter(x -> Defaults.fits(x.type, v)).findFirst()
                    .orElseThrow(() -> new GenerationException("the default " + v + " fits no form of the union " + u.union().name));
                yield CodeBlock.of("$T.$N($L)", unionName(u.union()), form.factory(), literal(c, form.type, v));
            }
            case ObjRef r -> {
                CodeBlock.Builder s = CodeBlock.builder().add("$T.builder()", className(c).nestedClass(r.type().name));
                JsonObject o = v.getAsJsonObject();
                for (Field f : r.type().fields) {
                    if (o.has(f.json)) {
                        s.add(".$N($L)", f.java, literal(c, f.type, o.get(f.json)));
                    }
                }
                yield s.add(".build()").build();
            }
        };
    }
}
