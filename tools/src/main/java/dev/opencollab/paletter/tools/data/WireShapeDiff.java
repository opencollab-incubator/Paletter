package dev.opencollab.paletter.tools.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static dev.opencollab.paletter.tools.data.DiffText.*;

/** The wire shapes and values of components of two captures, see {@link WireShapes}: {@link Diff#shapes} and {@link Diff#values}. */
final class WireShapeDiff extends DiffBuilder {
    /**
     * Component wire shapes of two sets of captures, per component (see {@link WireShapes}): an added, removed or retyped
     * field, or a component that is no longer sent, is a wire format change; a component not sent before is new.
     *
     * @param before component to (path to type)
     */
    static Diff shapes(Map<String, Map<String, String>> before, Map<String, Map<String, String>> after) {
        WireShapeDiff wire = new WireShapeDiff();
        List<String> wireChanges = new ArrayList<>();
        List<String> newComponents = new ArrayList<>();
        Set<String> names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());
        for (String component : names) {
            Map<String, String> x = before.get(component);
            Map<String, String> y = after.get(component);
            if (x == null) {
                newComponents.add(component);
            } else if (y == null) {
                wireChanges.add(component + ": no longer sent");
            } else {
                Set<String> paths = new TreeSet<>(x.keySet());
                paths.addAll(y.keySet());
                for (String path : paths) {
                    String a = x.get(path);
                    String b = y.get(path);
                    String where = component + (path.isEmpty() ? "" : " " + path);
                    if (a == null) {
                        wireChanges.add(where + ": new field (" + b + ")");
                    } else if (b == null) {
                        wireChanges.add(where + ": field removed (was " + a + ")");
                    } else if (!a.equals(b)) {
                        wireChanges.add(where + ": type " + a + " -> " + b);
                    }
                }
            }
        }
        wire.put(Diff.WIRE_CHANGES, wireChanges);
        wire.put(Diff.NEW_COMPONENTS, newComponents);
        return wire.diff;
    }

    /**
     * The values of components and paths both sides send ({@link WireShapes#values}): a value that came or went with the same
     * shape is a value change, which {@link #shapes} cannot see (an enum id, a unit, a default). Added and removed paths are shape
     * changes and not repeated here.
     *
     * @param before component to (path to the distinct values)
     */
    static Diff values(Map<String, Map<String, Set<String>>> before, Map<String, Map<String, Set<String>>> after) {
        WireShapeDiff wire = new WireShapeDiff();
        List<String> changes = new ArrayList<>();
        for (String component : new TreeSet<>(before.keySet())) {
            Map<String, Set<String>> y = after.get(component);
            if (y == null) {
                continue;
            }
            before.get(component).forEach((path, x) -> {
                Set<String> now = y.get(path);
                if (now == null || x.equals(now)) {
                    return;
                }
                Set<String> added = difference(now, x);
                Set<String> removed = difference(x, now);
                changes.add(component + (path.isEmpty() ? "" : " " + path) + ": " + (added.isEmpty() ? "" : "new values " + limit(added))
                    + (added.isEmpty() || removed.isEmpty() ? "" : "; ") + (removed.isEmpty() ? "" : "values gone " + limit(removed)));
            });
        }
        wire.put(Diff.VALUE_CHANGES, changes);
        return wire.diff;
    }

    private static String limit(Set<String> values) {
        List<String> list = values.stream().limit(6).map(DiffText::shorten).toList();
        return list + (values.size() > 6 ? " and " + (values.size() - 6) + " more" : "");
    }
}
