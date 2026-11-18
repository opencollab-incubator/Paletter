package org.geysermc.paletter.tools.codegen.component;

import org.geysermc.paletter.tools.codegen.component.ApiModel.Comp;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Field;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ListOf;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ObjRef;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ObjType;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Window;
import org.geysermc.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import org.geysermc.paletter.tools.model.rule.Rule;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Checks of a built component: no wire leaf has two deciding fields, no object has two fields of one name.
 */
final class ModelChecks {
    private ModelChecks() {
    }

    /**
     * Every wire leaf must be moved by at most one field (shorthands aside): two fields deciding
     * the same leaf need a hand transform. Then each default is checked against what BDS sends when the field is omitted.
     */
    static void checkConflicts(Comp c) {
        for (Window w : c.windows) {
            if (!w.sent()) {
                continue;
            }
            Map<String, List<String>> owners = new TreeMap<>();
            collectOwners(c, w, c.root, owners, false);
            for (Map.Entry<String, List<String>> e : owners.entrySet()) {
                if (new LinkedHashSet<>(e.getValue()).size() > 1) {
                    throw new GenerationException("conflicting rules: " + c.id + " " + e.getValue() + " all decide the wire leaf "
                        + e.getKey() + "; name a transform for one of them in HandTransforms");
                }
            }
        }
    }

    static void collectOwners(Comp c, Window w, ObjType o, Map<String, List<String>> owners, boolean transformed) {
        for (Field f : o.fields) {
            boolean t = transformed || f.transform != null || WireRules.writesHolder(w, f) || c.transform != null;
            if (!t) {
                for (String leaf : WireRules.rulesOf(w, f).stream().filter(Rule::writesValue).filter(r -> !r.sharesLeaf()).map(r -> r.wire).toList()) {
                    owners.computeIfAbsent(leaf, k -> new ArrayList<>()).add(f.path);
                }
            }
            ObjType child = WireRules.objectOf(f.type);
            if (child != null) {
                collectOwners(c, w, child, owners, t);
            }
        }
    }

    static void checkFieldNames(Comp c, ObjType o) {
        Set<String> names = new TreeSet<>();
        for (Field f : o.fields) {
            if (!names.add(f.java)) {
                throw new GenerationException("name collision: " + c.id + " " + (o.path.isEmpty() ? "" : o.path + " ") + "has two fields named " + f.java);
            }
            if (f.type instanceof ObjRef r) {
                checkFieldNames(c, r.type());
            } else if (f.type instanceof ListOf l && l.element() instanceof ObjRef r) {
                checkFieldNames(c, r.type());
            }
        }
    }
}
