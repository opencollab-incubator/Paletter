package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Comp;
import org.geysermc.paletter.tools.codegen.component.ApiModel.EnumRef;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Field;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Kind;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ListOf;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ObjRef;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ObjType;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Scalar;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ScalarKind;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Type;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Vec3;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Window;
import org.geysermc.paletter.tools.codegen.component.ComponentGenerator.GenerationException;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Writes the internal encoders with JavaPoet: per component, the inferred rules of each protocol window as straight line
 * code over the component record ({@code org.geysermc.paletter.internal.component.ItemWire}, {@code BlockWire},
 * {@code TraitWire}), and {@code ComponentIndex} (component names and JSON keys of every generated type).
 * <p>
 * Semantics of a window's mapping, per object level: the component's {@code constants} first; then every field in docs
 * order. A set field writes its value rules (identity, linear, table, affix, bitset, count) and the constants its presence
 * switches on (only for leaves no field owns: the others follow their owner); an unset field writes its {@code whenAbsent}
 * leaves (its own and its children's, never another field's). A field named in {@code transforms.json} calls the hand
 * transform of that name instead, with the record that holds it, after the generic fields of that record.
 */
final class WireSource {
    static final String PKG = "org.geysermc.paletter.internal.component";
    private static final ClassName WIRE = ClassName.get(PKG, "Wire");
    private static final ClassName WIRE_NODE = ClassName.get(PKG, "WireNode");
    private static final ClassName WIRE_CONTEXT = ClassName.get(PKG, "WireContext");
    private static final ClassName TRANSFORMS = ClassName.get(PKG, "Transforms");
    private static final ClassName NBT_MAP = ClassName.get("org.cloudburstmc.nbt", "NbtMap");

    private final Comp c;
    private CodeBlock.Builder b;
    private int vars;
    /** Value leaves of the window being written (owned leaves), and the field owning each. */
    private Set<String> owned = Set.of();
    private final Map<String, Field> ownerOf = new LinkedHashMap<>();
    private Window window;

    private WireSource(Comp c) {
        this.c = c;
    }

    static void write(ApiModel model, Map<String, String> files) {
        for (Kind kind : Kind.values()) {
            files.put(PKG.replace('.', '/') + "/" + wireClass(kind) + ".java", ApiSource.file(PKG, kindClass(kind, model)));
        }
        files.put(PKG.replace('.', '/') + "/ComponentIndex.java", ApiSource.file(PKG, index(model)));
    }

    static String wireClass(Kind kind) {
        return switch (kind) {
            case ITEM -> "ItemWire";
            case BLOCK -> "BlockWire";
            case TRAIT -> "TraitWire";
        };
    }

    private static TypeSpec kindClass(Kind kind, ApiModel model) {
        List<Comp> members = model.components.stream().filter(c -> c.kind == kind && !c.standalone).toList();
        ClassName iface = ClassName.get(kind.pkg(), kind.iface());
        TypeSpec.Builder type = TypeSpec.classBuilder(wireClass(kind)).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Encoders of the generated {@link $T} types: the rules of {@code data/model/mappings.json} per\nprotocol window, "
                + "hand transforms by name. Not API.\n", iface)
            .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class).addMember("value", "{$S, $S}", "unused", "deprecation").build())
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        CodeBlock.Builder dispatch = CodeBlock.builder().beginControlFlow("switch (component)");
        for (Comp c : members) {
            dispatch.addStatement("case $T value -> $N(value, ctx, out)", ApiSource.className(c), method(c));
        }
        if (kind == Kind.ITEM) {
            dispatch.addStatement("case $T value -> $T.write(value, ctx, out)", ClassName.get(kind.pkg(), "WireOnlyItemComponent"),
                ClassName.get(PKG, "WireOnlyWire"));
        }
        if (members.isEmpty()) {
            dispatch.addStatement("default -> throw new $T(component.toString())", IllegalArgumentException.class);
        }
        dispatch.endControlFlow();
        type.addMethod(MethodSpec.methodBuilder("write").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addJavadoc("Writes a component into {@code out}: the item components compound, the block's {@code components}, or for a\n"
                + "trait its element of {@code traits}.\n")
            .addParameter(ApiSource.nonNull(iface), "component").addParameter(ApiSource.nonNull(WIRE_CONTEXT), "ctx")
            .addParameter(ApiSource.nonNull(WIRE_NODE), "out").addCode(dispatch.build()).build());
        for (Comp c : members) {
            new WireSource(c).component(type);
        }
        return type.build();
    }

    static String method(Comp c) {
        return ComponentGenerator.camel(c.className);
    }

    // ---- One component -------------------------------------------------------------------------------------------------

    private MethodSpec.Builder encoder(String name) {
        return MethodSpec.methodBuilder(name).addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(ApiSource.className(c), "c").addParameter(WIRE_CONTEXT, "ctx").addParameter(WIRE_NODE, "out");
    }

    private void component(TypeSpec.Builder type) {
        String name = method(c);
        if (c.windows.size() <= 1) {
            b = CodeBlock.builder();
            windowBody(c.windows.isEmpty() ? null : c.windows.getFirst());
            type.addMethod(encoder(name).addCode(b.build()).build());
            return;
        }
        CodeBlock.Builder dispatch = CodeBlock.builder().addStatement("int protocol = ctx.protocol()");
        for (int i = 0; i < c.windows.size(); i++) {
            Window w = c.windows.get(i);
            List<String> conditions = new ArrayList<>();
            if (w.since() != Integer.MIN_VALUE) {
                conditions.add("protocol >= " + w.since());
            }
            if (w.until() != Integer.MAX_VALUE) {
                conditions.add("protocol < " + w.until());
            }
            if (i == 0) {
                dispatch.beginControlFlow("if ($L)", String.join(" && ", conditions));
            } else {
                dispatch.nextControlFlow("else if ($L)", String.join(" && ", conditions));
            }
            dispatch.addStatement("$N$L(c, ctx, out)", name, window(w));
        }
        dispatch.endControlFlow();
        type.addMethod(encoder(name).addCode(dispatch.build()).build());
        for (Window w : c.windows) {
            b = CodeBlock.builder();
            windowBody(w);
            type.addMethod(encoder(name + window(w)).addCode(b.build()).build());
        }
    }

    private static String window(Window w) {
        return (w.since() == Integer.MIN_VALUE ? "" : "From" + w.since()) + (w.until() == Integer.MAX_VALUE ? "" : "Until" + w.until());
    }

    private void windowBody(Window w) {
        this.window = w;
        this.owned = new TreeSet<>();
        this.ownerOf.clear();
        if (w != null) {
            collectOwned(c.root);
            for (Map.Entry<String, String> e : w.constants().entrySet()) {
                String path = strip(e.getKey());
                if (!path.contains("[]")) {
                    put("out", path, literal(e.getValue()));
                }
            }
        }
        if (c.transform != null) {
            b.addStatement("$T.$N(ctx, out, c)", TRANSFORMS, ComponentGenerator.camel(c.transform));
            return;
        }
        if (w == null) {
            b.addStatement("ctx.drop($T.NAME, $S)", ApiSource.className(c), "has no known wire mapping");
            return;
        }
        fields(c.root, "c", "out", "");
    }

    private void collectOwned(ObjType o) {
        for (Field f : fieldsWithAlternatives(o)) {
            if (f.transform != null) {
                // A transformed field owns every leaf its observations touch: its transform writes them
                JsonObject m = ComponentGenerator.mappingOf(window, f);
                if (m != null && m.has("rules")) {
                    for (JsonElement r : m.getAsJsonArray("rules")) {
                        String wire = strip(r.getAsJsonObject().get("wire").getAsString());
                        owned.add(wire);
                        ownerOf.putIfAbsent(wire, f);
                    }
                }
                for (String wire : ComponentGenerator.whenAbsent(window, f).keySet()) {
                    owned.add(strip(wire));
                    ownerOf.putIfAbsent(strip(wire), f);
                }
            }
            for (JsonObject r : rules(f)) {
                if (ComponentGenerator.VALUE_KINDS.contains(r.get("kind").getAsString())) {
                    owned.add(strip(r.get("wire").getAsString()));
                    ownerOf.putIfAbsent(strip(r.get("wire").getAsString()), f.alternativeOf != null ? f.alternativeOf : f);
                }
            }
            ObjType child = ComponentGenerator.objectOf(f.type);
            if (child != null && f.type instanceof ListOf) {
                String list = listPath(child, "");
                if (list != null) {
                    ownerOf.putIfAbsent(list, f);
                }
            }
            if (child != null) {
                collectOwned(child);
            }
        }
    }

    /** Wire paths of traits are relative to their element of {@code traits}. */
    private String strip(String wire) {
        if (c.kind == Kind.TRAIT && wire.startsWith("traits[].")) {
            return wire.substring("traits[].".length());
        }
        return wire;
    }

    private List<JsonObject> rules(Field f) {
        List<JsonObject> rules = new ArrayList<>(ComponentGenerator.rulesOf(window, f));
        if (f.type instanceof Vec3) {
            // The x/y/z children of the object form write the same leaves as the elements of the array form
            for (String axis : List.of("x", "y", "z")) {
                JsonObject m = window.field(f.path + "." + axis);
                if (m != null && m.has("rules")) {
                    for (JsonElement r : m.getAsJsonArray("rules")) {
                        JsonObject copy = r.getAsJsonObject().deepCopy();
                        if (ComponentGenerator.VALUE_KINDS.contains(copy.get("kind").getAsString())) {
                            copy.addProperty("element", List.of("x", "y", "z").indexOf(axis));
                            rules.add(copy);
                        }
                    }
                }
            }
            Map<String, JsonObject> byLeaf = new LinkedHashMap<>();
            for (JsonObject r : rules) {
                byLeaf.putIfAbsent(r.get("wire").getAsString() + " " + r.get("kind").getAsString(), r);
            }
            rules = new ArrayList<>(byLeaf.values());
        }
        return rules;
    }

    private static List<Field> fieldsWithAlternatives(ObjType o) {
        return ApiSource.allFields(o);
    }

    /**
     * @param value the Java expression of the record holding the fields
     * @param node the node variable leaves are written to
     * @param prefix the generic wire path of that node ("" or {@code ...list[]})
     */
    private void fields(ObjType o, String value, String node, String prefix) {
        Set<String> transformsCalled = new LinkedHashSet<>();
        for (Field f : fieldsWithAlternatives(o)) {
            if (f.transform == null) {
                field(f, value, node, prefix);
            }
        }
        // Transforms last: they see what the generic rules of their siblings wrote
        for (Field f : fieldsWithAlternatives(o)) {
            if (f.transform != null && transformsCalled.add(f.transform)) {
                b.addStatement("$T.$N(ctx, $N, $N)", TRANSFORMS, ComponentGenerator.camel(f.transform), node, value);
            }
        }
    }

    private void field(Field f, String owner, String node, String prefix) {
        String var = "v" + (vars++);
        boolean nullable = ApiSource.nullable(f);
        b.addStatement("$T $N = $N.$N()", ApiSource.typeName(c, f.type, nullable), var, owner, f.java);
        if (nullable) {
            b.beginControlFlow("if ($N != null)", var);
        }
        present(f, var, node, prefix);
        if (nullable) {
            CodeBlock.Builder present = b;
            b = CodeBlock.builder();
            absentLines(f, f, node, prefix, new TreeSet<>());
            CodeBlock absent = b.build();
            b = present;
            if (!absent.isEmpty()) {
                b.nextControlFlow("else");
                b.add(absent);
            }
            b.endControlFlow();
        }
    }

    private void present(Field f, String var, String node, String prefix) {
        List<JsonObject> rules = rules(f);
        boolean form = f.form != null;
        boolean mapped = false;
        for (JsonObject r : rules) {
            String kind = r.get("kind").getAsString();
            String wire = strip(r.get("wire").getAsString());
            if (kind.equals("constant")) {
                if ((form || !owned.contains(wire)) && placeable(wire, prefix)) {
                    String rel = relative(wire, prefix);
                    String v = r.get("value").getAsString();
                    if (v.equals("<not sent>")) {
                        b.addStatement("$N.remove($S)", node, rel);
                    } else if (r.has("nbt")) {
                        put(node, rel, literal(v + " (" + r.get("nbt").getAsString() + ")"));
                    }
                }
                continue;
            }
            if (!ComponentGenerator.VALUE_KINDS.contains(kind)) {
                continue;
            }
            mapped = true;
            value(f, r, var, node, prefix, wire);
        }
        if (f.type instanceof ObjRef r) {
            fields(r.wire(), var, node, prefix);
            return;
        }
        if (f.type instanceof ListOf l && l.element() instanceof ObjRef r) {
            String list = listPath(r.wire(), prefix);
            if (list == null) {
                if (!mapped) {
                    unmapped(f, var + ".isEmpty()");
                }
                return;
            }
            String rel = relative(list, prefix);
            String element = "e" + (vars++);
            String elementNode = "n" + (vars++);
            b.addStatement("$N.list($S)", node, rel);
            b.beginControlFlow("for ($T $N : $N)", ApiSource.className(c).nestedClass(r.type().name), element, var);
            b.addStatement("$T $N = $N.element($S)", WIRE_NODE, elementNode, node, rel);
            String elementPrefix = list + "[]";
            for (Map.Entry<String, String> e : window.constants().entrySet()) {
                String path = strip(e.getKey());
                if (path.startsWith(elementPrefix + ".") && !path.substring(elementPrefix.length() + 1).contains("[]")) {
                    put(elementNode, path.substring(elementPrefix.length() + 1), literal(e.getValue()));
                }
            }
            fields(r.wire(), element, elementNode, elementPrefix);
            b.endControlFlow();
            return;
        }
        if (!mapped && rules.stream().noneMatch(x -> x.get("kind").getAsString().equals("constant"))) {
            JsonObject m = ComponentGenerator.mappingOf(window, f);
            String status = m == null ? "none" : m.get("status").getAsString();
            if (!status.equals("dropped")) {
                unmapped(f, null);
            }
        }
    }

    private void unmapped(Field f, String emptyCheck) {
        String json = f.json == null ? f.java : f.path;
        if (f.defaultValue != null && emptyCheck == null) {
            return;
        }
        if (emptyCheck != null) {
            b.beginControlFlow("if (!$L)", emptyCheck);
        }
        b.addStatement("ctx.drop($T.NAME, $S)", ApiSource.className(c), json + " has no known wire mapping");
        if (emptyCheck != null) {
            b.endControlFlow();
        }
    }

    /** The wire list a list field's elements go to: the list part of any rule of a field inside the element. */
    private String listPath(ObjType element, String prefix) {
        int depth = prefix.isEmpty() ? 0 : prefix.split("\\[]", -1).length - 1;
        for (String wire : allWires(element)) {
            if (!prefix.isEmpty() && !wire.startsWith(prefix + ".")) {
                continue;
            }
            int at = -1;
            int from = 0;
            for (int i = 0; i <= depth; i++) {
                at = wire.indexOf("[]", from);
                if (at < 0) {
                    break;
                }
                from = at + 2;
            }
            if (at > 0) {
                return wire.substring(0, at);
            }
        }
        return null;
    }

    private List<String> allWires(ObjType o) {
        List<String> wires = new ArrayList<>();
        for (Field f : fieldsWithAlternatives(o)) {
            for (JsonObject r : ComponentGenerator.rulesOf(window, f)) {
                wires.add(strip(r.get("wire").getAsString()));
            }
            ObjType child = ComponentGenerator.objectOf(f.type);
            if (child != null) {
                wires.addAll(allWires(child));
            }
        }
        return wires;
    }

    private boolean placeable(String wire, String prefix) {
        if (!prefix.isEmpty() && !wire.startsWith(prefix + ".")) {
            return false;
        }
        String rel = prefix.isEmpty() ? wire : wire.substring(prefix.length() + 1);
        return !rel.contains("[]");
    }

    private String relative(String wire, String prefix) {
        return prefix.isEmpty() ? wire : wire.substring(prefix.length() + 1);
    }

    private void value(Field f, JsonObject r, String var, String node, String prefix, String wire) {
        String kind = r.get("kind").getAsString();
        String nbt = r.has("nbt") ? r.get("nbt").getAsString() : null;
        Type t = f.type;
        CodeBlock expr = CodeBlock.of("$N", var);
        // A whole list of scalars: the rule describes one element (wire ends with [])
        if (t instanceof ListOf l && !(l.element() instanceof ObjRef) && wire.endsWith("[]") && kind.equals("identity")) {
            String listWire = wire.substring(0, wire.length() - 2);
            checkPlaceable(f, listWire, prefix);
            put(node, relative(listWire, prefix), listValue(l.element(), nbt, var));
            return;
        }
        if (t instanceof Vec3 && wire.endsWith("[]") && kind.equals("identity")) {
            String listWire = wire.substring(0, wire.length() - 2);
            checkPlaceable(f, listWire, prefix);
            put(node, relative(listWire, prefix), CodeBlock.of("$T.floats($T.of($N.x(), $N.y(), $N.z()))", WIRE, ApiSource.LIST, var, var, var));
            return;
        }
        checkPlaceable(f, wire, prefix);
        String rel = relative(wire, prefix);
        boolean guarded = false;
        if (r.has("element") && !kind.equals("bitset")) {
            int k = r.get("element").getAsInt();
            if (t instanceof Vec3) {
                expr = CodeBlock.of("$N.$N()", var, List.of("x", "y", "z").get(k));
                t = new Scalar(ScalarKind.FLOAT);
            } else if (t instanceof ListOf l) {
                b.beginControlFlow("if ($N.size() > $L)", var, k);
                guarded = true;
                expr = CodeBlock.of("$N.get($L)", var, k);
                t = l.element();
            }
        }
        if (nbt == null) {
            throw new GenerationException(c.id + " " + f.path + ": the rule for " + wire + " has no NBT type");
        }
        switch (kind) {
            case "identity" -> put(node, rel, convert(t, expr, nbt, f));
            case "linear" -> {
                double scale = r.get("scale").getAsDouble();
                double offset = r.get("offset").getAsDouble();
                CodeBlock number = CodeBlock.of("($L * $L + $L)", decimal(scale), numeric(t, expr), decimal(offset));
                put(node, rel, fromNumber(nbt, number, f));
            }
            case "table" -> {
                CodeBlock key = key(t, expr);
                b.beginControlFlow("switch ($L)", key);
                JsonObject table = r.getAsJsonObject("table");
                for (String k : new TreeSet<>(table.keySet())) {
                    String v = table.get(k).getAsString();
                    if (v.equals("<not sent>")) {
                        b.addStatement("case $S -> $N.remove($S)", k, node, rel);
                    } else {
                        b.addStatement("case $S -> $N.put($S, $L)", k, node, rel, literal(v + " (" + nbt + ")"));
                    }
                }
                b.addStatement("default -> ctx.drop($T.NAME, $S + $L + $S)", ApiSource.className(c), f.path + " = ", key, " has no known wire value");
                b.endControlFlow();
            }
            case "affix" -> put(node, rel, CodeBlock.of("$S + $T.namespaced($L) + $S", r.get("prefix").getAsString(), WIRE, stringOf(t, expr),
                r.get("suffix").getAsString()));
            case "bitset" -> {
                if (!(t instanceof ListOf l)) {
                    throw new GenerationException(c.id + " " + f.path + ": a bitset rule needs a list field");
                }
                String bits = "bits" + (vars++);
                String e = "x" + (vars++);
                b.addStatement("int $N = 0", bits);
                b.beginControlFlow("for ($T $N : $N)", ApiSource.typeName(c, l.element(), true), e, var);
                CodeBlock.Builder sw = CodeBlock.builder().add("$N |= switch ($L) {\n", bits, stringOf(l.element(), CodeBlock.of("$N", e))).indent();
                JsonObject table = r.getAsJsonObject("bits");
                for (String k : new TreeSet<>(table.keySet())) {
                    sw.add("case $S -> $L;\n", k, table.get(k).getAsLong());
                }
                sw.add("default -> 0;\n").unindent().add("}");
                b.addStatement("$L", sw.build());
                b.endControlFlow();
                put(node, rel, fromNumber(nbt, CodeBlock.of("$N", bits), f));
            }
            case "count" -> put(node, rel, fromNumber(nbt, CodeBlock.of("$N.size()", var), f));
            default -> throw new GenerationException(c.id + " " + f.path + ": unknown rule kind " + kind);
        }
        if (guarded) {
            b.endControlFlow();
        }
    }

    private static String decimal(double d) {
        return Docs.number(d) + (d == Math.rint(d) ? ".0" : "");
    }

    private void checkPlaceable(Field f, String wire, String prefix) {
        if (!placeable(wire, prefix)) {
            throw new GenerationException("list depth mismatch: " + c.id + " " + f.path + " writes " + wire + " from "
                + (prefix.isEmpty() ? "the component" : prefix) + "; name a transform for it in transforms.json");
        }
    }

    private CodeBlock listValue(Type element, String nbt, String var) {
        return switch (element) {
            case EnumRef e -> CodeBlock.of("$T.strings($N.stream().map(x -> x.value()).toList())", WIRE, var);
            case Scalar s when s.kind() == ScalarKind.STRING -> CodeBlock.of("$T.strings($N)", WIRE, var);
            case Scalar s when s.kind() == ScalarKind.INT -> CodeBlock.of("$T.ints($N)", WIRE, var);
            case Scalar s when s.kind() == ScalarKind.FLOAT -> CodeBlock.of("$T.floats($N)", WIRE, var);
            default -> throw new GenerationException(c.id + ": unsupported list element " + element + " (" + nbt + ")");
        };
    }

    private CodeBlock key(Type t, CodeBlock expr) {
        return switch (t) {
            case EnumRef e -> CodeBlock.of("$L.value()", expr);
            case Scalar s when s.kind() == ScalarKind.STRING -> expr;
            case Scalar s when s.kind() == ScalarKind.BOOL -> CodeBlock.of("$T.valueOf($L)", String.class, expr);
            default -> CodeBlock.of("$T.key($L)", WIRE, expr);
        };
    }

    private CodeBlock stringOf(Type t, CodeBlock expr) {
        return t instanceof EnumRef ? CodeBlock.of("$L.value()", expr) : CodeBlock.of("$T.valueOf($L)", String.class, expr);
    }

    private static CodeBlock numeric(Type t, CodeBlock expr) {
        return t instanceof Scalar s && s.kind() == ScalarKind.BOOL ? CodeBlock.of("($L ? 1 : 0)", expr) : CodeBlock.of("(double) $L", expr);
    }

    private CodeBlock convert(Type t, CodeBlock expr, String nbt, Field f) {
        if (t instanceof EnumRef) {
            if (!nbt.equals("string")) {
                throw new GenerationException(c.id + " " + f.path + ": an enum written as " + nbt + " needs a table rule");
            }
            return CodeBlock.of("$L.value()", expr);
        }
        if (t instanceof Scalar s) {
            return switch (s.kind()) {
                case STRING -> {
                    if (!nbt.equals("string")) {
                        throw new GenerationException(c.id + " " + f.path + ": a string written as " + nbt);
                    }
                    yield expr;
                }
                case BOOL -> switch (nbt) {
                    case "byte" -> CodeBlock.of("$T.bool($L)", WIRE, expr);
                    case "string" -> CodeBlock.of("$T.valueOf($L)", String.class, expr);
                    default -> fromNumber(nbt, CodeBlock.of("($L ? 1 : 0)", expr), f);
                };
                case INT, FLOAT -> nbt.equals("string") ? CodeBlock.of("$T.valueOf($L)", String.class, expr) : fromNumber(nbt, expr, f);
            };
        }
        throw new GenerationException(c.id + " " + f.path + ": an identity rule on " + t + " needs a transform");
    }

    private CodeBlock fromNumber(String nbt, CodeBlock expr, Field f) {
        return switch (nbt) {
            case "byte" -> CodeBlock.of("$T.toByte($L)", WIRE, expr);
            case "short" -> CodeBlock.of("$T.toShort($L)", WIRE, expr);
            case "int" -> CodeBlock.of("$T.toInt($L)", WIRE, expr);
            case "long" -> CodeBlock.of("(long) ($L)", expr);
            case "float" -> CodeBlock.of("$T.toFloat($L)", WIRE, expr);
            case "double" -> CodeBlock.of("(double) ($L)", expr);
            default -> throw new GenerationException(c.id + " " + f.path + ": a number written as " + nbt);
        };
    }

    // ---- Absent fields -----------------------------------------------------------------------------------------------

    /** Whether a field is the subject or inside it. */
    private static boolean within(Field field, Field subject) {
        if (field == subject) {
            return true;
        }
        ObjType child = ComponentGenerator.objectOf(subject.type);
        if (child == null) {
            return false;
        }
        for (Field f : fieldsWithAlternatives(child)) {
            if (within(field, f)) {
                return true;
            }
        }
        return false;
    }

    private void absentLines(Field subject, Field f, String node, String prefix, Set<String> decided) {
        for (Map.Entry<String, String> e : ComponentGenerator.whenAbsent(window, f).entrySet()) {
            String wire = strip(e.getKey());
            // What BDS sends for another field's leaf when this one is omitted is that field's business
            Field owner = ownerOf.get(wire);
            if (owner != null && !within(owner, subject)) {
                continue;
            }
            if (!placeable(wire, prefix) || !decided.add(wire)) {
                continue;
            }
            String rel = relative(wire, prefix);
            if (e.getValue().equals("omitted")) {
                b.addStatement("$N.remove($S)", node, rel);
            } else {
                put(node, rel, literal(e.getValue()));
            }
        }
        ObjType child = f.type instanceof ObjRef r ? r.wire() : null;
        if (child != null) {
            for (Field cf : fieldsWithAlternatives(child)) {
                if (cf.transform == null) {
                    absentLines(subject, cf, node, prefix, decided);
                }
            }
        }
    }

    // ---- Literals ----------------------------------------------------------------------------------------------------

    private void put(String node, String path, CodeBlock value) {
        b.addStatement("$N.put($S, $L)", node, path, value);
    }

    /** A {@code value (type)} literal of mappings.json as Java. */
    static CodeBlock literal(String typed) {
        int at = typed.lastIndexOf(" (");
        if (at < 0 || !typed.endsWith(")")) {
            throw new GenerationException("not a typed wire value: " + typed);
        }
        String value = typed.substring(0, at);
        String type = typed.substring(at + 2, typed.length() - 1);
        return switch (type) {
            case "byte" -> CodeBlock.of("(byte) $L", Long.parseLong(value));
            case "short" -> CodeBlock.of("(short) $L", Long.parseLong(value));
            case "int" -> CodeBlock.of("$L", Long.parseLong(value));
            case "long" -> CodeBlock.of("$LL", Long.parseLong(value));
            case "float" -> CodeBlock.of("$L", Docs.floatLiteral(Double.parseDouble(value)));
            case "double" -> CodeBlock.of("$Ld", Double.parseDouble(value));
            case "string" -> CodeBlock.of("$S", value);
            case "compound" -> {
                if (!value.equals("{}")) {
                    throw new GenerationException("only empty compounds can be literals: " + typed);
                }
                yield CodeBlock.of("$T.EMPTY", NBT_MAP);
            }
            default -> {
                if (type.startsWith("list<") && value.equals("[]")) {
                    yield CodeBlock.of("$T.EMPTY_LIST", WIRE);
                }
                throw new GenerationException("unsupported wire literal: " + typed);
            }
        };
    }

    /**
     * Evaluates a value rule on a JSON value the way the encoder does, as a {@code value (type)} literal; null if the
     * rule cannot be evaluated on it.
     */
    static String evaluate(JsonObject rule, JsonElement json) {
        if (!rule.has("nbt") || json == null || !json.isJsonPrimitive()) {
            return null;
        }
        String nbt = rule.get("nbt").getAsString();
        JsonPrimitive p = json.getAsJsonPrimitive();
        String kind = rule.get("kind").getAsString();
        if (rule.has("element")) {
            return null;
        }
        switch (kind) {
            case "identity" -> {
                if (p.isBoolean()) {
                    return typed(p.getAsBoolean() ? 1 : 0, nbt);
                }
                if (p.isNumber()) {
                    return typed(p.getAsDouble(), nbt);
                }
                return nbt.equals("string") ? p.getAsString() + " (string)" : null;
            }
            case "linear" -> {
                if (!p.isNumber() && !p.isBoolean()) {
                    return null;
                }
                double x = p.isBoolean() ? (p.getAsBoolean() ? 1 : 0) : p.getAsDouble();
                return typed(rule.get("scale").getAsDouble() * x + rule.get("offset").getAsDouble(), nbt);
            }
            case "table" -> {
                JsonObject table = rule.getAsJsonObject("table");
                String k = p.isString() ? p.getAsString() : p.toString();
                return table.has(k) ? table.get(k).getAsString() + " (" + nbt + ")" : null;
            }
            case "affix" -> {
                String v = p.getAsString();
                return rule.get("prefix").getAsString() + (v.contains(":") ? v : "minecraft:" + v) + rule.get("suffix").getAsString() + " (string)";
            }
            default -> {
                return null;
            }
        }
    }

    private static String typed(double v, String nbt) {
        return switch (nbt) {
            case "byte" -> (byte) (long) v + " (byte)";
            case "short" -> (short) (long) v + " (short)";
            case "int" -> (int) (long) v + " (int)";
            case "long" -> (long) v + " (long)";
            case "float" -> (float) v + " (float)";
            case "double" -> v + " (double)";
            case "string" -> Docs.number(v) + " (string)";
            default -> null;
        };
    }

    // ---- Index -----------------------------------------------------------------------------------------------------------

    private static TypeSpec index(ApiModel model) {
        TypeName classMap = ParameterizedTypeName.get(ApiSource.MAP, ApiSource.STRING,
            ParameterizedTypeName.get(ClassName.get(Class.class), com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class)));
        TypeName keyMap = ParameterizedTypeName.get(ApiSource.MAP,
            ParameterizedTypeName.get(ClassName.get(Class.class), com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class)),
            ParameterizedTypeName.get(ApiSource.MAP, ApiSource.STRING, ApiSource.STRING));
        ClassName linked = ClassName.get(LinkedHashMap.class);
        TypeSpec.Builder type = TypeSpec.classBuilder("ComponentIndex").addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Every generated component type by add-on JSON name, and the JSON key of every field of every generated record\n"
                + "(record class -> JSON key -> record component). For tests and tooling; not API.\n");
        for (String name : List.of("ITEM", "BLOCK", "TRAIT", "STANDALONE")) {
            type.addField(FieldSpec.builder(classMap, name, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).initializer("new $T<>()", linked).build());
        }
        type.addField(FieldSpec.builder(keyMap, "JSON_KEYS", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).initializer("new $T<>()", linked).build());
        type.addField(FieldSpec.builder(keyMap, "FORMS", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("Synthetic fields of value forms: record class -> JSON type (boolean, string, ...) -> record component.\n")
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
            if (!forms.isEmpty()) {
                init.addStatement("FORMS.put($T.class, $T.of($L))", cls, ApiSource.MAP, CodeBlock.join(forms, ", "));
            }
        }
        type.addStaticBlock(init.build());
        type.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        type.addMethod(MethodSpec.methodBuilder("keys").addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(ParameterizedTypeName.get(ClassName.get(Class.class), com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class)), "type")
            .addParameter(com.palantir.javapoet.ArrayTypeName.of(ApiSource.STRING), "keys").varargs()
            .addStatement("$T<$T, $T> map = new $T<>()", ApiSource.MAP, ApiSource.STRING, ApiSource.STRING, linked)
            .beginControlFlow("for (int i = 0; i < keys.length; i += 2)").addStatement("map.put(keys[i], keys[i + 1])").endControlFlow()
            .addStatement("JSON_KEYS.put(type, $T.unmodifiableMap(map))", ClassName.get("java.util", "Collections")).build());
        return type.build();
    }

    private static void keys(CodeBlock.Builder init, ClassName cls, ObjType o) {
        List<CodeBlock> pairs = new ArrayList<>();
        for (Field f : o.fields) {
            if (f.json != null) {
                pairs.add(CodeBlock.of("$S, $S", f.json, f.java));
            }
        }
        init.addStatement("keys($T.class$L)", cls, pairs.isEmpty() ? "" : CodeBlock.of(", $L", CodeBlock.join(pairs, ", ")));
    }
}
