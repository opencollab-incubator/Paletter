package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
 * inference, see {@code tools.model.infer.TraitStateTable}).
 */
final class TraitStateSource {
    private static final ClassName BLOCK_PROPERTY = ClassName.get("dev.opencollab.paletter.block", "BlockProperty");

    private TraitStateSource() {
    }

    static TypeSpec table(ApiModel model) {
        ClassName list = ClassName.get(List.class);
        CodeBlock.Builder body = CodeBlock.builder().add("return switch (trait + $S + value) {\n", " ").indent();
        // By name: the order of mappings.json does not matter
        for (Map.Entry<String, JsonElement> trait : new TreeMap<>(model.traitStates.asMap()).entrySet()) {
            for (Map.Entry<String, JsonElement> value : new TreeMap<>(trait.getValue().getAsJsonObject().asMap()).entrySet()) {
                List<CodeBlock> states = new ArrayList<>();
                for (JsonElement e : value.getValue().getAsJsonArray()) {
                    JsonObject state = e.getAsJsonObject();
                    body.add("// $L: values from $L; $L\n", state.get("name").getAsString(),
                        state.get("valuesFrom").getAsString(), state.get("default").getAsString());
                    if (state.has("disputes")) {
                        // A build whose own BDS data says other values: the table holds the newest build's for every version
                        state.getAsJsonArray("disputes").forEach(d -> body.add("// disputed: $L\n", d.getAsString()));
                    }
                    states.add(property(state));
                }
                body.add("case $S -> $T.of(\n$>$>$L$<$<);\n", trait.getKey() + " " + value.getKey(), list, CodeBlock.join(states, ",\n"));
            }
        }
        body.add("default -> throw new $T(trait + $S + value);\n", IllegalArgumentException.class, " has no enabled state ")
            .unindent().add("};\n");
        return TypeSpec.classBuilder("TraitStateTable").addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("The block states each trait adds, per value of its {@code enabled_states}, in order; the first value of a state\n"
                + "is its default. The client adds them (they are not on the wire). From {@code data/model/mappings.json}\n"
                + "{@code traitStates}: the names from the docs, the values from the vanilla state of the same name, the defaults\n"
                + "checked against the hashes BDS sent where a capture has one (each case says). Not API.\n")
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(MethodSpec.methodBuilder("of").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addJavadoc("@param trait the trait name, e.g. {@code minecraft:placement_direction}\n"
                    + "@param value a value of its {@code enabled_states}, e.g. {@code minecraft:cardinal_direction}\n"
                    + "@return the states it adds\n")
                .returns(ParameterizedTypeName.get(list, ParameterizedTypeName.get(BLOCK_PROPERTY, WildcardTypeName.subtypeOf(Object.class))))
                .addParameter(ApiSource.STRING, "trait").addParameter(ApiSource.STRING, "value")
                .addCode(body.build()).build())
            .build();
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
