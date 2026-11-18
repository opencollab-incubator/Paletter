package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Field;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ListOf;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Type;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Window;
import dev.opencollab.paletter.tools.model.rule.Descriptor;
import dev.opencollab.paletter.tools.model.rule.Rule;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The wire rules of a field in a window, as the generated encoders and the checks of the model read them.
 */
final class WireRules {
    private WireRules() {
    }

    static Set<String> valueLeaves(JsonObject fieldMapping) {
        Set<String> leaves = new TreeSet<>();
        for (Rule r : Rule.all(fieldMapping)) {
            if (r.writesValue()) {
                leaves.add(r.wire);
            }
        }
        return leaves;
    }

    /** The object a field holds, as it occurs at the field (its own JSON paths and rules). */
    static ObjType objectOf(Type t) {
        if (t instanceof ObjRef r) {
            return r.wire();
        }
        if (t instanceof ListOf l && l.element() instanceof ObjRef r) {
            return r.wire();
        }
        if (t instanceof ApiModel.UnionRef u && u.object() != null) {
            return u.object().wire();
        }
        return null;
    }

    /**
     * Whether a field's rules include a descriptor written from the record that holds the field. Like a hand transform it
     * replaces the generic encoding of its field and is called after its siblings.
     */
    static boolean writesHolder(Window w, Field f) {
        return rulesOf(w, f).stream().anyMatch(r -> r instanceof Descriptor d && d.writesHolder());
    }

    /** The rules of a field in a window, without the rules a union's object takes over from its children. */
    static List<Rule> rulesOf(Window w, Field f) {
        List<Rule> rules = Rule.all(mappingOf(w, f));
        if (f.type instanceof ObjRef || (f.type instanceof ListOf l && l.element() instanceof ObjRef)) {
            // An object's own value rules come from its scalar shorthand, which its children explain
            rules.removeIf(r -> r.writesValue() && !r.eachElement());
        }
        if (f.type instanceof ApiModel.UnionRef) {
            rules.removeIf(Rule::writesValue);
        }
        return rules;
    }

    static JsonObject mappingOf(Window w, Field f) {
        if (f.mappingKey == null) {
            return null;
        }
        if (f.mappingKey.startsWith("form:")) {
            return w.form(f.mappingKey.substring(5));
        }
        return w.field(f.mappingKey);
    }

    static Map<String, String> whenAbsent(Window w, Field f) {
        JsonObject m = mappingOf(w, f);
        Map<String, String> out = new TreeMap<>();
        if (m != null && m.has("whenAbsent")) {
            m.getAsJsonObject("whenAbsent").entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
        }
        return out;
    }
}
