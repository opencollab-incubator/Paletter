package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.EnumRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Field;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ListOf;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.MapOf;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Scalar;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ScalarKind;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.States;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Type;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Vec3;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Window;
import dev.opencollab.paletter.tools.model.rule.Linear;
import dev.opencollab.paletter.tools.model.rule.Rule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The default of a field: the docs' default unless BDS sends something else when the field is omitted.
 */
final class Defaults {
    private final Hand hand;

    Defaults(Hand hand) {
        this.hand = hand;
    }

    /**
     * Applies the docs default unless BDS sends something else when the field is omitted, then settles the shape of every
     * boolean and number field without one ({@link #settle}).
     */
    void apply(Comp c, ObjType o) {
        docsDefaults(c, o);
        for (Field f : o.fields) {
            settle(c, o, f);
        }
    }

    /**
     * Applies the docs default unless BDS sends something else when the field is omitted; then the field stays unset
     * and {@link #settle} decides.
     */
    private void docsDefaults(Comp c, ObjType o) {
        for (Field f : o.fields) {
            Window w = c.evidence(f.mappingKey);
            ObjType child = WireRules.objectOf(f.type);
            if (child != null) {
                apply(c, child);
            }
            if (f.required || f.form != null && f.docsDefault == null) {
                if (f.form != null && f.type instanceof ListOf) {
                    f.defaultValue = new JsonArray();
                }
                continue;
            }
            JsonElement d = f.docsDefault;
            if (d == null || d.isJsonNull()) {
                continue;
            }
            if (hand.listed("noDefaults", c.id + ":" + f.path)) {
                f.defaultNote = "The docs give the default " + render(d) + "; unset, BDS behaves differently (see api.json noDefaults).";
                continue;
            }
            if (!fits(f.type, d)) {
                f.defaultNote = "The docs give the default " + d + ", which is not a value of this type; unset, BDS's behaviour applies.";
                continue;
            }
            String mismatch = w == null || f.transform != null || WireRules.writesHolder(w, f) || c.transform != null ? null : defaultMismatch(w, f, d);
            if (mismatch != null) {
                f.defaultNote = "The docs give the default " + render(d) + ", but " + mismatch + "; so it is unset by default, and the "
                    + "encoder sends what BDS sends then.";
                continue;
            }
            f.defaultValue = d;
        }
    }

    /**
     * A boolean or number field the docs give no usable default, or the one value of a component, is not nullable unless
     * leaving it out differs on the wire:
     * <ul>
     *     <li>nullable where BDS sends nothing for it when it is omitted, which no value can say, or where it writes
     *     nothing at all;</li>
     *     <li>else defaulting to the value whose encoding is what BDS sends when it is omitted (captured, data/model);</li>
     *     <li>required where that is unknown. A default is never guessed.</li>
     * </ul>
     * Fields api.json lists as {@code optional}, value forms and hand-encoded fields keep their shape.
     */
    private void settle(Comp c, ObjType o, Field f) {
        boolean only = o == c.root && o.fields.stream().filter(x -> x.form == null).count() == 1;
        boolean number = f.type instanceof Scalar s && s.kind() != ScalarKind.STRING;
        boolean value = only && (f.type instanceof Scalar || f.type instanceof EnumRef);
        if (!(number || value) || f.required || f.defaultValue != null || f.form != null
            || f.keyedExtraOf != null || f.transform != null || c.transform != null || hand.listed("optional", c.id + ":" + f.path)) {
            return;
        }
        Window w = c.evidence(f.mappingKey);
        Map<String, String> absent = w == null ? Map.of() : WireRules.whenAbsent(w, f);
        if (absent.containsValue("omitted") || w == null || WireRules.rulesOf(w, f).stream().noneMatch(Rule::writesValue)) {
            // Omitting it differs on the wire, or it writes nothing at all: its shape says nothing about the wire
            return;
        }
        JsonElement sent = whenOmitted(w, f);
        if (sent == null) {
            f.required = true;
            return;
        }
        f.defaultValue = sent;
        f.defaultNote = f.docsDefault == null ? "The docs give no default; this is what BDS sends when the field is omitted."
            : "The docs give the default " + render(f.docsDefault) + ", but BDS sends what this default sends when the field is omitted.";
    }

    /**
     * The value whose encoding is what BDS sends when the field is omitted, by every value rule; null if none is (or nothing
     * shows what BDS sends).
     */
    static JsonElement whenOmitted(Window w, Field f) {
        Map<String, String> absent = WireRules.whenAbsent(w, f);
        List<Rule> rules = WireRules.rulesOf(w, f).stream().filter(Rule::writesValue).toList();
        if (absent.isEmpty() || rules.isEmpty()) {
            return null;
        }
        List<JsonElement> candidates = new ArrayList<>();
        if (f.type instanceof EnumRef e) {
            e.type().values.forEach(v -> candidates.add(new JsonPrimitive(v)));
        } else if (f.type instanceof Scalar s && s.kind() == ScalarKind.STRING) {
            for (String literal : absent.values()) {
                int unit = literal.lastIndexOf(" (");
                if (unit >= 0) {
                    candidates.add(new JsonPrimitive(literal.substring(0, unit)));
                }
            }
        } else if (((Scalar) f.type).kind() == ScalarKind.BOOL) {
            candidates.add(new JsonPrimitive(false));
            candidates.add(new JsonPrimitive(true));
        } else {
            // The value a leaf BDS sends when it is omitted comes from: the leaf itself, or back through a unit conversion
            for (Rule rule : rules) {
                String literal = absent.get(rule.wire);
                int unit = literal == null ? -1 : literal.lastIndexOf(" (");
                if (unit < 0) {
                    continue;
                }
                double v;
                try {
                    v = Double.parseDouble(literal.substring(0, unit));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (rule instanceof Linear l) {
                    v = (v - l.offset) / l.scale;
                }
                if (((Scalar) f.type).kind() == ScalarKind.FLOAT) {
                    candidates.add(new JsonPrimitive(v));
                } else if (v == Math.rint(v)) {
                    candidates.add(new JsonPrimitive((long) v));
                }
            }
        }
        for (JsonElement candidate : candidates) {
            boolean compared = false;
            boolean same = true;
            for (Rule rule : rules) {
                String expected = absent.get(rule.wire);
                if (expected == null) {
                    continue;
                }
                String actual = rule.evaluate(candidate);
                compared = true;
                same &= actual != null && sameLiteral(expected, actual);
            }
            if (compared && same) {
                return candidate;
            }
        }
        return null;
    }

    /** An object's documented default names its fields' values; they win over the fields' own defaults. */
    static void propagateDefaults(ObjType o) {
        for (Field f : o.fields) {
            ObjType child = WireRules.objectOf(f.type);
            if (child == null) {
                continue;
            }
            if (f.type instanceof ObjRef && f.docsDefault != null && f.docsDefault.isJsonObject()) {
                for (Field cf : child.fields) {
                    JsonElement value = f.docsDefault.getAsJsonObject().get(cf.json);
                    if (value != null) {
                        cf.docsDefault = value;
                    }
                }
            }
            propagateDefaults(child);
        }
    }

    static String render(JsonElement d) {
        return Docs.literal(d);
    }

    static boolean fits(Type t, JsonElement d) {
        return switch (t) {
            case Scalar s -> d.isJsonPrimitive() && switch (s.kind()) {
                case BOOL -> d.getAsJsonPrimitive().isBoolean();
                case INT, FLOAT -> d.getAsJsonPrimitive().isNumber();
                case STRING -> d.getAsJsonPrimitive().isString();
            };
            case EnumRef e -> d.isJsonPrimitive() && e.type().values.contains(d.getAsString());
            case Vec3 v -> d.isJsonArray() && d.getAsJsonArray().size() == 3 || d.isJsonObject();
            case ListOf l -> d.isJsonArray() && d.getAsJsonArray().asList().stream().allMatch(e -> fits(l.element(), e));
            case MapOf m -> d.isJsonObject() && d.getAsJsonObject().entrySet().stream().allMatch(e -> fits(m.value(), e.getValue()));
            case ObjRef r -> d.isJsonObject() && d.getAsJsonObject().keySet().stream().allMatch(k -> r.type().fields.stream().anyMatch(f -> k.equals(f.json)));
            case States s -> d.isJsonObject();
            case ApiModel.UnionRef u -> u.union().variants.stream().anyMatch(v -> fits(v.type, d));
            case ApiModel.ComponentRef r -> false;
            case ApiModel.MolangRef m -> d.isJsonPrimitive() && d.getAsJsonPrimitive().isString() || d.isJsonObject() && d.getAsJsonObject().has("expression");
        };
    }

    /** @return why the default contradicts BDS, or null if it is consistent (or nothing shows otherwise) */
    static String defaultMismatch(Window w, Field f, JsonElement d) {
        Map<String, String> absent = WireRules.whenAbsent(w, f);
        if (absent.isEmpty()) {
            return null;
        }
        if (f.type instanceof ObjRef r && d.isJsonObject()) {
            for (Field child : r.type().fields) {
                JsonElement v = d.getAsJsonObject().get(child.json);
                if (v == null) {
                    continue;
                }
                for (Rule rule : WireRules.rulesOf(w, child)) {
                    String leaf = rule.wire;
                    String expected = absent.get(leaf);
                    String actual = rule.evaluate(v);
                    if (expected != null && actual != null && !sameLiteral(expected, actual)) {
                        return "BDS sends " + leaf + " = " + expected + " when it is omitted";
                    }
                }
            }
            return null;
        }
        for (Rule rule : WireRules.rulesOf(w, f)) {
            String leaf = rule.wire;
            String expected = absent.get(leaf);
            if (expected == null || (expected.equals("omitted") && f.path != null && f.path.contains("[]"))) {
                continue;
            }
            String actual = rule.evaluate(d);
            if (actual == null) {
                continue;
            }
            if (!sameLiteral(expected, actual)) {
                return "BDS " + "sends " + leaf + " = " + (expected.equals("omitted") ? "nothing" : expected) + " when it is omitted";
            }
        }
        return null;
    }

    static boolean sameLiteral(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        int ia = a.lastIndexOf(" (");
        int ib = b.lastIndexOf(" (");
        if (ia < 0 || ib < 0 || !a.substring(ia).equals(b.substring(ib))) {
            return false;
        }
        try {
            return Double.parseDouble(a.substring(0, ia)) == Double.parseDouble(b.substring(0, ib));
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
