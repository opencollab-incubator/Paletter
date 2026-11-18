package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ComponentRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.EnumRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.EnumType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Field;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Kind;
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
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Variant;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Vec3;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes the public component types with JavaPoet: one record per component with nested records, enums and builders,
 * and the sealed component interfaces.
 */
final class ApiSource {
    /** The file comment of every generated file (also how {@link Main} recognizes files it owns). */
    static final String HEADER = Main.GENERATOR + " from data/model. Do not edit; see docs/data-pipeline.md.\n"
        + "Documentation text is derived from the Minecraft Creator documentation by Microsoft (CC BY 4.0); see\n"
        + "data/model/ATTRIBUTION.md.";
    static final ClassName NON_NULL = ClassName.get("org.checkerframework.checker.nullness.qual", "NonNull");
    static final ClassName NULLABLE = ClassName.get("org.checkerframework.checker.nullness.qual", "Nullable");
    static final ClassName BEDROCK_FORMAT = ClassName.get("dev.opencollab.paletter.annotation", "BedrockFormat");
    static final ClassName BEDROCK_EXPERIMENT = ClassName.get("dev.opencollab.paletter.annotation", "BedrockExperiment");
    static final ClassName SERVER_ONLY = ClassName.get("dev.opencollab.paletter.annotation", "ServerOnly");
    static final ClassName SINCE_PREVIEW = ClassName.get("dev.opencollab.paletter.annotation", "SincePreview");
    static final ClassName BEDROCK_SINCE = ClassName.get("dev.opencollab.paletter.annotation", "BedrockSince");
    static final ClassName REPLACED_BY = ClassName.get("dev.opencollab.paletter.annotation", "ReplacedBy");
    static final ClassName CHECK = ClassName.get("dev.opencollab.paletter.internal.component", "Check");
    static final ClassName ITEM_TYPE = ClassName.get("dev.opencollab.paletter.vanilla", "BedrockItemType");
    static final ClassName BLOCK_TYPE = ClassName.get("dev.opencollab.paletter.vanilla", "BedrockBlockType");
    static final ClassName BLOCK_STATE = ClassName.get("dev.opencollab.paletter.vanilla", "BedrockBlockState");
    static final ClassName LIST = ClassName.get(List.class);
    static final ClassName MAP = ClassName.get(Map.class);
    static final ClassName OBJECTS = ClassName.get("java.util", "Objects");
    static final ClassName STRING = ClassName.get(String.class);

    private final Comp c;
    private final ApiModel model;
    private final String since;

    private ApiSource(Comp c, ApiModel model, String since) {
        this.c = c;
        this.model = model;
        this.since = since;
    }

    static String file(String pkg, TypeSpec type) {
        return JavaFile.builder(pkg, type).addFileComment("$L", HEADER).indent("    ").skipJavaLangImports(true).build().toString();
    }

    static void write(ApiModel model, String since, Map<String, String> files) {
        for (Comp c : model.components) {
            files.put(c.kind.pkg().replace('.', '/') + "/" + c.className + ".java", file(c.kind.pkg(), new ApiSource(c, model, since).record(c.root, true)));
        }
        for (UnionType u : model.unions.values()) {
            if (u.owner == null) {
                files.put(u.kind.pkg().replace('.', '/') + "/" + u.name + ".java", file(u.kind.pkg(), union(u, since)));
            }
        }
        for (Kind kind : Kind.values()) {
            List<Comp> members = model.components.stream().filter(c -> c.kind == kind && !c.standalone).toList();
            files.put(kind.pkg().replace('.', '/') + "/" + kind.iface() + ".java", file(kind.pkg(), iface(kind, members, model, since)));
        }
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
        StringBuilder doc = new StringBuilder(what).append(", mirroring Bedrock's add-on JSON: one record per component, generated\n"
            + "from the component model in {@code data/model} (creator docs plus what Bedrock Dedicated Server sends). Paletter\n"
            + "compiles them to the wire format of each palette's protocol.\n");
        List<String> omitted = new ArrayList<>();
        String prefix = kind == Kind.TRAIT ? "block:trait:" : kind == Kind.ITEM ? "item:" : "block:minecraft:";
        model.omitted.forEach((id, reason) -> {
            if (id.startsWith(prefix) && (kind == Kind.TRAIT || !id.startsWith("block:trait:"))) {
                StringBuilder item = new StringBuilder("    <li>{@code " + id.substring(id.indexOf(':') + 1) + "}: " + Docs.escape(reason));
                Replacements.Replacement replacement = model.replacements.get(id);
                if (replacement != null) {
                    item.append("; ").append(replacementText(model, kind, replacement));
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
        String list = Docs.learnUrl(model.lists.get(kind == Kind.ITEM ? "item" : kind == Kind.BLOCK ? "block" : "trait"));
        if (list != null) {
            doc.append("\n@see <a href=\"").append(list).append("\">Microsoft Learn: ")
                .append(kind == Kind.ITEM ? "item components" : kind == Kind.BLOCK ? "block components" : "block states and traits")
                .append("</a>");
        }
        doc.append("\n@since ").append(since).append('\n');
        TypeSpec.Builder type = TypeSpec.interfaceBuilder(kind.iface()).addModifiers(Modifier.PUBLIC, Modifier.SEALED)
            .addJavadoc("$L", doc.toString());
        members.stream().map(ApiSource::className).sorted((a, b) -> a.simpleName().compareTo(b.simpleName())).forEach(type::addPermittedSubclass);
        if (kind == Kind.ITEM || kind == Kind.BLOCK) {
            type.addPermittedSubclass(ClassName.get(kind.pkg(), wireOnly(kind)));
        }
        type.addMethod(MethodSpec.methodBuilder("name").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addJavadoc("Returns the component name in add-on JSON.\n\n@return the name, e.g. {@code minecraft:durability}\n")
            .returns(STRING.annotated(AnnotationSpec.builder(NON_NULL).build())).build());
        if (members.isEmpty()) {
            ClassName none = ClassName.get(kind.pkg(), kind.iface(), "None");
            type.addPermittedSubclass(none);
            type.addType(TypeSpec.classBuilder("None").addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("Placeholder of a kind with no generated component; it has no instances.\n").addSuperinterface(ClassName.get(kind.pkg(), kind.iface()))
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
                .addMethod(MethodSpec.methodBuilder("name").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                    .returns(STRING.annotated(AnnotationSpec.builder(NON_NULL).build())).addStatement("return $S", "").build())
                .build());
        }
        return type.build();
    }

    /** "replaced by {@link A}, {@code b}: evidence": generated successors of the same kind are linked, others named. */
    private static String replacementText(ApiModel model, Kind kind, Replacements.Replacement r) {
        List<String> by = new ArrayList<>();
        for (String id : r.by()) {
            Comp successor = model.component(id);
            by.add(successor != null && successor.kind == kind ? "{@link " + successor.className + "}"
                : "{@code " + id.substring(id.indexOf(':') + 1) + "}");
        }
        return "replaced by " + String.join(", ", by) + (r.hand() ? " (stated in fixtures.json: " : " (") + Docs.escape(r.evidence()) + ")";
    }

    private String replacementDoc(Replacements.Replacement r) {
        return replacementText(model, c.kind, r);
    }

    /** The paragraph that says in which protocols BDS accepts the component without sending it. */
    private String serverOnlyDoc() {
        ApiModel.Window w = c.serverOnly;
        if (!w.bounded()) {
            String versions = String.join(", ", model.captureVersions.values());
            return String.join("\n", Docs.wrap("Server only: BDS accepts it in packs but sends nothing to clients"
                + (versions.isEmpty() ? "" : " (BDS " + versions + ")") + ", so it has no client effect. Palettes leave it out without a warning.")) + "\n";
        }
        String range = w.since() == Integer.MIN_VALUE ? "below protocol " + w.until()
            : w.until() == Integer.MAX_VALUE ? "from protocol " + w.since() : "from protocol " + w.since() + " below " + w.until();
        List<String> captured = new ArrayList<>();
        model.captureVersions.forEach((protocol, bds) -> {
            if (protocol >= w.since() && protocol < w.until()) {
                captured.add(bds);
            }
        });
        StringBuilder text = new StringBuilder("Server only ").append(range).append(": BDS accepts it in packs but sends nothing to clients there");
        if (!captured.isEmpty()) {
            text.append(" (BDS ").append(String.join(", ", captured)).append(")");
        }
        text.append(", so those palettes leave it out and add a report warning.");
        Map.Entry<Integer, String> sent = w.until() == Integer.MAX_VALUE ? null : model.captureVersions.ceilingEntry(w.until());
        if (sent != null) {
            text.append(" Sent from protocol ").append(w.until()).append(" (BDS ").append(sent.getValue()).append(").");
        }
        return String.join("\n", Docs.wrap(text.toString())) + "\n";
    }

    /** The sentence that says since when BDS rejects the component (from the captures), with BDS's own message. */
    private String rejectedText() {
        ApiModel.Window w = c.rejected;
        String since;
        if (model.captureVersions.isEmpty()) {
            since = "rejected by BDS";
        } else if (w.since() == Integer.MIN_VALUE) {
            Map.Entry<Integer, String> oldest = model.captureVersions.firstEntry();
            since = "rejected by every captured BDS (oldest: " + oldest.getValue() + ", protocol " + oldest.getKey() + ")";
        } else {
            Map.Entry<Integer, String> first = model.captureVersions.floorEntry(w.since());
            since = "rejected by BDS since " + (first == null ? "protocol " + w.since() : first.getValue() + " (protocol " + first.getKey() + ")");
        }
        return since + (w.mapping().has("note") ? ": " + w.mapping().get("note").getAsString() : "");
    }

    /** What the gating captures verify of a component: where BDS does not take it without experiments, where it does, and where it never does. */
    static String gatingDoc(List<Gating.Result> results, boolean serverOnly) {
        List<String> gated = results.stream().filter(r -> r.gated(serverOnly)).map(Gating.Result::build).toList();
        List<String> open = results.stream().filter(r -> r.without().equals(Gating.ACCEPTED)).map(Gating.Result::build).toList();
        List<String> never = results.stream().filter(Gating.Result::rejectedAnyway).map(Gating.Result::build).toList();
        boolean ignored = results.stream().anyMatch(r -> r.gated(serverOnly) && r.without().equals(Gating.IGNORED));
        StringBuilder doc = new StringBuilder();
        if (!gated.isEmpty()) {
            doc.append("BDS-verified: in a world without experiments BDS ").append(ignored ? "rejects or ignores" : "rejects")
                .append(" it in ").append(String.join(", ", gated)).append(".\n");
        }
        if (!open.isEmpty()) {
            doc.append("BDS takes it without any experiment in ").append(String.join(", ", open)).append(": the docs' gating does not hold there.\n");
        }
        if (!never.isEmpty()) {
            doc.append("BDS rejects it even with the experiments on in ").append(String.join(", ", never)).append(".\n");
        }
        return doc.toString();
    }

    // ---- Records -----------------------------------------------------------------------------------------------------

    private ClassName name(ObjType o) {
        return o == c.root ? className(c) : className(c).nestedClass(o.name);
    }

    private TypeSpec record(ObjType o, boolean top) {
        ClassName self = name(o);
        TypeSpec.Builder type = TypeSpec.recordBuilder(self).addModifiers(Modifier.PUBLIC);
        // Javadoc
        StringBuilder doc = new StringBuilder();
        String text = top ? c.doc : o.doc;
        doc.append(lines(text.isEmpty() ? "The {@code " + o.path + "} object." : !top && o.javadoc ? text : Docs.escape(text)));
        if (top && !c.standalone) {
            doc.append("<p>\nAdd-on JSON: {@code \"").append(c.name).append("\"}")
                .append(c.page.isEmpty() || Docs.learnUrl(c.page) != null ? "" : ", documented in {@code " + c.page + "}").append(".\n");
            if (c.bedrockSince != null) {
                doc.append(String.join("\n", Docs.wrap("Bedrock " + c.bedrockSince + " is the first bundled version with it: the schemas of"
                    + " older captured BDS builds do not have it, so their palettes leave it out and add a report warning."))).append('\n');
            }
            if (c.serverOnly != null) {
                doc.append(serverOnlyDoc());
            }
            if (c.rejected != null) {
                doc.append(String.join("\n", Docs.wrap("Retired: " + Docs.escape(rejectedText())
                    + ". Its encoder writes nothing and adds a report warning, as BDS would never send it to a client."))).append('\n');
            }
            if (!c.experiments.isEmpty()) {
                doc.append("The creator docs name the experiment").append(c.experiments.size() > 1 ? "s " : " ")
                    .append(String.join(", ", c.experiments.stream().map(Docs::escape).toList()))
                    .append(" for it (in some format versions);")
                    .append(c.gating.isEmpty() ? " unverified against BDS.\n" : "\n" + gatingDoc(c.gating, c.serverOnly != null));
                for (String name : c.unmappedExperiments) {
                    doc.append("Unverified: the creator docs name experiment \"").append(Docs.escape(name)).append("\", which is in no capture.\n");
                }
            }
            if (c.transform != null) {
                doc.append("Compiled by the hand transform {@code Transforms.").append(c.transform).append("}.\n");
            }
        }
        doc.append('\n');
        for (Field f : allFields(o)) {
            List<String> l = Docs.wrap(fieldDoc(f));
            doc.append("@param ").append(f.java).append(' ').append(l.isEmpty() ? "" : l.getFirst()).append('\n');
            for (int i = 1; i < l.size(); i++) {
                doc.append("       ").append(l.get(i)).append('\n');
            }
        }
        if (top) {
            String url = c.standalone ? null : Docs.learnUrl(c.page);
            if (url != null) {
                doc.append("@see <a href=\"").append(url).append("\">Microsoft Learn: ").append(Docs.escape(c.name)).append("</a>\n");
            }
            doc.append("@since ").append(since).append('\n');
            if (c.replacement != null) {
                doc.append("@deprecated ").append(replacementDoc(c.replacement)).append('\n');
            } else if (c.rejected != null) {
                doc.append("@deprecated ").append(String.join("\n       ", Docs.wrap(Docs.escape(rejectedText())))).append('\n');
            } else if (c.deprecated()) {
                doc.append("@deprecated marked deprecated by the Minecraft creator documentation\n");
            }
        }
        type.addJavadoc("$L", doc.toString());
        if (top && c.minFormat != null) {
            type.addAnnotation(AnnotationSpec.builder(BEDROCK_FORMAT).addMember("value", "$S", c.minFormat).build());
        }
        if (top && !c.toggles.isEmpty()) {
            type.addAnnotation(AnnotationSpec.builder(BEDROCK_EXPERIMENT).addMember("value", "$S", c.toggles.getFirst()).build());
        }
        if (top && c.sincePreview != null) {
            type.addAnnotation(AnnotationSpec.builder(SINCE_PREVIEW).addMember("value", "$S", c.sincePreview).build());
        }
        if (top && c.bedrockSince != null) {
            type.addAnnotation(AnnotationSpec.builder(BEDROCK_SINCE).addMember("value", "$S", c.bedrockSince).build());
        }
        if (top && c.serverOnly != null) {
            AnnotationSpec.Builder serverOnly = AnnotationSpec.builder(SERVER_ONLY);
            if (c.serverOnly.since() != Integer.MIN_VALUE) {
                serverOnly.addMember("since", "$L", c.serverOnly.since());
            }
            if (c.serverOnly.until() != Integer.MAX_VALUE) {
                serverOnly.addMember("until", "$L", c.serverOnly.until());
            }
            type.addAnnotation(serverOnly.build());
        }
        if (top && (c.deprecated() || c.replacement != null || c.rejected != null)) {
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

        // Components and the compact constructor's validation
        List<ParameterSpec> params = new ArrayList<>();
        for (Field f : allFields(o)) {
            ParameterSpec.Builder p = ParameterSpec.builder(recordType(f), f.java);
            if (f.minFormat != null) {
                p.addAnnotation(AnnotationSpec.builder(BEDROCK_FORMAT).addMember("value", "$S", f.minFormat).build());
            }
            if (f.sincePreview != null) {
                p.addAnnotation(AnnotationSpec.builder(SINCE_PREVIEW).addMember("value", "$S", f.sincePreview).build());
            }
            params.add(p.build());
        }
        Validation validation = validation(o);
        MethodSpec.Builder constructor = validation.code().isEmpty() ? MethodSpec.constructorBuilder()
            : MethodSpec.compactConstructorBuilder().addModifiers(Modifier.PUBLIC).addCode(validation.code())
                .addJavadoc(validation.javadoc());
        type.recordConstructor(constructor.addParameters(params).build());

        if (top && !c.standalone) {
            type.addField(FieldSpec.builder(STRING, "NAME", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("The component name in add-on JSON.\n").initializer("$S", c.name).build());
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
        type.addType(builder(o, self));
        if (top) {
            for (ObjType nested : c.types.values()) {
                type.addType(record(nested, false));
            }
            for (EnumType e : c.enums.values()) {
                type.addType(enumType(e));
            }
            for (UnionType u : model.unions.values()) {
                if (u.owner == c) {
                    type.addType(union(u, since));
                }
            }
            if (c.usesVec3) {
                type.addType(TypeSpec.recordBuilder("Vec3").addModifiers(Modifier.PUBLIC).addJavadoc("An add-on JSON vector {@code [x, y, z]}.\n\n@param x the x component\n@param y the y component\n@param z the z component\n")
                    .recordConstructor(MethodSpec.constructorBuilder().addParameter(float.class, "x").addParameter(float.class, "y")
                        .addParameter(float.class, "z").build()).build());
            }
        }
        return type.build();
    }

    private static String lines(String text) {
        return String.join("\n", Docs.wrap(text)) + "\n";
    }

    static List<Field> allFields(ObjType o) {
        return o.fields;
    }

    private String fieldDoc(Field f) {
        StringBuilder s = new StringBuilder();
        String doc = f.javadoc ? f.doc : Docs.escape(f.doc);
        s.append(doc.isEmpty() ? (f.json == null ? "The value." : "Add-on JSON {@code " + f.json + "}.") : doc);
        if (f.json != null && !doc.isEmpty() && f.keyedExtraOf == null) {
            s.append(" Add-on JSON {@code ").append(f.json).append("}.");
        }
        if (f.required) {
            s.append(" Required.");
        } else if (f.defaultValue != null) {
            s.append(" Default: ").append(Docs.literal(f.defaultValue)).append('.');
        } else {
            s.append(" Optional (null: not set).");
        }
        if (!f.reservedKeys.isEmpty()) {
            s.append(" A key that has a field of its own (").append(String.join(", ", f.reservedKeys.stream().map(k -> "{@code " + k + "}").toList()))
                .append(") is refused.");
        }
        if (f.defaultNote != null) {
            s.append(' ').append(f.defaultNote);
        }
        // Each limit with where it comes from; a limit nothing states is not written (BDS may still refuse values there)
        if (f.min != null && f.max != null && f.minSource.equals(f.maxSource)) {
            s.append(" Range [").append(Docs.number(f.min)).append(", ").append(Docs.number(f.max)).append("] (").append(f.minSource).append(").");
        } else {
            if (f.min != null) {
                s.append(" At least ").append(Docs.number(f.min)).append(" (").append(f.minSource).append(").");
            }
            if (f.max != null) {
                s.append(" At most ").append(Docs.number(f.max)).append(" (").append(f.maxSource).append(").");
            }
        }
        if (f.allowed != null) {
            s.append(" One of ").append(f.allowed.stream().map(Docs::number).toList()).append('.');
        }
        if (f.notBelow != null) {
            s.append(" Not below {@code ").append(f.notBelow.json).append("} (").append(f.notBelowSource).append(").");
        }
        if (f.conflictsWith != null) {
            s.append(" Empty while {@code ").append(f.conflictsWith.json).append("} is {@code ").append(f.conflictValue).append("} (")
                .append(f.conflictSource).append(").");
        }
        if (f.multipleOf != null) {
            s.append(" Multiples of ").append(Docs.number(f.multipleOf)).append(" only (BDS refuses the others).");
        }
        if (f.documentedValues != null && f.documentedValues.size() <= 10 && !f.documentedValues.stream().allMatch(f.doc::contains)) {
            s.append(" Documented values: ").append(String.join(", ", f.documentedValues.stream().map(v -> "{@code " + Docs.escape(v) + "}").toList()))
                .append("; BDS takes other strings too.");
        }
        if (f.transform != null) {
            s.append(" Compiled by the hand transform {@code Transforms.").append(f.transform).append("}.");
        }
        return s.toString();
    }

    static boolean nullable(Field f) {
        return f.nullable();
    }

    private static boolean primitive(Field f) {
        return !nullable(f) && f.type instanceof Scalar s && s.kind() != ScalarKind.STRING;
    }

    private TypeName recordType(Field f) {
        TypeName t = typeName(f.type, nullable(f));
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
    TypeName typeName(Type t, boolean boxed) {
        return typeName(c, t, boxed);
    }

    static TypeName typeName(Comp c, Type t, boolean boxed) {
        return switch (t) {
            case Scalar s -> switch (s.kind()) {
                case BOOL -> boxed ? ClassName.get(Boolean.class) : TypeName.BOOLEAN;
                case INT -> boxed ? ClassName.get(Integer.class) : TypeName.INT;
                case FLOAT -> boxed ? ClassName.get(Float.class) : TypeName.FLOAT;
                case STRING -> STRING;
            };
            case EnumRef e -> className(c).nestedClass(e.type().name);
            case ObjRef r -> className(c).nestedClass(r.type().name);
            case Vec3 v -> className(c).nestedClass("Vec3");
            case ListOf l -> ParameterizedTypeName.get(LIST, typeName(c, l.element(), true));
            case MapOf m -> ParameterizedTypeName.get(MAP, STRING, typeName(c, m.value(), true));
            case States s -> ParameterizedTypeName.get(MAP, STRING, ClassName.get(Object.class));
            case ComponentRef r -> ClassName.get(c.kind.pkg(), r.className());
            case UnionRef u -> unionName(u.union());
        };
    }

    static ClassName unionName(UnionType u) {
        return u.owner != null ? className(u.owner).nestedClass(u.name) : ClassName.get(u.kind.pkg(), u.name);
    }

    // ---- Validation ----------------------------------------------------------------------------------------------------

    /**
     * The compact constructor of a record: its code and the Javadoc that says what the code does.
     *
     * @param code the statements, empty if there is nothing to check or copy
     * @param nulls whether a required component is checked for null
     * @param ranges whether a component is checked against a range, an allowed set or another component
     * @param copies whether a list or map is copied
     */
    private record Validation(CodeBlock code, boolean nulls, boolean ranges, boolean copies) {
        String javadoc() {
            boolean checks = nulls || ranges;
            String summary = checks && copies ? "Checks the components and copies lists and maps, so the value is immutable.\n"
                : checks ? "Checks the components.\n" : "Copies lists and maps, so the value is immutable.\n";
            StringBuilder doc = new StringBuilder(summary);
            if (nulls) {
                doc.append("\n@throws NullPointerException if a required component is null\n");
            }
            if (ranges) {
                doc.append(nulls ? "" : "\n")
                    .append("@throws IllegalArgumentException if a component is out of its documented range or conflicts with another\n");
            }
            return doc.toString();
        }
    }

    private Validation validation(ObjType o) {
        CodeBlock.Builder body = CodeBlock.builder();
        boolean[] flags = new boolean[3];
        String owner = c.className + (o == c.root ? "" : "." + o.name);
        for (Field f : allFields(o)) {
            String name = owner + "." + f.java;
            boolean nullable = nullable(f);
            boolean reference = !(f.type instanceof Scalar s && s.kind() != ScalarKind.STRING) || nullable;
            if (f.type instanceof ListOf) {
                flags[2] = true;
                if (nullable) {
                    body.addStatement("$N = $N == null ? null : $T.copyOf($N)", f.java, f.java, LIST, f.java);
                } else {
                    flags[0] = true;
                    body.addStatement("$N = $T.copyOf($T.requireNonNull($N, $S))", f.java, LIST, OBJECTS, f.java, name);
                }
            } else if (f.type instanceof MapOf || f.type instanceof States) {
                flags[2] = true;
                if (nullable) {
                    body.addStatement("$N = $N == null ? null : $T.copy($N, $S)", f.java, f.java, CHECK, f.java, name);
                } else {
                    flags[0] = true;
                    body.addStatement("$N = $T.copy($N, $S)", f.java, CHECK, f.java, name);
                }
            } else if (reference && !nullable) {
                flags[0] = true;
                body.addStatement("$T.requireNonNull($N, $S)", OBJECTS, f.java, name);
            }
            if (!f.reservedKeys.isEmpty()) {
                flags[1] = true;
                body.beginControlFlow("for ($T key : $T.of($L))", STRING, LIST, String.join(", ", f.reservedKeys.stream()
                    .map(k -> "\"" + k + "\"").toList()));
                body.beginControlFlow("if ($N.containsKey(key))", f.java);
                body.addStatement("throw new $T($S + key + $S)", IllegalArgumentException.class, name + " has the key ",
                    ", which has a field of its own");
                body.endControlFlow().endControlFlow();
            }
            if (f.type instanceof States) {
                flags[1] = true;
                body.addStatement("$T.stateValues($N, $S)", CHECK, f.java, name);
            }
            if (f.type instanceof UnionRef && (f.min != null || f.max != null || f.multipleOf != null || f.allowed != null)) {
                throw new GenerationException(name + " is a union with a limit; a limit of one form needs a check of that variant");
            }
            if (f.min != null || f.max != null) {
                flags[1] = true;
                if (nullable) {
                    body.beginControlFlow("if ($N != null)", f.java);
                }
                body.addStatement("$T.range($S, $N, $L, $L)", CHECK, name, f.java, bound(f.min), bound(f.max));
                if (nullable) {
                    body.endControlFlow();
                }
            }
            if (f.multipleOf != null) {
                flags[1] = true;
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
                flags[1] = true;
                if (nullable) {
                    body.beginControlFlow("if ($N != null)", f.java);
                }
                body.addStatement("$T.oneOf($S, $N, $L)", CHECK, name, f.java, String.join(", ", f.allowed.stream().map(Docs::number).toList()));
                if (nullable) {
                    body.endControlFlow();
                }
            }
            if (f.notBelow != null) {
                flags[1] = true;
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
                flags[1] = true;
                body.addStatement("$T.conflict($S, $S, $L, $S, $N, $S)", CHECK, owner, f.java,
                    (nullable ? f.java + " != null && " : "") + "!" + f.java + ".isEmpty()", f.conflictsWith.java, f.conflictsWith.java, f.conflictValue);
            }
        }
        return new Validation(body.build(), flags[0], flags[1], flags[2]);
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
                .append(first == null ? "protocol " + rejecting.since() : first.getValue() + " (protocol " + first.getKey() + ")")
                .append("; the list is accepted by every captured BDS.");
        }
        type.addMethod(MethodSpec.methodBuilder("of").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addJavadoc(doc + "\n\n@param entry the one entry\n@return the value with everything else at its default\n", jsonType, target.java)
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

    /** Javadoc of a builder setter: the summary, then the one parameter and the builder it returns. */
    private static final String SETTER = "$L\n\n@param $L the value\n@return this builder\n";

    // ---- Builders --------------------------------------------------------------------------------------------------------

    private TypeSpec builder(ObjType o, ClassName self) {
        ClassName builder = self.nestedClass("Builder");
        TypeSpec.Builder type = TypeSpec.classBuilder(builder).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("Builds {@link $T}: defaults applied, required fields and ranges checked by {@link #build()}.\n", self);
        for (Field f : allFields(o)) {
            FieldSpec.Builder field = FieldSpec.builder(nullable(typeName(f.type, true)), f.java, Modifier.PRIVATE);
            if (f.defaultValue != null) {
                field.initializer(literal(f.type, f.defaultValue));
            }
            type.addField(field.build());
        }
        type.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        for (Field f : allFields(o)) {
            setters(type, builder, f);
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
        MethodSpec.Builder main = MethodSpec.methodBuilder(f.java).addModifiers(Modifier.PUBLIC).returns(nonNull(builder))
            .addJavadoc(SETTER, "Sets {@code " + json + "}.", f.java)
            .addParameter(recordType(f), f.java)
            .addStatement("this.$N = $N", f.java, f.java);
        type.addMethod(main.addStatement("return this").build());
        if (f.type instanceof UnionRef u) {
            unionSetters(type, builder, f, u.union(), json);
        }
        if (f.type instanceof ListOf l) {
            TypeName element = typeName(l.element(), true);
            type.addMethod(MethodSpec.methodBuilder(f.java).addModifiers(Modifier.PUBLIC).returns(nonNull(builder)).varargs()
                .addJavadoc(SETTER, "Sets {@code " + json + "}.", f.java).addParameter(com.palantir.javapoet.ArrayTypeName.of(nonNull(element)), f.java)
                .addStatement("return $N($T.of($N))", f.java, LIST, f.java).build());
            if (l.element() instanceof Scalar s && s.kind() == ScalarKind.STRING && f.descriptor != null) {
                ClassName handle = f.descriptor.equals("item") ? ITEM_TYPE : BLOCK_TYPE;
                type.addMethod(MethodSpec.methodBuilder(f.java).addModifiers(Modifier.PUBLIC).returns(nonNull(builder)).varargs()
                    .addJavadoc(SETTER, "Sets {@code " + json + "} to the identifiers of vanilla or custom " + f.descriptor + "s.", f.java)
                    .addParameter(com.palantir.javapoet.ArrayTypeName.of(nonNull(handle)), f.java)
                    .addStatement("$T<$T> identifiers = new $T<>()", LIST, STRING, ArrayList.class)
                    .beginControlFlow("for ($T type : $N)", handle, f.java).addStatement("identifiers.add(type.identifier())").endControlFlow()
                    .addStatement("return $N(identifiers)", f.java).build());
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

    /** {@code name(T first, T... rest)}: a list of at least one value, passed on as the list. */
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

    // ---- Unions ------------------------------------------------------------------------------------------------------

    /** The sealed interface of a union: one record per JSON form, and a factory named after each (and one taking vanilla constants for a list of identifiers). */
    private static TypeSpec union(UnionType u, String since) {
        ClassName self = unionName(u);
        Comp owner = u.owner;
        StringBuilder doc = new StringBuilder("The value of an add-on JSON field that takes one of several forms:\n");
        doc.append("<ul>\n");
        for (Variant v : u.variants) {
            doc.append("    <li>{@link ").append(v.name).append("}: ").append(meaning(v)).append("</li>\n");
        }
        doc.append("</ul>\n<p>\nSwitch over it without a default: the records below are all its forms.\n");
        doc.append("<p>\nUsed by: ").append(String.join(", ", u.users.stream().sorted().map(x -> "{@code " + x + "}").toList())).append(".\n");
        if (u.owner == null) {
            doc.append("\n@since ").append(since).append('\n');
        }
        TypeSpec.Builder type = TypeSpec.interfaceBuilder(self).addModifiers(Modifier.PUBLIC, Modifier.SEALED).addJavadoc("$L", doc.toString());
        if (u.owner != null) {
            type.addModifiers(Modifier.STATIC);
        }
        for (Variant v : u.variants) {
            ClassName variant = self.nestedClass(v.name);
            type.addPermittedSubclass(variant);
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

    // ---- Literals ----------------------------------------------------------------------------------------------------

    CodeBlock literal(Type t, JsonElement v) {
        return switch (t) {
            case Scalar s -> switch (s.kind()) {
                case BOOL -> CodeBlock.of("$L", v.getAsBoolean());
                case INT -> CodeBlock.of("$L", v.getAsLong() == v.getAsDouble() ? v.getAsLong() : (long) v.getAsDouble());
                case FLOAT -> CodeBlock.of("$L", Docs.floatLiteral(v.getAsDouble()));
                case STRING -> CodeBlock.of("$S", v.getAsString());
            };
            case EnumRef e -> CodeBlock.of("$T.$L", className(c).nestedClass(e.type().name),
                e.type().constants.get(e.type().values.indexOf(v.getAsString())));
            case Vec3 vec -> {
                List<String> xyz = new ArrayList<>();
                if (v.isJsonArray()) {
                    v.getAsJsonArray().forEach(x -> xyz.add(Docs.floatLiteral(x.getAsDouble())));
                } else {
                    for (String k : List.of("x", "y", "z")) {
                        xyz.add(Docs.floatLiteral(v.getAsJsonObject().has(k) ? v.getAsJsonObject().get(k).getAsDouble() : 0));
                    }
                }
                yield CodeBlock.of("new $T($L)", className(c).nestedClass("Vec3"), String.join(", ", xyz));
            }
            case ListOf l -> {
                List<CodeBlock> items = new ArrayList<>();
                v.getAsJsonArray().forEach(x -> items.add(literal(l.element(), x)));
                yield CodeBlock.of("$T.of($L)", LIST, CodeBlock.join(items, ", "));
            }
            case MapOf m -> {
                List<CodeBlock> items = new ArrayList<>();
                v.getAsJsonObject().entrySet().forEach(e -> items.add(CodeBlock.of("$S, $L", e.getKey(), literal(m.value(), e.getValue()))));
                yield CodeBlock.of("$T.of($L)", MAP, CodeBlock.join(items, ", "));
            }
            case States s -> CodeBlock.of("$T.of()", MAP);
            case ComponentRef r -> CodeBlock.of("null");
            case UnionRef u -> {
                Variant form = u.union().variants.stream().filter(x -> Defaults.fits(x.type, v)).findFirst()
                    .orElseThrow(() -> new GenerationException("the default " + v + " fits no form of the union " + u.union().name));
                yield CodeBlock.of("$T.$N($L)", unionName(u.union()), form.factory(), literal(form.type, v));
            }
            case ObjRef r -> {
                CodeBlock.Builder s = CodeBlock.builder().add("$T.builder()", className(c).nestedClass(r.type().name));
                JsonObject o = v.getAsJsonObject();
                for (Field f : r.type().fields) {
                    if (o.has(f.json)) {
                        s.add(".$N($L)", f.java, literal(f.type, o.get(f.json)));
                    }
                }
                yield s.add(".build()").build();
            }
        };
    }

    // ---- Enums -------------------------------------------------------------------------------------------------------

    private TypeSpec enumType(EnumType e) {
        ClassName self = className(c).nestedClass(e.name);
        TypeSpec.Builder type = TypeSpec.enumBuilder(self).addModifiers(Modifier.PUBLIC)
            .addJavadoc("The documented values; {@link #value()} is the add-on JSON string.\n");
        for (int i = 0; i < e.values.size(); i++) {
            type.addEnumConstant(e.constants.get(i), TypeSpec.anonymousClassBuilder("$S", e.values.get(i))
                .addJavadoc("{@code $L}\n", Docs.escape(e.values.get(i))).build());
        }
        type.addField(STRING, "value", Modifier.PRIVATE, Modifier.FINAL);
        type.addMethod(MethodSpec.constructorBuilder().addParameter(STRING, "value").addStatement("this.value = value").build());
        type.addMethod(MethodSpec.methodBuilder("value").addModifiers(Modifier.PUBLIC).returns(nonNull(STRING))
            .addJavadoc("Returns the value in add-on JSON.\n\n@return the value in add-on JSON\n").addStatement("return value").build());
        type.addMethod(MethodSpec.methodBuilder("fromValue").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(nonNull(self))
            .addJavadoc("Finds the constant of an add-on JSON value.\n\n@param value the value in add-on JSON\n@return the constant of the value\n@throws IllegalArgumentException if the value is not documented\n")
            .addParameter(nonNull(STRING), "value")
            .beginControlFlow("for ($T constant : values())", self)
            .beginControlFlow("if (constant.value.equals(value))").addStatement("return constant").endControlFlow()
            .endControlFlow()
            .addStatement("throw new $T($S + value)", IllegalArgumentException.class, "Not a " + e.name + ": ").build());
        return type.build();
    }
}
