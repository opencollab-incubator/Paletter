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
     * Simple names are unique across the public API. A generated component whose name another generated component or a
     * public type that is not generated here ({@link Context#reserved}) also has gets {@code Block} or {@code Item} in front
     * (traits count as blocks). The set of colliding names is computed, never listed. Nested types are scoped by their
     * owner, but one that has the name of a top level type would shadow it: that fails and asks for an api.json typeNames entry
     * (or enums), which is a hand choice: {@code VisualGeometry} reads better than {@code EmbeddedVisual.EmbeddedVisualGeometry}.
     * <p>
     * A prefix is a rename of the API. Before 1.0 nothing but {@code :paletter:apiCheck} stops a new component from silently
     * giving an existing name a prefix: the dump of {@code paletter/api/paletter.api} shows it in the diff.
     */
    static void prefix(ApiModel model, Set<String> reserved) {
        Map<String, Integer> counts = new HashMap<>();
        model.components.forEach(c -> counts.merge(c.className, 1, Integer::sum));
        for (Comp c : model.components) {
            c.baseName = c.className;
            if (counts.get(c.className) > 1 || reserved.contains(c.className)) {
                c.className = (c.kind == Kind.ITEM ? "Item" : "Block") + c.className;
                c.root.name = c.className;
            }
        }
        for (Comp c : model.components) {
            Map<String, String> renamed = new HashMap<>();
            model.components.stream().filter(o -> o.kind == c.kind).forEach(o -> renamed.put(o.baseName, o.className));
            for (ObjType o : Stream.concat(Stream.of(c.root), c.types.values().stream()).toList()) {
                o.fields.forEach(f -> f.type = renameRefs(f.type, renamed));
            }
        }
        Set<String> top = new HashSet<>(reserved);
        for (Comp c : model.components) {
            if (!top.add(c.className)) {
                throw new GenerationException("name collision: " + c.id + " is still " + c.className + " after the prefix; set one in api.json classes");
            }
        }
        for (Comp c : model.components) {
            List<String> nested = new ArrayList<>(c.types.keySet());
            nested.addAll(c.enums.keySet());
            for (String name : nested) {
                if (top.contains(name)) {
                    throw new GenerationException("name collision: " + c.id + " has a nested type " + name + ", which shadows the public type of that name;"
                        + " name it in api.json typeNames");
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
