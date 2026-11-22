package dev.opencollab.paletter.tools.codegen.component;

import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Kind;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ListOf;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.MapOf;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Type;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Simple names are unique across the public API: the check of the generated component names, and the {@code Block} or
 * {@code Item} prefix of the ones that collide.
 */
final class NameCollisions {
    private NameCollisions() {
    }

    static void check(ApiModel model) {
        Map<String, String> names = new TreeMap<>();
        for (Comp c : model.components) {
            if (JavaNames.JAVA_LANG.contains(c.className)) {
                throw new GenerationException("name collision: " + c.id + " would be " + c.className + ", which shadows java.lang."
                    + c.className + "; set a class name in api.json classes");
            }
            String key = c.kind.pkg() + "." + c.className;
            String other = names.put(key, c.id);
            if (other != null) {
                throw new GenerationException("name collision: " + other + " and " + c.id + " are both " + key + "; set one in api.json classes");
            }
        }
    }

    /**
     * Simple names are unique across the public API. A component the history records ({@link ApiHistory}) keeps its
     * recorded name. A new one whose name a recorded component, another new one or a public type that is not generated here
     * ({@link Context#reserved}) also has gets {@code Block} or {@code Item} in front (traits count as blocks): the
     * newcomer takes the prefix, an existing name never changes. The set of colliding names is computed, never listed.
     * Nested types are scoped by their owner, but one that has the name of a top level type would shadow it: that fails and
     * asks for an api.json typeNames entry (or enums), which is a hand choice: {@code VisualGeometry} reads better than
     * {@code EmbeddedVisual.EmbeddedVisualGeometry}.
     */
    static void prefix(ApiModel model, Set<String> reserved, ApiHistory history) {
        Map<String, Integer> counts = new HashMap<>();
        Set<String> kept = new HashSet<>();
        for (Comp c : model.components) {
            c.baseName = c.className;
            String recorded = history.recordedName("classes", c.id);
            if (recorded != null) {
                if (!recorded.equals(c.className) && !recorded.equals("Item" + c.className) && !recorded.equals("Block" + c.className)) {
                    throw new GenerationException(c.id + " would be " + c.className + ", but data/model/" + ApiHistory.FILE + " records "
                        + recorded + "; a deliberate rename changes both");
                }
                c.className = recorded;
                c.root.name = recorded;
                kept.add(recorded);
            }
        }
        model.components.stream().filter(c -> history.recordedName("classes", c.id) == null).forEach(c -> counts.merge(c.className, 1, Integer::sum));
        // A nested type of an existing component is taken too: the new component takes the prefix, the nested type keeps its name
        Set<String> nested = new HashSet<>();
        model.components.forEach(c -> {
            nested.addAll(c.types.keySet());
            nested.addAll(c.enums.keySet());
        });
        for (Comp c : model.components) {
            if (history.recordedName("classes", c.id) == null
                && (counts.get(c.className) > 1 || kept.contains(c.className) || reserved.contains(c.className) || nested.contains(c.className))) {
                c.className = (c.kind == Kind.ITEM ? "Item" : "Block") + c.className;
                c.root.name = c.className;
            }
        }
        model.components.forEach(history::className);
        for (Comp c : model.components) {
            Map<String, String> renamed = new HashMap<>();
            model.components.stream().filter(o -> o.kind == c.kind).forEach(o -> renamed.put(o.baseName, o.className));
            for (ObjType o : Stream.concat(Stream.of(c.root), c.types.values().stream()).toList()) {
                o.fields.forEach(f -> f.type = renameRefs(f.type, renamed));
            }
        }
        Map<String, String> top = new HashMap<>();
        reserved.forEach(name -> top.put(name, "a hand-written public type"));
        for (Comp c : model.components) {
            if (top.putIfAbsent(c.className, "the component " + c.id) != null) {
                throw new GenerationException("name collision: " + c.id + " is still " + c.className + " after the prefix; set one in api.json classes");
            }
        }
        for (Comp c : model.components) {
            List<String> names = new ArrayList<>(c.types.keySet());
            names.addAll(c.enums.keySet());
            for (String name : names) {
                if (top.containsKey(name)) {
                    // A new component got the prefix above, so the public type is a recorded or hand-written one
                    throw new GenerationException("name collision: " + c.id + " has a nested type " + name + ", which would shadow "
                        + top.get(name) + " of that name inside " + c.className + "; name the nested type in api.json typeNames (or enums)"
                        + " if it is the newer one, else the other in api.json classes, and the history entry with it");
                }
            }
        }
    }

    /** References to other components (api.json {@code component:Name}) follow the prefix their target got. */
    static Type renameRefs(Type t, Map<String, String> renamed) {
        return switch (t) {
            case ApiModel.ComponentRef r -> new ApiModel.ComponentRef(renamed.getOrDefault(r.className(), r.className()));
            case ListOf l -> new ListOf(renameRefs(l.element(), renamed));
            case MapOf m -> new MapOf(renameRefs(m.value(), renamed));
            default -> t;
        };
    }
}
