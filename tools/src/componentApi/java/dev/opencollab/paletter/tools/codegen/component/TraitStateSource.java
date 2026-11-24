package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code TraitStateTable}: the block states each trait adds, from {@code mappings.json} {@code traitStates} (written by the
 * inference, see {@code tools.model.infer.TraitStateTable}). Each {@code enabled_states} value has protocol windows, like the
 * component encoders: an older protocol than a window's first takes the window before it, the first window every older one
 * and the last every newer one. A window may mark the value unsupported, with the BDS data that makes it so.
 */
final class TraitStateSource {
    private static final ClassName BLOCK_PROPERTY = ClassName.get("dev.opencollab.paletter.block", "BlockProperty");

    private TraitStateSource() {
    }

    static TypeSpec table(ApiModel model) {
        ClassName list = ClassName.get(List.class);
        CodeBlock.Builder of = CodeBlock.builder().add("return switch (trait + $S + value) {\n", " ").indent();
        CodeBlock.Builder unsupported = CodeBlock.builder().add("return switch (trait + $S + value) {\n", " ").indent();
        // By name: the order of mappings.json does not matter
        for (Map.Entry<String, JsonElement> trait : new TreeMap<>(model.traitStates.asMap()).entrySet()) {
            for (Map.Entry<String, JsonElement> value : new TreeMap<>(trait.getValue().getAsJsonObject().asMap()).entrySet()) {
                String key = trait.getKey() + " " + value.getKey();
                JsonArray windows = value.getValue().getAsJsonObject().getAsJsonArray("windows");
                List<CodeBlock> states = new ArrayList<>();
                List<CodeBlock> reasons = new ArrayList<>();
                boolean anyUnsupported = false;
                for (JsonElement w : windows) {
                    JsonObject window = w.getAsJsonObject();
                    String protocols = windows.size() == 1 ? "" : "protocols " + window.getAsJsonArray("protocols") + ": ";
                    if (window.has("unsupported")) {
                        JsonObject why = window.getAsJsonObject("unsupported");
                        of.add("// $Lunsupported: $L\n", protocols, why.get("reason").getAsString());
                        why.getAsJsonArray("evidence").forEach(e -> of.add("// evidence: $L\n", e.getAsString()));
                        states.add(CodeBlock.of("null"));
                        reasons.add(CodeBlock.of("$S", why.get("reason").getAsString()));
                        anyUnsupported = true;
                        continue;
                    }
                    List<CodeBlock> properties = new ArrayList<>();
                    for (JsonElement e : window.getAsJsonArray("states")) {
                        JsonObject state = e.getAsJsonObject();
                        of.add("// $L$L: values from $L; $L\n", protocols, state.get("name").getAsString(),
                            state.get("valuesFrom").getAsString(), state.get("default").getAsString());
                        properties.add(property(state));
                    }
                    states.add(CodeBlock.of("$T.of(\n$>$>$L$<$<)", list, CodeBlock.join(properties, ",\n")));
                    reasons.add(CodeBlock.of("null"));
                }
                of.add("case $S -> $L;\n", key, byProtocol(windows, states));
                if (anyUnsupported) {
                    unsupported.add("case $S -> $L;\n", key, byProtocol(windows, reasons));
                }
            }
        }
        of.add("default -> throw new $T(trait + $S + value);\n", IllegalArgumentException.class, " has no enabled state ")
            .unindent().add("};\n");
        unsupported.add("default -> null;\n").unindent().add("};\n");
        AnnotationSpec nullable = AnnotationSpec.builder(ApiSource.NULLABLE).build();
        return TypeSpec.classBuilder("TraitStateTable").addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("The block states each trait adds, per value of its {@code enabled_states} and protocol window, in order; the\n"
                + "first value of a state is its default. The client adds them (they are not on the wire). From\n"
                + "{@code data/model/mappings.json} {@code traitStates}: the names from the docs, the values from the vanilla state of\n"
                + "the same name or from each window's own BDS dump, the defaults checked against the hashes BDS sent where a capture\n"
                + "has one (each case says). A window where BDS's data defines no state set for a value marks it unsupported. Not API.\n")
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(MethodSpec.methodBuilder("of").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addJavadoc("@param trait the trait name, e.g. {@code minecraft:placement_direction}\n"
                    + "@param value a value of its {@code enabled_states}, e.g. {@code minecraft:cardinal_direction}\n"
                    + "@param protocol the protocol of the palette's version\n"
                    + "@return the states it adds, or null if it is {@link #unsupported} in that protocol\n")
                .returns(ParameterizedTypeName.get(list, ParameterizedTypeName.get(BLOCK_PROPERTY, WildcardTypeName.subtypeOf(Object.class)))
                    .annotated(nullable))
                .addParameter(ApiSource.STRING, "trait").addParameter(ApiSource.STRING, "value").addParameter(int.class, "protocol")
                .addCode(of.build()).build())
            .addMethod(MethodSpec.methodBuilder("unsupported").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addJavadoc("@param trait the trait name\n"
                    + "@param value a value of its {@code enabled_states}\n"
                    + "@param protocol the protocol of the palette's version\n"
                    + "@return why the value adds no well-defined states in that protocol (the BDS data), or null if it does\n")
                .returns(ApiSource.STRING.annotated(nullable))
                .addParameter(ApiSource.STRING, "trait").addParameter(ApiSource.STRING, "value").addParameter(int.class, "protocol")
                .addCode(unsupported.build()).build())
            .build();
    }

    /** The expression of each window, chosen by {@code protocol}: below the first protocol of the next window, the window before it. */
    static CodeBlock byProtocol(JsonArray windows, List<CodeBlock> expressions) {
        // Neighbouring windows with the same expression (a reason of null for two windows of other states) are one
        List<CodeBlock> merged = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < expressions.size(); i++) {
            if (!merged.isEmpty() && merged.getLast().equals(expressions.get(i))) {
                continue;
            }
            merged.add(expressions.get(i));
            starts.add(windows.get(i).getAsJsonObject().getAsJsonArray("protocols").asList().stream()
                .mapToInt(JsonElement::getAsInt).min().orElse(0));
        }
        CodeBlock result = merged.getLast();
        for (int i = merged.size() - 2; i >= 0; i--) {
            result = CodeBlock.of("protocol < $L ? $L : $L", starts.get(i + 1), merged.get(i), result);
        }
        return result;
    }

    private static CodeBlock property(JsonObject state) {
        String name = state.get("name").getAsString();
        List<CodeBlock> values = new ArrayList<>();
        state.getAsJsonArray("values").forEach(v -> values.add(switch (state.get("type").getAsString()) {
            case "string" -> CodeBlock.of("$S", v.getAsString());
            case "bool" -> CodeBlock.of("$L", v.getAsInt() != 0);
            default -> CodeBlock.of("$L", v.getAsInt());
        }));
        String factory = switch (state.get("type").getAsString()) {
            case "string" -> "strings";
            case "bool" -> "bools";
            default -> "ints";
        };
        return CodeBlock.of("$T.$L($S, $L)", BLOCK_PROPERTY, factory, name, CodeBlock.join(values, ", "));
    }
}
