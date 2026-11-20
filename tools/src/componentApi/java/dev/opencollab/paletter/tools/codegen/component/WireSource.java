package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Kind;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes the internal encoders with JavaPoet: per component, the inferred rules of each protocol window as straight line
 * code over the component record ({@code dev.opencollab.paletter.internal.component.ItemWire}, {@code BlockWire},
 * {@code TraitWire}; one {@link ComponentWire} per component, one {@link WindowWire} per window), and
 * {@code ComponentIndex} ({@link IndexSource}).
 * <p>
 * Semantics of a window's mapping, per object level: the component's {@code constants} first; then every field in docs
 * order. A set field writes its value rules (identity, linear, table, affix, bitset, count) and the constants its presence
 * switches on (only for leaves no field owns: the others follow their owner); an unset field writes its {@code whenAbsent}
 * leaves (its own and its children's, never another field's). A field named in {@link HandTransforms} calls its hand
 * transform instead, with the record that holds it, after the generic fields of that record.
 */
final class WireSource {
    static final String PKG = "dev.opencollab.paletter.internal.component";
    static final ClassName WIRE = ClassName.get(PKG, "Wire");
    static final ClassName WIRE_NODE = ClassName.get(PKG, "WireNode");
    static final ClassName WIRE_CONTEXT = ClassName.get(PKG, "WireContext");
    static final ClassName TRANSFORMS = ClassName.get(PKG, "Transforms");
    static final ClassName NBT_MAP = ClassName.get("org.cloudburstmc.nbt", "NbtMap");

    private WireSource() {
    }

    static void write(ApiModel model, Map<String, String> files) {
        for (Kind kind : Kind.values()) {
            files.put(PKG.replace('.', '/') + "/" + wireClass(kind) + ".java", ApiSource.file(PKG, kindClass(kind, model)));
        }
        files.put(PKG.replace('.', '/') + "/ComponentIndex.java", ApiSource.file(PKG, IndexSource.index(model)));
        if (!model.traitStates.isEmpty()) {
            files.put(PKG.replace('.', '/') + "/TraitStateTable.java", ApiSource.file(PKG, TraitStateSource.table(model)));
        }
    }

    static String wireClass(Kind kind) {
        return switch (kind) {
            case ITEM -> "ItemWire";
            case BLOCK -> "BlockWire";
            case TRAIT -> "TraitWire";
        };
    }

    static String method(Comp c) {
        return JavaNames.camel(c.baseName != null ? c.baseName : c.className);
    }

    private static TypeSpec kindClass(Kind kind, ApiModel model) {
        List<Comp> members = model.components.stream().filter(c -> c.kind == kind && !c.standalone).toList();
        ClassName iface = ClassName.get(kind.pkg(), kind.iface());
        TypeSpec.Builder type = TypeSpec.classBuilder(wireClass(kind)).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Encoders of the generated {@link $T} types: the rules of {@code data/model/mappings.json} per\nprotocol window, "
                + "hand transforms by name. Not API.\n", iface)
            .addAnnotation(model.removedComponents.stream().anyMatch(c -> c.kind == kind)
                ? AnnotationSpec.builder(SuppressWarnings.class).addMember("value", "{$S, $S, $S}", "unused", "deprecation", "removal").build()
                : AnnotationSpec.builder(SuppressWarnings.class).addMember("value", "{$S, $S}", "unused", "deprecation").build())
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        List<MethodSpec> encoders = new ArrayList<>();
        CodeBlock.Builder dispatch = CodeBlock.builder().beginControlFlow("switch (component)");
        for (Comp c : members) {
            CodeBlock inline = new ComponentWire(c, false).component(encoders);
            if (inline == null) {
                dispatch.addStatement("case $T c -> $N(c, ctx, out)", ApiSource.className(c), method(c));
            } else {
                dispatch.add("case $T c -> $L", ApiSource.className(c), inline);
            }
        }
        // A component that left the model (StandIns): every palette leaves its stand-in out, with a report warning
        for (Comp c : model.removedComponents.stream().filter(c -> c.kind == kind).toList()) {
            dispatch.addStatement("case $T c -> ctx.drop($T.NAME, $S)", ApiSource.className(c), ApiSource.className(c),
                "was removed from Bedrock (a deprecated stand-in)");
        }
        if (kind == Kind.ITEM || kind == Kind.BLOCK) {
            dispatch.addStatement("case $T c -> $T.write(c, ctx, out)", ClassName.get(kind.pkg(), ApiSource.wireOnly(kind)),
                ClassName.get(PKG, "WireOnlyWire"));
        }
        // The sealed type's sentinel (ApiSource#sentinelName) has no instances
        dispatch.addStatement("default -> throw new $T(component.toString())", IllegalArgumentException.class);
        dispatch.endControlFlow();
        type.addMethod(MethodSpec.methodBuilder("write").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addJavadoc("Writes a component into {@code out}: the item components compound, the block's {@code components}, or for a\n"
                + "trait its element of {@code traits}.\n")
            .addParameter(ApiSource.nonNull(iface), "component").addParameter(ApiSource.nonNull(WIRE_CONTEXT), "ctx")
            .addParameter(ApiSource.nonNull(WIRE_NODE), "out").addCode(dispatch.build()).build());
        for (Comp c : model.components.stream().filter(c -> c.kind == kind && c.standalone).toList()) {
            new ComponentWire(c, true).component(encoders);
        }
        encoders.forEach(type::addMethod);
        if (kind == Kind.ITEM || kind == Kind.BLOCK) {
            type.addMethod(envelope(kind, model));
        }
        return type.build();
    }

    /**
     * {@code envelope(ctx, out)}: the leaves of the kind's {@code envelope} in {@code mappings.json}, per protocol window
     * (the oldest window also holds for older protocols, like the windows of a component).
     */
    private static MethodSpec envelope(Kind kind, ApiModel model) {
        JsonArray windows = model.envelope.getOrDefault(kind == Kind.ITEM ? "item" : "block", new JsonArray());
        CodeBlock.Builder body = CodeBlock.builder();
        if (windows.size() > 1) {
            body.addStatement("int protocol = ctx.protocol()");
        }
        // Newest window first: each holds from its first protocol on
        for (int i = windows.size() - 1; i >= 0; i--) {
            JsonObject window = windows.get(i).getAsJsonObject();
            if (windows.size() > 1) {
                int since = window.getAsJsonArray("protocols").get(0).getAsInt();
                if (i == windows.size() - 1) {
                    body.beginControlFlow("if (protocol >= $L)", since);
                } else if (i > 0) {
                    body.nextControlFlow("else if (protocol >= $L)", since);
                } else {
                    body.nextControlFlow("else");
                }
            }
            for (Map.Entry<String, JsonElement> leaf : window.getAsJsonObject("leaves").entrySet()) {
                body.addStatement("out.put($S, $L)", leaf.getKey(), WireValues.literal(leaf.getValue().getAsString()));
            }
        }
        if (windows.size() > 1) {
            body.endControlFlow();
        }
        return MethodSpec.methodBuilder("envelope").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addJavadoc("Writes what BDS sends for every custom " + (kind == Kind.ITEM ? "item, relative to its components compound"
                : "block, relative to its block property data") + ",\nwhatever the definition says ({@code envelope} of "
                + "{@code data/model/mappings.json}: what every control variant of\nthe spec captures sends alike). The description "
                + "and the components write over it.\n")
            .addParameter(ApiSource.nonNull(WIRE_CONTEXT), "ctx").addParameter(ApiSource.nonNull(WIRE_NODE), "out")
            .addCode(body.build()).build();
    }
}
