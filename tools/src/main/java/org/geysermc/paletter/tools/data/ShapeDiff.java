package org.geysermc.paletter.tools.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares the wire shapes of two sets of captures component by component (see {@link WireShapes}). An added, removed
 * or retyped field, or a component that is no longer sent, is a <b>wire format change</b>; a component that was not sent
 * before is reported as new.
 */
public final class ShapeDiff {
    public final List<String> wireChanges = new ArrayList<>();
    public final List<String> newComponents = new ArrayList<>();

    /** @param before component to (path to type) */
    public static ShapeDiff of(Map<String, Map<String, String>> before, Map<String, Map<String, String>> after) {
        ShapeDiff diff = new ShapeDiff();
        Set<String> names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());
        for (String name : names) {
            Map<String, String> x = before.get(name);
            Map<String, String> y = after.get(name);
            if (x == null) {
                diff.newComponents.add(name);
            } else if (y == null) {
                diff.wireChanges.add(name + ": no longer sent");
            } else {
                Set<String> paths = new TreeSet<>(x.keySet());
                paths.addAll(y.keySet());
                for (String path : paths) {
                    String a = x.get(path);
                    String b = y.get(path);
                    String where = name + (path.isEmpty() ? "" : " " + path);
                    if (a == null) {
                        diff.wireChanges.add(where + ": new field (" + b + ")");
                    } else if (b == null) {
                        diff.wireChanges.add(where + ": field removed (was " + a + ")");
                    } else if (!a.equals(b)) {
                        diff.wireChanges.add(where + ": type " + a + " -> " + b);
                    }
                }
            }
        }
        return diff;
    }

    public boolean identical() {
        return wireChanges.isEmpty() && newComponents.isEmpty();
    }
}
