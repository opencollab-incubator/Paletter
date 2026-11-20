package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.palantir.javapoet.CodeBlock;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Field;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Kind;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ListOf;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.MapOf;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Scalar;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ScalarKind;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Type;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Vec3;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Window;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import dev.opencollab.paletter.tools.model.rule.Constant;
import dev.opencollab.paletter.tools.model.rule.Descriptor;
import dev.opencollab.paletter.tools.model.rule.EmptyMap;
import dev.opencollab.paletter.tools.model.rule.Identity;
import dev.opencollab.paletter.tools.model.rule.Molang;
import dev.opencollab.paletter.tools.model.rule.Rule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static dev.opencollab.paletter.tools.codegen.component.WireSource.NBT_MAP;
import static dev.opencollab.paletter.tools.codegen.component.WireSource.TRANSFORMS;
import static dev.opencollab.paletter.tools.codegen.component.WireSource.WIRE;
import static dev.opencollab.paletter.tools.codegen.component.WireSource.WIRE_NODE;
import static dev.opencollab.paletter.tools.codegen.component.WireValues.literal;

/**
 * The body of one protocol window of a component's encoder: the window's constants, then per field its value rules or
 * its absent leaves, then the hand transforms (see {@link WireSource} for the semantics). The window, the leaves fields
 * own in it and the owner of each are fixed when it is made; only the code being written grows.
 */
final class WindowWire {
    private final Comp c;
    /** The window, or null for a component without a mapping. */
    private final Window window;
    /** Whether the encoder is called from outside (a standalone type of the definition core): it handles a null value. */
    private final boolean exposed;
    /** The leaves a transformed field writes in any window of the component ({@link ComponentWire#transformedLeaves}). */
    private final Set<String> transformed;
    /** Value leaves of the window (owned leaves), and the field owning each. */
    private final Set<String> owned = new TreeSet<>();
    private final Map<String, Field> ownerOf = new LinkedHashMap<>();

    private CodeBlock.Builder b = CodeBlock.builder();
    private int vars;

    WindowWire(Comp c, Window window, boolean exposed, Set<String> transformed) {
        this.c = c;
        this.window = window;
        this.exposed = exposed;
        this.transformed = transformed;
        if (window != null && window.sent()) {
            collectOwned(c.root, false);
            collectOwned(c.root, true);
        }
    }

    /** @return the code of the window's encoder */
    CodeBlock body() {
        Window w = window;
        if (w != null && !w.sent()) {
            // BDS of these protocols does not send the component (server only, or it rejects it): leave it out, say so.
            // Server only in every protocol, it is no error to use: nothing to report
            if (w.absent()) {
                b.addStatement("ctx.drop($L, $S)", name(), "does not exist in this Bedrock version");
            } else if (c.serverOnly != null && !c.serverOnly.bounded()) {
                b.add("// server only: BDS sends nothing to clients\n");
            } else {
                b.addStatement("ctx.drop($L, $S)", name(), "is not sent to clients (BDS: " + w.status() + ")");
            }
            return b.build();
        }
        if (exposed && w != null) {
            absentObject(w);
        }
        if (w != null) {
            for (Map.Entry<String, String> e : w.constants().entrySet()) {
                String path = strip(e.getKey());
                if (!path.contains("[]") && transformed.stream().noneMatch(leaf -> path.equals(leaf) || path.startsWith(leaf + "."))) {
                    put("out", path, literal(e.getValue()));
                }
            }
        }
        if (c.transform != null) {
            transformCall(c.transform, "out", "c");
            return b.build();
        }
        if (w == null) {
            b.addStatement("ctx.drop($L, $S)", name(), "has no known wire mapping");
            return b.build();
        }
        fields(c.root, "c", "out", "");
        return b.build();
    }

    /**
     * For a standalone type: without it ({@code c} null) the definition gets what BDS sent when its object was left out,
     * the absent values of the object and of each of its fields.
     */
    private void absentObject(Window w) {
        Map<String, String> absent = new TreeMap<>();
        JsonObject fields = w.mapping().has("fields") ? w.mapping().getAsJsonObject("fields") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : fields.entrySet()) {
            JsonObject field = e.getValue().getAsJsonObject();
            if ((e.getKey().equals(c.standalonePath) || e.getKey().startsWith(c.standalonePath + ".")) && field.has("whenAbsent")) {
                field.getAsJsonObject("whenAbsent").entrySet().forEach(a -> {
                    String before = absent.putIfAbsent(a.getKey(), a.getValue().getAsString());
                    if (before != null && !before.equals(a.getValue().getAsString())) {
                        throw new GenerationException(c.id + ": " + a.getKey() + " is " + before + " and " + a.getValue().getAsString()
                            + " without " + c.standalonePath);
                    }
                });
            }
        }
        b.beginControlFlow("if (c == null)");
        absent.forEach((path, value) -> {
            if (value.equals("omitted")) {
                b.addStatement("out.remove($S)", path);
            } else {
                put("out", path, literal(value));
            }
        });
        b.addStatement("return");
        b.endControlFlow();
    }

    /** What a report entry names: the {@code NAME} of the component, or of a standalone type ({@code menu_category}). */
    CodeBlock name() {
        return CodeBlock.of("$T.NAME", ApiSource.className(c));
    }

    /** A call of a hand transform; it checks the protocol itself where BDS builds differ. */
    private void transformCall(String transform, String node, String value) {
        b.addStatement("$T.$N(ctx, $N, $N)", TRANSFORMS, transform, node, value);
    }

    /**
     * Who writes each leaf: the field whose value rule targets it, else the transformed field whose observations touch it.
     * A value rule comes first (the pass with {@code transformed} false): the variants of a transformed field can move a
     * sibling's leaf (they are compared with the control when BDS rejected the base), and the sibling's own rule still
     * writes that leaf.
     */
    private void collectOwned(ObjType o, boolean transformed) {
        for (Field f : o.fields) {
            if (transformed && (f.transform != null || WireRules.writesHolder(window, f))) {
                for (Rule r : Rule.all(WireRules.mappingOf(window, f))) {
                    String wire = strip(r.wire);
                    owned.add(wire);
                    ownerOf.putIfAbsent(wire, f);
                }
                for (String wire : WireRules.whenAbsent(window, f).keySet()) {
                    owned.add(strip(wire));
                    ownerOf.putIfAbsent(strip(wire), f);
                }
            }
            ObjType child = WireRules.objectOf(f.type);
            if (!transformed) {
                for (Rule r : rules(f)) {
                    if (r.writesValue()) {
                        owned.add(strip(r.wire));
                        ownerOf.putIfAbsent(strip(r.wire), f);
                        if (r instanceof Descriptor d) {
                            d.leaves().forEach(leaf -> {
                                owned.add(strip(leaf));
                                ownerOf.putIfAbsent(strip(leaf), f);
                            });
                        }
                    }
                }
                if (child != null && f.type instanceof ListOf) {
                    String list = listPath(child, "");
                    if (list != null) {
                        ownerOf.putIfAbsent(list, f);
                    }
                }
            }
            if (child != null) {
                collectOwned(child, transformed);
            }
        }
    }

    /** Wire paths of traits are relative to their element of {@code traits}. */
    String strip(String wire) {
        return strip(c, wire);
    }

    static String strip(Comp c, String wire) {
        if (c.kind == Kind.TRAIT && wire.startsWith("traits[].")) {
            return wire.substring("traits[].".length());
        }
        return wire;
    }

    /** The value rules of a field in the window; for a vector also those of its x/y/z object form. */
    List<Rule> rules(Field f) {
        List<Rule> rules = new ArrayList<>(WireRules.rulesOf(window, f));
        if (f.type instanceof Vec3) {
            // The x/y/z children of the object form write the same leaves as the elements of the array form
            for (String axis : List.of("x", "y", "z")) {
                for (Rule r : Rule.all(window.field(f.path + "." + axis))) {
                    if (r.writesValue()) {
                        r.element = List.of("x", "y", "z").indexOf(axis);
                        rules.add(r);
                    }
                }
            }
            Map<String, Rule> byLeaf = new LinkedHashMap<>();
            for (Rule r : rules) {
                byLeaf.putIfAbsent(r.wire + " " + r.kind(), r);
            }
            rules = new ArrayList<>(byLeaf.values());
        }
        return rules;
    }

    Comp component() {
        return c;
    }

    String id() {
        return c.id;
    }

    /** The code being written; a {@link LeafEmitter} adds to it. */
    CodeBlock.Builder code() {
        return b;
    }

    /** A fresh local variable name. */
    String local(String prefix) {
        return prefix + (vars++);
    }

    // ---- Fields ------------------------------------------------------------------------------------------------------

    /**
     * @param value the Java expression of the record holding the fields
     * @param node the node variable leaves are written to
     * @param prefix the generic wire path of that node ("" or {@code ...list[]})
     */
    private void fields(ObjType o, String value, String node, String prefix) {
        // The leaves a descriptor written from the holder writes: the fields that only write those need no code of their own
        Set<String> held = new TreeSet<>();
        for (Field f : o.fields) {
            for (Rule r : rules(f)) {
                if (r instanceof Descriptor d && d.writesHolder()) {
                    d.leaves().forEach(leaf -> held.add(strip(leaf)));
                }
            }
        }
        for (Field f : o.fields) {
            if (f.transform == null && !WireRules.writesHolder(window, f) && !writesOnly(f, held)) {
                if (f.type instanceof ApiModel.UnionRef) {
                    throw new GenerationException(c.id + " " + f.path + " takes several forms; name a hand transform for it in HandTransforms");
                }
                field(f, value, node, prefix);
            }
        }
        // Written from the holder last, in field order: they see what the generic rules of their siblings wrote
        Set<String> transformsCalled = new LinkedHashSet<>();
        for (Field f : o.fields) {
            if (f.transform != null && transformsCalled.add(f.transform)) {
                transformCall(f.transform, node, value);
            }
            for (Rule r : rules(f)) {
                if (r instanceof Descriptor d && d.writesHolder()) {
                    r.emit(new LeafEmitter(this, f, f.type, CodeBlock.of("$N", value), node, relative(strip(r.wire), prefix), false));
                }
            }
        }
    }

    /** Whether the field's value rules write nothing but the leaves. */
    private boolean writesOnly(Field f, Set<String> leaves) {
        List<Rule> written = rules(f).stream().filter(Rule::writesValue).toList();
        return !written.isEmpty() && written.stream().allMatch(r -> leaves.contains(strip(r.wire)));
    }

    private void field(Field f, String owner, String node, String prefix) {
        boolean nullable = ApiSource.nullable(f);
        String read = owner + "." + f.java + "()";
        CodeBlock.Builder all = b;
        int start = vars;
        String var = "v" + (vars++);
        CodeBlock present = presentBlock(f, var, node, prefix);
        // A read used once is written where it is used; one not used at all is not made
        long uses = Pattern.compile("\\b" + var + "\\b").matcher(present.toString()).results().count();
        boolean inline = uses == 0 || uses == 1 && !nullable;
        if (inline) {
            vars = start;
            present = presentBlock(f, read, node, prefix);
        }
        b = CodeBlock.builder();
        if (nullable) {
            absentLines(f, f, node, prefix, new TreeSet<>());
        }
        CodeBlock absent = b.build();
        b = all;
        if (present.isEmpty() && absent.isEmpty()) {
            vars = start;
            return; // the field writes nothing in this window
        }
        if (!inline) {
            b.addStatement("$T $N = $L", ApiSource.typeName(c, f.type, nullable), var, read);
        }
        if (nullable && present.isEmpty()) {
            b.beginControlFlow("if ($L == null)", read);
            b.add(absent);
            b.endControlFlow();
            return;
        }
        if (nullable) {
            b.beginControlFlow("if ($L != null)", inline ? read : var);
        }
        b.add(present);
        if (nullable) {
            if (!absent.isEmpty()) {
                b.nextControlFlow("else");
                b.add(absent);
            }
            b.endControlFlow();
        }
    }

    private CodeBlock presentBlock(Field f, String var, String node, String prefix) {
        CodeBlock.Builder all = b;
        b = CodeBlock.builder();
        present(f, var, node, prefix);
        CodeBlock present = b.build();
        b = all;
        return present;
    }

    private void present(Field f, String var, String node, String prefix) {
        List<Rule> rules = rules(f);
        boolean form = f.form != null;
        boolean mapped = false;
        for (Rule r : rules) {
            String wire = strip(r.wire);
            if (r instanceof Constant) {
                // what the field's presence switches on, where no other field owns the leaf
                if ((form || !owned.contains(wire)) && placeable(wire, prefix)) {
                    r.emit(new LeafEmitter(this, f, f.type, CodeBlock.of("$N", var), node, relative(wire, prefix), false));
                }
                continue;
            }
            if (!r.writesValue()) {
                continue;
            }
            mapped = true;
            if (!r.eachElement()) {
                value(f, r, var, node, prefix, wire);
            }
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
            for (Rule rule : rules) {
                if (rule.eachElement()) {
                    rule.emit(new LeafEmitter(this, f, l.element(), CodeBlock.of("$N", element), elementNode, relative(strip(rule.wire), elementPrefix), false));
                }
            }
            fields(r.wire(), element, elementNode, elementPrefix);
            b.endControlFlow();
            return;
        }
        if (!mapped && rules.stream().noneMatch(x -> x instanceof Constant)) {
            JsonObject m = WireRules.mappingOf(window, f);
            String status = m == null ? "none" : m.get("status").getAsString();
            if (!status.equals("dropped")) {
                if (!replacedByConstant(f, prefix)) {
                    unmapped(f, null);
                } else if (f.defaultValue == null) {
                    b.addStatement("ctx.changed($L, $S)", name(), (f.json == null ? f.java : f.path) + " is sent as a constant, whatever its value");
                }
            }
        }
    }

    /**
     * Whether the window sends a constant in the place of the field: the leaf the component's own compound has for it
     * ({@code minecraft:enchantable.slot}), which an encoder writes whatever the field says. The value is replaced, not
     * dropped, so the encoder reports it as changed.
     */
    private boolean replacedByConstant(Field f, String prefix) {
        return prefix.isEmpty() && window.constants().keySet().stream().anyMatch(leaf -> strip(leaf).equals(c.name + "." + f.path));
    }

    private void unmapped(Field f, String emptyCheck) {
        String json = f.json == null ? f.java : f.path;
        if (f.defaultValue != null && emptyCheck == null) {
            return;
        }
        if (emptyCheck != null) {
            b.beginControlFlow("if (!$L)", emptyCheck);
        }
        b.addStatement("ctx.drop($L, $S)", name(), json + " has no known wire mapping");
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
        for (Field f : o.fields) {
            for (Rule r : WireRules.rulesOf(window, f)) {
                wires.add(strip(r.wire));
            }
            ObjType child = WireRules.objectOf(f.type);
            if (child != null) {
                wires.addAll(allWires(child));
            }
        }
        return wires;
    }

    private static boolean placeable(String wire, String prefix) {
        if (!prefix.isEmpty() && !wire.startsWith(prefix + ".")) {
            return false;
        }
        String rel = prefix.isEmpty() ? wire : wire.substring(prefix.length() + 1);
        return !rel.contains("[]");
    }

    private static String relative(String wire, String prefix) {
        return prefix.isEmpty() ? wire : wire.equals(prefix) ? "" : wire.substring(prefix.length() + 1);
    }

    private void value(Field f, Rule r, String var, String node, String prefix, String wire) {
        Type t = f.type;
        CodeBlock expr = CodeBlock.of("$N", var);
        // A rule of every element of a list leaf (wire ends with []) onto a list of scalars or a vector: the leaf is the list
        if (r.wholeList() && wire.endsWith("[]") && (t instanceof ListOf l && !(l.element() instanceof ObjRef) || t instanceof Vec3)) {
            String listWire = wire.substring(0, wire.length() - 2);
            checkPlaceable(f, listWire, prefix);
            r.emit(new LeafEmitter(this, f, t, expr, node, relative(listWire, prefix), true));
            return;
        }
        // A list of scalars sent as a list of objects that hold each value under one key ({name})
        int element = wire.lastIndexOf("[].");
        if (t instanceof ListOf l && !(l.element() instanceof ObjRef) && r instanceof Identity && element > 0
            && !wire.substring(element + 3).contains("[]") && !wire.substring(element + 3).contains(".")) {
            String listWire = wire.substring(0, element);
            checkPlaceable(f, listWire, prefix);
            String x = "x" + (vars++);
            b.addStatement("$N.list($S)", node, relative(listWire, prefix));
            b.beginControlFlow("for ($T $N : $N)", ApiSource.typeName(c, l.element(), true), x, var);
            b.addStatement("$N.element($S).put($S, $L)", node, relative(listWire, prefix), wire.substring(element + 3),
                WireValues.convert(c.id + " " + f.path, l.element(), CodeBlock.of("$N", x), r.nbt));
            b.endControlFlow();
            return;
        }
        // A map whose keys reach the wire (wire ends with {}): one leaf per entry
        if (t instanceof MapOf m && wire.endsWith("{}") && r.mapValue()) {
            String mapWire = wire.substring(0, wire.length() - 2);
            checkPlaceable(f, mapWire, prefix);
            if (rules(f).stream().anyMatch(x -> x instanceof EmptyMap && strip(x.wire).equals(mapWire))) {
                b.beginControlFlow("if ($N.isEmpty())", var);
                put(node, relative(mapWire, prefix), CodeBlock.of("$T.EMPTY", NBT_MAP));
                b.endControlFlow();
            }
            String e = "x" + (vars++);
            b.beginControlFlow("for ($T<$T, $T> $N : $N.entrySet())", Map.Entry.class, String.class, ApiSource.typeName(c, m.value(), true), e, var);
            CodeBlock entry = WireValues.convert(c.id + " " + f.path, m.value(), CodeBlock.of("$N.getValue()", e), r.nbt);
            b.addStatement("$N.put($S + $N.getKey(), $L)", node, relative(mapWire, prefix) + ".", e,
                r instanceof Molang ? CodeBlock.of("$T.molang($L)", WIRE, entry) : entry);
            b.endControlFlow();
            return;
        }
        checkPlaceable(f, wire, prefix);
        boolean guarded = false;
        if (r.element != null) {
            int k = r.element;
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
        if (r.nbt == null) {
            throw new GenerationException(c.id + " " + f.path + ": the rule for " + wire + " has no NBT type");
        }
        r.emit(new LeafEmitter(this, f, t, expr, node, relative(wire, prefix), false));
        if (guarded) {
            b.endControlFlow();
        }
    }

    private void checkPlaceable(Field f, String wire, String prefix) {
        if (!placeable(wire, prefix)) {
            throw new GenerationException("list depth mismatch: " + c.id + " " + f.path + " writes " + wire + " from "
                + (prefix.isEmpty() ? "the component" : prefix) + "; name a transform for it in HandTransforms");
        }
    }

    // ---- Absent fields -----------------------------------------------------------------------------------------------

    /** Whether a field is the subject or inside it. */
    private static boolean within(Field field, Field subject) {
        if (field == subject) {
            return true;
        }
        ObjType child = WireRules.objectOf(subject.type);
        if (child == null) {
            return false;
        }
        for (Field f : child.fields) {
            if (within(field, f)) {
                return true;
            }
        }
        return false;
    }

    private void absentLines(Field subject, Field f, String node, String prefix, Set<String> decided) {
        for (Map.Entry<String, String> e : WireRules.whenAbsent(window, f).entrySet()) {
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
            for (Field cf : child.fields) {
                if (cf.transform == null && !WireRules.writesHolder(window, cf)) {
                    absentLines(subject, cf, node, prefix, decided);
                }
            }
        }
    }

    void put(String node, String path, CodeBlock value) {
        b.addStatement("$N.put($S, $L)", node, path, value);
    }
}
