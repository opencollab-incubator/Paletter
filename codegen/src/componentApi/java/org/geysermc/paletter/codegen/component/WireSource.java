package org.geysermc.paletter.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.geysermc.paletter.codegen.component.ApiModel.Comp;
import org.geysermc.paletter.codegen.component.ApiModel.EnumRef;
import org.geysermc.paletter.codegen.component.ApiModel.Field;
import org.geysermc.paletter.codegen.component.ApiModel.Kind;
import org.geysermc.paletter.codegen.component.ApiModel.ListOf;
import org.geysermc.paletter.codegen.component.ApiModel.MapOf;
import org.geysermc.paletter.codegen.component.ApiModel.ObjRef;
import org.geysermc.paletter.codegen.component.ApiModel.ObjType;
import org.geysermc.paletter.codegen.component.ApiModel.Scalar;
import org.geysermc.paletter.codegen.component.ApiModel.ScalarKind;
import org.geysermc.paletter.codegen.component.ApiModel.States;
import org.geysermc.paletter.codegen.component.ApiModel.Type;
import org.geysermc.paletter.codegen.component.ApiModel.Vec3;
import org.geysermc.paletter.codegen.component.ApiModel.Window;
import org.geysermc.paletter.codegen.component.ComponentGenerator.GenerationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Writes the internal encoders: per component, the inferred rules of each protocol window as straight line code over the
 * component record ({@code org.geysermc.paletter.internal.component.ItemWire}, {@code BlockWire}, {@code TraitWire}), and
 * {@code ComponentIndex} (component names and JSON keys of every generated type).
 * <p>
 * Semantics of a window's mapping, per object level: the component's {@code constants} first; then every field in docs
 * order. A set field writes its value rules (identity, linear, table, affix, bitset, count) and the constants its presence
 * switches on (only for leaves no field owns: the others follow their owner); an unset field writes its {@code whenAbsent}
 * leaves (for an object, its own entries first, then its children's). A field named in {@code transforms.json} calls the
 * hand transform of that name instead, with the record that holds it.
 */
final class WireSource {
    private static final String PKG = "org.geysermc.paletter.internal.component";

    private final Comp c;
    private final StringBuilder out = new StringBuilder();
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
            files.put(PKG.replace('.', '/') + "/" + wireClass(kind) + ".java", kindClass(kind, model));
        }
        files.put(PKG.replace('.', '/') + "/ComponentIndex.java", index(model));
    }

    static String wireClass(Kind kind) {
        return switch (kind) {
            case ITEM -> "ItemWire";
            case BLOCK -> "BlockWire";
            case TRAIT -> "TraitWire";
        };
    }

    private static String kindClass(Kind kind, ApiModel model) {
        List<Comp> members = model.components.stream().filter(c -> c.kind == kind && !c.standalone).toList();
        StringBuilder s = new StringBuilder(ApiSource.HEADER);
        s.append("package ").append(PKG).append(";\n\n");
        s.append("import org.checkerframework.checker.nullness.qual.NonNull;\n");
        s.append("import org.cloudburstmc.nbt.NbtMap;\n");
        s.append("import ").append(kind.pkg()).append(".*;\n\n");
        s.append("/**\n * Encoders of the generated ").append(kind.iface()).append(" types: the rules of {@code codegen/model/mappings.json} per\n");
        s.append(" * protocol window, hand transforms by name. Not API.\n */\n");
        s.append("@SuppressWarnings({\"unused\", \"deprecation\"})\n");
        s.append("public final class ").append(wireClass(kind)).append(" {\n");
        s.append("    private ").append(wireClass(kind)).append("() {\n    }\n\n");
        s.append("    /**\n     * Writes a component into {@code out}: the item components compound, the block's {@code components}, or for a\n");
        s.append("     * trait its element of {@code traits}.\n     */\n");
        s.append("    public static void write(@NonNull ").append(kind.iface()).append(" component, @NonNull WireContext ctx, @NonNull WireNode out) {\n");
        s.append("        switch (component) {\n");
        for (Comp c : members) {
            s.append("            case ").append(c.className).append(" value -> ").append(method(c)).append("(value, ctx, out);\n");
        }
        if (members.isEmpty()) {
            s.append("            default -> throw new IllegalArgumentException(component.toString());\n");
        }
        s.append("        }\n    }\n");
        for (Comp c : members) {
            WireSource w = new WireSource(c);
            s.append('\n').append(w.component());
        }
        s.append("}\n");
        return s.toString();
    }

    static String method(Comp c) {
        return ComponentGenerator.camel(c.className);
    }

    // ---- One component -------------------------------------------------------------------------------------------------

    private String component() {
        String type = c.className;
        String name = method(c);
        if (c.windows.size() <= 1) {
            line(1, "private static void " + name + "(" + type + " c, WireContext ctx, WireNode out) {");
            if (!c.windows.isEmpty()) {
                windowBody(c.windows.getFirst(), 2);
            } else {
                body(null, 2);
            }
            line(1, "}");
            return out.toString();
        }
        line(1, "private static void " + name + "(" + type + " c, WireContext ctx, WireNode out) {");
        line(2, "int protocol = ctx.protocol();");
        for (int i = 0; i < c.windows.size(); i++) {
            Window w = c.windows.get(i);
            List<String> conditions = new ArrayList<>();
            if (w.since() != Integer.MIN_VALUE) {
                conditions.add("protocol >= " + w.since());
            }
            if (w.until() != Integer.MAX_VALUE) {
                conditions.add("protocol < " + w.until());
            }
            line(2, (i == 0 ? "if (" : "} else if (") + String.join(" && ", conditions) + ") {");
            line(3, name + window(w) + "(c, ctx, out);");
        }
        line(2, "}");
        line(1, "}");
        for (Window w : c.windows) {
            out.append('\n');
            line(1, "private static void " + name + window(w) + "(" + type + " c, WireContext ctx, WireNode out) {");
            windowBody(w, 2);
            line(1, "}");
        }
        return out.toString();
    }

    private static String window(Window w) {
        return (w.since() == Integer.MIN_VALUE ? "" : "From" + w.since()) + (w.until() == Integer.MAX_VALUE ? "" : "Until" + w.until());
    }

    private void windowBody(Window w, int indent) {
        this.window = w;
        this.owned = new TreeSet<>();
        this.ownerOf.clear();
        collectOwned(c.root);
        body(w, indent);
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

    private void body(Window w, int indent) {
        if (w != null) {
            for (Map.Entry<String, String> e : w.constants().entrySet()) {
                String path = strip(e.getKey());
                if (!path.contains("[]")) {
                    put(indent, "out", path, e.getValue());
                }
            }
        }
        if (c.transform != null) {
            line(indent, "Transforms." + ComponentGenerator.camel(c.transform) + "(ctx, out, c);");
            return;
        }
        if (w == null) {
            line(indent, "ctx.drop(" + c.className + ".NAME, \"has no known wire mapping\");");
            return;
        }
        fields(c.root, "c", "out", "", indent);
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
            // One rule per leaf
            Map<String, JsonObject> byLeaf = new LinkedHashMap<>();
            for (JsonObject r : rules) {
                String key = r.get("wire").getAsString() + " " + r.get("kind").getAsString();
                byLeaf.putIfAbsent(key, r);
            }
            rules = new ArrayList<>(byLeaf.values());
        }
        return rules;
    }

    private static List<Field> fieldsWithAlternatives(ObjType o) {
        List<Field> fields = new ArrayList<>();
        for (Field f : o.fields) {
            fields.add(f);
            fields.addAll(f.alternatives);
        }
        return fields;
    }

    /**
     * @param value the Java expression of the record holding the fields
     * @param node the node variable leaves are written to
     * @param prefix the generic wire path of that node ("" or {@code ...list[]})
     */
    private void fields(ObjType o, String value, String node, String prefix, int indent) {
        Set<String> transformsCalled = new LinkedHashSet<>();
        for (Field f : fieldsWithAlternatives(o)) {
            if (f.transform == null) {
                field(f, value, node, prefix, indent);
            }
        }
        // Transforms last: they see what the generic rules of their siblings wrote
        for (Field f : fieldsWithAlternatives(o)) {
            if (f.transform != null && transformsCalled.add(f.transform)) {
                line(indent, "Transforms." + ComponentGenerator.camel(f.transform) + "(ctx, " + node + ", " + value + ");");
            }
        }
    }

    private void field(Field f, String owner, String node, String prefix, int indent) {
        String var = "v" + (vars++);
        boolean nullable = f.nullable() || f.alternativeOf != null || !f.alternatives.isEmpty();
        String javaType = new ApiSourceTypes(c).type(f.type, nullable);
        line(indent, javaType + " " + var + " = " + owner + "." + f.java + "();");
        int in = indent;
        if (nullable) {
            line(indent, "if (" + var + " != null) {");
            in++;
        }
        present(f, var, node, prefix, in);
        if (nullable) {
            List<String> absent = new ArrayList<>();
            absentLines(f, node, prefix, absent, new TreeSet<>());
            if (!absent.isEmpty()) {
                line(indent, "} else {");
                for (String a : absent) {
                    line(indent + 1, a);
                }
            }
            line(indent, "}");
        }
    }

    private void present(Field f, String var, String node, String prefix, int indent) {
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
                        line(indent, node + ".remove(\"" + rel + "\");");
                    } else if (r.has("nbt")) {
                        put(indent, node, rel, v + " (" + r.get("nbt").getAsString() + ")");
                    }
                }
                continue;
            }
            if (!ComponentGenerator.VALUE_KINDS.contains(kind)) {
                continue;
            }
            mapped = true;
            value(f, r, var, node, prefix, wire, indent);
        }
        if (f.type instanceof ObjRef r) {
            fields(r.wire(), var, node, prefix, indent);
            return;
        }
        if (f.type instanceof ListOf l && l.element() instanceof ObjRef r) {
            String list = listPath(r.wire(), prefix);
            if (list == null) {
                if (!mapped) {
                    unmapped(f, var + ".isEmpty()", indent);
                }
                return;
            }
            String rel = relative(list, prefix);
            String element = "e" + (vars++);
            String elementNode = "n" + (vars++);
            line(indent, node + ".list(\"" + rel + "\");");
            line(indent, "for (" + c.className + "." + r.type().name + " " + element + " : " + var + ") {");
            line(indent + 1, "WireNode " + elementNode + " = " + node + ".element(\"" + rel + "\");");
            String elementPrefix = list + "[]";
            for (Map.Entry<String, String> e : window.constants().entrySet()) {
                String path = strip(e.getKey());
                if (path.startsWith(elementPrefix + ".") && !path.substring(elementPrefix.length() + 1).contains("[]")) {
                    put(indent + 1, elementNode, path.substring(elementPrefix.length() + 1), e.getValue());
                }
            }
            fields(r.wire(), element, elementNode, elementPrefix, indent + 1);
            line(indent, "}");
            return;
        }
        if (!mapped && rules.stream().noneMatch(x -> x.get("kind").getAsString().equals("constant"))) {
            JsonObject m = ComponentGenerator.mappingOf(window, f);
            String status = m == null ? "none" : m.get("status").getAsString();
            if (!status.equals("dropped")) {
                unmapped(f, null, indent);
            }
        }
    }

    private void unmapped(Field f, String emptyCheck, int indent) {
        String json = f.json == null ? f.java : f.path;
        String condition = emptyCheck == null ? "" : "if (!" + emptyCheck + ") ";
        if (f.defaultValue != null && emptyCheck == null) {
            return;
        }
        line(indent, condition + "ctx.drop(" + c.className + ".NAME, \"" + json.replace("\"", "'") + " has no known wire mapping\");");
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

    private void value(Field f, JsonObject r, String var, String node, String prefix, String wire, int indent) {
        String kind = r.get("kind").getAsString();
        String nbt = r.has("nbt") ? r.get("nbt").getAsString() : null;
        Type t = f.type;
        String expr = var;
        // A whole list of scalars: the rule describes one element (wire ends with [])
        if (t instanceof ListOf l && !(l.element() instanceof ObjRef) && wire.endsWith("[]") && kind.equals("identity")) {
            String listWire = wire.substring(0, wire.length() - 2);
            checkPlaceable(f, listWire, prefix);
            String elementType = l.element() instanceof EnumRef ? "string" : nbt;
            line(indent, node + ".put(\"" + relative(listWire, prefix) + "\", " + listValue(l.element(), elementType, var) + ");");
            return;
        }
        if (t instanceof Vec3 && wire.endsWith("[]") && kind.equals("identity")) {
            String listWire = wire.substring(0, wire.length() - 2);
            checkPlaceable(f, listWire, prefix);
            line(indent, node + ".put(\"" + relative(listWire, prefix) + "\", Wire.floats(java.util.List.of(" + var + ".x(), " + var + ".y(), "
                + var + ".z())));");
            return;
        }
        checkPlaceable(f, wire, prefix);
        String rel = relative(wire, prefix);
        if (r.has("element") && !kind.equals("bitset")) {
            int k = r.get("element").getAsInt();
            if (t instanceof Vec3) {
                expr = var + "." + List.of("x", "y", "z").get(k) + "()";
                t = new Scalar(ScalarKind.FLOAT);
            } else if (t instanceof ListOf l) {
                line(indent, "if (" + var + ".size() > " + k + ") {");
                indent++;
                expr = var + ".get(" + k + ")";
                t = l.element();
            }
        }
        if (nbt == null) {
            throw new GenerationException(c.id + " " + f.path + ": the rule for " + wire + " has no NBT type");
        }
        switch (kind) {
            case "identity" -> line(indent, node + ".put(\"" + rel + "\", " + convert(t, expr, nbt, f) + ");");
            case "linear" -> {
                double scale = r.get("scale").getAsDouble();
                double offset = r.get("offset").getAsDouble();
                String number = "(" + Docs.number(scale) + (scale == Math.rint(scale) ? ".0" : "") + " * " + numeric(t, expr) + " + "
                    + Docs.number(offset) + (offset == Math.rint(offset) ? ".0" : "") + ")";
                line(indent, node + ".put(\"" + rel + "\", " + fromNumber(nbt, number, f) + ");");
            }
            case "table" -> {
                String key = key(t, expr);
                line(indent, "switch (" + key + ") {");
                JsonObject table = r.getAsJsonObject("table");
                for (String k : table.keySet()) {
                    String v = table.get(k).getAsString();
                    if (v.equals("<not sent>")) {
                        line(indent + 1, "case " + Docs.string(k) + " -> " + node + ".remove(\"" + rel + "\");");
                    } else {
                        line(indent + 1, "case " + Docs.string(k) + " -> " + node + ".put(\"" + rel + "\", " + literal(v + " (" + nbt + ")") + ");");
                    }
                }
                line(indent + 1, "default -> ctx.drop(" + c.className + ".NAME, \"" + f.path.replace("\"", "'") + " = \" + " + key
                    + " + \" has no known wire value\");");
                line(indent, "}");
            }
            case "affix" -> line(indent, node + ".put(\"" + rel + "\", " + Docs.string(r.get("prefix").getAsString())
                + " + Wire.namespaced(" + stringOf(t, expr) + ") + " + Docs.string(r.get("suffix").getAsString()) + ");");
            case "bitset" -> {
                if (!(t instanceof ListOf l)) {
                    throw new GenerationException(c.id + " " + f.path + ": a bitset rule needs a list field");
                }
                String bits = "bits" + (vars++);
                line(indent, "int " + bits + " = 0;");
                String e = "x" + (vars++);
                line(indent, "for (" + new ApiSourceTypes(c).type(l.element(), true) + " " + e + " : " + var + ") {");
                line(indent + 1, bits + " |= switch (" + stringOf(l.element(), e) + ") {");
                JsonObject table = r.getAsJsonObject("bits");
                for (String k : table.keySet()) {
                    line(indent + 2, "case " + Docs.string(k) + " -> " + table.get(k).getAsLong() + ";");
                }
                line(indent + 2, "default -> 0;");
                line(indent + 1, "};");
                line(indent, "}");
                line(indent, node + ".put(\"" + rel + "\", " + fromNumber(nbt, bits, f) + ");");
            }
            case "count" -> line(indent, node + ".put(\"" + rel + "\", " + fromNumber(nbt, var + ".size()", f) + ");");
            default -> throw new GenerationException(c.id + " " + f.path + ": unknown rule kind " + kind);
        }
        if (r.has("element") && !kind.equals("bitset") && f.type instanceof ListOf) {
            line(indent - 1, "}");
        }
    }

    private void checkPlaceable(Field f, String wire, String prefix) {
        if (!placeable(wire, prefix)) {
            throw new GenerationException("list depth mismatch: " + c.id + " " + f.path + " writes " + wire + " from "
                + (prefix.isEmpty() ? "the component" : prefix) + "; name a transform for it in transforms.json");
        }
    }

    private String listValue(Type element, String nbt, String var) {
        return switch (element) {
            case EnumRef e -> "Wire.strings(" + var + ".stream().map(x -> x.value()).toList())";
            case Scalar s when s.kind() == ScalarKind.STRING -> "Wire.strings(" + var + ")";
            case Scalar s when s.kind() == ScalarKind.INT -> "Wire.ints(" + var + ")";
            case Scalar s when s.kind() == ScalarKind.FLOAT -> "Wire.floats(" + var + ")";
            default -> throw new GenerationException(c.id + ": unsupported list element " + element + " (" + nbt + ")");
        };
    }

    private String key(Type t, String expr) {
        return switch (t) {
            case EnumRef e -> expr + ".value()";
            case Scalar s when s.kind() == ScalarKind.STRING -> expr;
            case Scalar s when s.kind() == ScalarKind.BOOL -> "String.valueOf(" + expr + ")";
            default -> "Wire.key(" + expr + ")";
        };
    }

    private String stringOf(Type t, String expr) {
        return t instanceof EnumRef ? expr + ".value()" : "String.valueOf(" + expr + ")";
    }

    private static String numeric(Type t, String expr) {
        return t instanceof Scalar s && s.kind() == ScalarKind.BOOL ? "(" + expr + " ? 1 : 0)" : "(double) " + expr;
    }

    private String convert(Type t, String expr, String nbt, Field f) {
        if (t instanceof EnumRef) {
            if (!nbt.equals("string")) {
                throw new GenerationException(c.id + " " + f.path + ": an enum written as " + nbt + " needs a table rule");
            }
            return expr + ".value()";
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
                    case "byte" -> "Wire.bool(" + expr + ")";
                    case "string" -> "String.valueOf(" + expr + ")";
                    default -> fromNumber(nbt, "(" + expr + " ? 1 : 0)", f);
                };
                case INT, FLOAT -> nbt.equals("string") ? "String.valueOf(" + expr + ")" : fromNumber(nbt, expr, f);
            };
        }
        throw new GenerationException(c.id + " " + f.path + ": an identity rule on " + t + " needs a transform");
    }

    private String fromNumber(String nbt, String expr, Field f) {
        return switch (nbt) {
            case "byte" -> "Wire.toByte(" + expr + ")";
            case "short" -> "Wire.toShort(" + expr + ")";
            case "int" -> "Wire.toInt(" + expr + ")";
            case "long" -> "(long) (" + expr + ")";
            case "float" -> "Wire.toFloat(" + expr + ")";
            case "double" -> "(double) (" + expr + ")";
            default -> throw new GenerationException(c.id + " " + f.path + ": a number written as " + nbt);
        };
    }

    // ---- Absent fields -----------------------------------------------------------------------------------------------

    private void absentLines(Field f, String node, String prefix, List<String> lines, Set<String> decided) {
        absentLines(f, f, node, prefix, lines, decided);
    }

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

    private void absentLines(Field subject, Field f, String node, String prefix, List<String> lines, Set<String> decided) {
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
                lines.add(node + ".remove(\"" + rel + "\");");
            } else {
                lines.add(node + ".put(\"" + rel + "\", " + literal(e.getValue()) + ");");
            }
        }
        ObjType child = f.type instanceof ObjRef r ? r.wire() : null;
        if (child != null) {
            for (Field cf : fieldsWithAlternatives(child)) {
                if (cf.transform == null) {
                    absentLines(subject, cf, node, prefix, lines, decided);
                }
            }
        }
    }

    // ---- Literals ----------------------------------------------------------------------------------------------------

    private void put(int indent, String node, String path, String typedValue) {
        line(indent, node + ".put(\"" + path + "\", " + literal(typedValue) + ");");
    }

    /** A {@code value (type)} literal of mappings.json as Java. */
    static String literal(String typed) {
        int at = typed.lastIndexOf(" (");
        if (at < 0 || !typed.endsWith(")")) {
            throw new GenerationException("not a typed wire value: " + typed);
        }
        String value = typed.substring(0, at);
        String type = typed.substring(at + 2, typed.length() - 1);
        return switch (type) {
            case "byte" -> "(byte) " + Long.parseLong(value);
            case "short" -> "(short) " + Long.parseLong(value);
            case "int" -> String.valueOf(Long.parseLong(value));
            case "long" -> Long.parseLong(value) + "L";
            case "float" -> Docs.floatLiteral(Double.parseDouble(value));
            case "double" -> Double.parseDouble(value) + "d";
            case "string" -> Docs.string(value);
            case "compound" -> {
                if (!value.equals("{}")) {
                    throw new GenerationException("only empty compounds can be literals: " + typed);
                }
                yield "NbtMap.EMPTY";
            }
            default -> {
                if (type.startsWith("list<") && value.equals("[]")) {
                    yield "Wire.EMPTY_LIST";
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

    private void line(int indent, String text) {
        out.append("    ".repeat(indent)).append(text).append('\n');
    }

    // ---- Index -----------------------------------------------------------------------------------------------------------

    private static String index(ApiModel model) {
        StringBuilder s = new StringBuilder(ApiSource.HEADER);
        s.append("package ").append(PKG).append(";\n\n");
        s.append("import java.util.Collections;\nimport java.util.LinkedHashMap;\nimport java.util.Map;\n\n");
        s.append("/**\n * Every generated component type by add-on JSON name, and the JSON key of every field of every generated record\n");
        s.append(" * (record class -> JSON key -> record component). For tests and tooling; not API.\n */\n");
        s.append("public final class ComponentIndex {\n");
        s.append("    public static final Map<String, Class<?>> ITEM = new LinkedHashMap<>();\n");
        s.append("    public static final Map<String, Class<?>> BLOCK = new LinkedHashMap<>();\n");
        s.append("    public static final Map<String, Class<?>> TRAIT = new LinkedHashMap<>();\n");
        s.append("    public static final Map<String, Class<?>> STANDALONE = new LinkedHashMap<>();\n");
        s.append("    public static final Map<Class<?>, Map<String, String>> JSON_KEYS = new LinkedHashMap<>();\n");
        s.append("    /** Synthetic fields of value forms: record class -> JSON type (boolean, string, ...) -> record component. */\n");
        s.append("    public static final Map<Class<?>, Map<String, String>> FORMS = new LinkedHashMap<>();\n\n");
        s.append("    static {\n");
        for (Comp c : model.components) {
            String map = c.standalone ? "STANDALONE" : c.kind.name();
            String cls = c.kind.pkg() + "." + c.className;
            s.append("        ").append(map).append(".put(\"").append(c.standalone ? c.id : c.name).append("\", ").append(cls).append(".class);\n");
            keys(s, cls, c.root);
            for (ObjType o : c.types.values()) {
                keys(s, cls + "." + o.name, o);
            }
            List<String> forms = new ArrayList<>();
            for (Field f : c.root.fields) {
                if (f.form != null) {
                    forms.add("\"" + f.form + "\", \"" + f.java + "\"");
                }
            }
            if (!forms.isEmpty()) {
                s.append("        FORMS.put(").append(cls).append(".class, Map.of(").append(String.join(", ", forms)).append("));\n");
            }
        }
        s.append("    }\n\n    private ComponentIndex() {\n    }\n\n");
        s.append("    private static void keys(Class<?> type, String... keys) {\n");
        s.append("        Map<String, String> map = new LinkedHashMap<>();\n");
        s.append("        for (int i = 0; i < keys.length; i += 2) {\n            map.put(keys[i], keys[i + 1]);\n        }\n");
        s.append("        JSON_KEYS.put(type, Collections.unmodifiableMap(map));\n    }\n}\n");
        return s.toString();
    }

    private static void keys(StringBuilder s, String cls, ObjType o) {
        List<String> pairs = new ArrayList<>();
        for (Field f : o.fields) {
            if (f.json != null) {
                pairs.add(Docs.string(f.json));
                pairs.add(Docs.string(f.java));
            }
        }
        s.append("        keys(").append(cls).append(".class").append(pairs.isEmpty() ? "" : ", " + String.join(", ", pairs)).append(");\n");
    }

    /** Java types of the model, as {@link ApiSource} writes them (without imports or annotations). */
    private record ApiSourceTypes(Comp c) {
        String type(Type t, boolean boxed) {
            return switch (t) {
                case Scalar s -> boxed ? s.kind().boxed : s.kind().primitive;
                case EnumRef e -> c.className + "." + e.type().name;
                case ObjRef r -> c.className + "." + r.type().name;
                case Vec3 v -> c.className + ".Vec3";
                case ListOf l -> "java.util.List<" + type(l.element(), true) + ">";
                case MapOf m -> "java.util.Map<String, " + type(m.value(), true) + ">";
                case States s -> "java.util.Map<String, Object>";
                case ApiModel.ComponentRef r -> c.kind.pkg() + "." + r.className();
            };
        }
    }
}
