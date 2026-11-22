package dev.opencollab.paletter.tools.codegen.component;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Field;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Scalar;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ScalarKind;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Writes {@code ComponentIndex}: every generated component type by add-on JSON name, the JSON keys, value forms and
 * descriptor fields of every generated class, and the protocol constants of the spec captures.
 */
final class IndexSource {
    private IndexSource() {
    }

    static TypeSpec index(ApiModel model) {
        TypeName classMap = ParameterizedTypeName.get(ApiSource.MAP, ApiSource.STRING,
            ParameterizedTypeName.get(ClassName.get(Class.class), com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class)));
        TypeName keyMap = ParameterizedTypeName.get(ApiSource.MAP,
            ParameterizedTypeName.get(ClassName.get(Class.class), com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class)),
            ParameterizedTypeName.get(ApiSource.MAP, ApiSource.STRING, ApiSource.STRING));
        ClassName linked = ClassName.get(LinkedHashMap.class);
        TypeSpec.Builder type = TypeSpec.classBuilder("ComponentIndex").addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Every generated component type by add-on JSON name, and the JSON key of every field of every generated class\n"
                + "(class -> JSON key -> accessor, in the order of the builder's fields). For tests and tooling; not API.\n");
        for (String name : List.of("ITEM", "BLOCK", "TRAIT", "STANDALONE")) {
            type.addField(FieldSpec.builder(classMap, name, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).initializer("new $T<>()", linked).build());
        }
        type.addField(FieldSpec.builder(keyMap, "JSON_KEYS", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).initializer("new $T<>()", linked).build());
        type.addField(FieldSpec.builder(keyMap, "FORMS", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("Synthetic fields of value forms: class -> JSON type (boolean, string, ...) -> accessor. An accessor\n"
                + "ending in {@code []} is the list field the value form is one entry of (the object form of a list).\n")
            .initializer("new $T<>()", linked).build());
        type.addField(FieldSpec.builder(keyMap, "DESCRIPTORS", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("The item and block descriptor fields (api.json {@code descriptors}) that are a name: record class -> record component ->\n"
                + "{@code item} or {@code block}. Add-on JSON writes a descriptor as a name or as an object with {@code name}.\n")
            .initializer("new $T<>()", linked).build());
        CodeBlock.Builder init = CodeBlock.builder();
        for (Comp c : model.components) {
            String map = c.standalone ? "STANDALONE" : c.kind.name();
            ClassName cls = ApiSource.className(c);
            init.addStatement("$N.put($S, $T.class)", map, c.standalone ? c.id : c.name, cls);
            keys(init, cls, c.root);
            for (ObjType o : c.types.values()) {
                keys(init, cls.nestedClass(o.name), o);
            }
            List<CodeBlock> forms = new ArrayList<>();
            for (Field f : c.root.fields) {
                if (f.form != null) {
                    forms.add(CodeBlock.of("$S, $S", f.form, f.java));
                }
            }
            c.elementForms.forEach((form, list) -> forms.add(CodeBlock.of("$S, $S", form, list + "[]")));
            if (!forms.isEmpty()) {
                init.addStatement("FORMS.put($T.class, $L)", cls, mapOf(forms));
            }
            descriptors(init, cls, c.root);
            for (ObjType o : c.types.values()) {
                descriptors(init, cls.nestedClass(o.name), o);
            }
        }
        type.addStaticBlock(init.build());
        type.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        type.addField(FieldSpec.builder(int.class, "OLDEST_CAPTURED_PROTOCOL", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("The oldest protocol a spec capture of BDS exists for: the shapes in {@code data/model/mappings.json} were observed from here.\n")
            .initializer("$L", model.oldestCaptured == Integer.MIN_VALUE ? "Integer.MIN_VALUE" : String.valueOf(model.oldestCaptured)).build());
        type.addField(FieldSpec.builder(int.class, "NEWEST_CAPTURED_PROTOCOL", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("The newest protocol a spec capture of BDS exists for: a palette of a newer protocol is encoded with the shape observed here.\n")
            .initializer("$L", model.newestCaptured == Integer.MAX_VALUE ? "Integer.MAX_VALUE" : String.valueOf(model.newestCaptured)).build());
        // Named by the data version, not the BDS build: a recapture of the same version with another build keeps the name
        model.windowProtocols.forEach((bds, protocol) -> {
            String version = model.dataVersions.get(protocol);
            if (version == null) {
                throw new GenerationException("the capture of BDS " + bds + " (window protocol " + protocol + ") names no data version");
            }
            type.addField(FieldSpec.builder(int.class, "V" + version.replace('.', '_'), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("The first protocol of data version $L, the window of its spec capture of BDS $L ({@code data/bds/$L/spec}): what\n"
                    + "the hand transforms compare {@code WireContext.protocol()} with where that version changed the wire.\n", version, bds, bds)
                .initializer("$L", protocol).build());
        });
        String captures = String.join(", ", model.windowProtocols.keySet());
        if (model.firstCustomBlockId != null) {
            type.addField(FieldSpec.builder(int.class, "CUSTOM_BLOCK_ID_START", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("The {@code vanilla_block_data.block_id} of the first custom block: BDS numbers custom blocks from here, in\n"
                    + "every spec capture ($L).\n", captures)
                .initializer("$L", model.firstCustomBlockId).build());
        }
        if (model.blockItemIdPlusBlockId != null) {
            type.addField(FieldSpec.builder(int.class, "BLOCK_ITEM_ID_PLUS_BLOCK_ID", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("The item of a custom block has the runtime id {@code BLOCK_ITEM_ID_PLUS_BLOCK_ID - block_id} in every spec\n"
                    + "capture ($L).\n", captures)
                .initializer("$L", model.blockItemIdPlusBlockId).build());
        }
        type.addMethod(MethodSpec.methodBuilder("keys").addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(ParameterizedTypeName.get(ClassName.get(Class.class), com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class)), "type")
            .addParameter(com.palantir.javapoet.ArrayTypeName.of(ApiSource.STRING), "keys").varargs()
            .addStatement("$T<$T, $T> map = new $T<>()", ApiSource.MAP, ApiSource.STRING, ApiSource.STRING, linked)
            .beginControlFlow("for (int i = 0; i < keys.length; i += 2)").addStatement("map.put(keys[i], keys[i + 1])").endControlFlow()
            .addStatement("JSON_KEYS.put(type, $T.unmodifiableMap(map))", ClassName.get("java.util", "Collections")).build());
        return type.build();
    }

    private static void descriptors(CodeBlock.Builder init, ClassName cls, ObjType o) {
        List<CodeBlock> pairs = new ArrayList<>();
        for (Field f : o.fields) {
            if (f.descriptor != null && f.type instanceof Scalar s && s.kind() == ScalarKind.STRING) {
                pairs.add(CodeBlock.of("$S, $S", f.java, f.descriptor));
            }
        }
        if (!pairs.isEmpty()) {
            init.addStatement("DESCRIPTORS.put($T.class, $L)", cls, mapOf(pairs));
        }
    }

    /** {@code Map.of(k, v, ...)}, which takes at most ten pairs; {@code Map.ofEntries(Map.entry(k, v), ...)} past that. */
    static CodeBlock mapOf(List<CodeBlock> pairs) {
        if (pairs.size() <= 10) {
            return CodeBlock.of("$T.of($L)", ApiSource.MAP, CodeBlock.join(pairs, ", "));
        }
        List<CodeBlock> entries = pairs.stream().map(p -> CodeBlock.of("$T.entry($L)", ApiSource.MAP, p)).toList();
        return CodeBlock.of("$T.ofEntries($L)", ApiSource.MAP, CodeBlock.join(entries, ", "));
    }

    private static void keys(CodeBlock.Builder init, ClassName cls, ObjType o) {
        List<CodeBlock> pairs = new ArrayList<>();
        for (Field f : o.fields) {
            if (f.json != null && f.keyedExtraOf == null) {
                pairs.add(CodeBlock.of("$S, $S", f.json, f.java));
            }
        }
        init.addStatement("keys($T.class$L)", cls, pairs.isEmpty() ? "" : CodeBlock.of(", $L", CodeBlock.join(pairs, ", ")));
    }
}
