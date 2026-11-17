package org.geysermc.paletter.tools.whitebox;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.whitebox.WhiteboxCoverage.Captured;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Self-checks of a {@code whitebox} run, against a binary changing in a way the discovery silently misses: it must find
 * every component the captures of the build show (the keys of legacy format items aside, which are not all component
 * classes), and must not lose components or keys against the previous build that the captures of this build still
 * show. What no capture shows can go without a problem; it is a {@code !} line of the diff to the build before. A
 * problem makes {@code update} report "white-box unavailable for this build"; it never stops the update, and the facts
 * are not written.
 * <p>
 * The docs generator's schemas are not read yet; the captures and the previous build stand in for them.
 */
public final class WhiteboxChecks {
    /** A build may lose this many components or keys, or this share of the previous build's, without a problem. */
    private static final int SMALL_DROP = 3;
    private static final int SMALL_DROP_SHARE = 50;
    private static final List<String> KEY_LISTS = List.of("writes", "writesExtra", "writesNested", "reads", "schema");

    private WhiteboxChecks() {
    }

    /**
     * @param previous the facts of the build before, or null
     * @param captured what the captures of this build show; empty when it has none yet
     * @return what is wrong with the facts, one problem per line; empty when they pass
     */
    public static List<String> problems(JsonObject facts, JsonObject previous, Captured captured) {
        List<String> problems = new ArrayList<>();
        Map<String, Set<String>> now = keys(facts);

        Set<String> missing = new TreeSet<>(captured.names());
        missing.removeIf(name -> !name.startsWith("item:minecraft:") && !name.startsWith("block:minecraft:"));
        // A legacy format item (most vanilla items, old format versions) is sent under keys that are not classes: minecraft:foil
        missing.removeIf(captured::onlyLegacy);
        missing.removeAll(now.keySet());
        // A class the code registers without a name (the one for onUseOn) is found by what it writes
        for (JsonElement unnamed : facts.getAsJsonObject("itemClassesWithoutName").getAsJsonArray("sent")) {
            Set<String> writes = keysOf(unnamed.getAsJsonObject());
            missing.removeIf(name -> name.startsWith("item:") && !writes.isEmpty() && writes.stream().allMatch(key -> captured.mentions(name, key)));
        }
        if (!missing.isEmpty()) {
            problems.add(missing.size() + " captured components are not found in the code: " + missing);
        }
        if (previous == null) {
            return problems;
        }

        // What the build before had and this one lacks, unless no capture of this build shows it (without captures,
        // nothing is excused)
        boolean captures = !captured.names().isEmpty();
        Map<String, Set<String>> before = keys(previous);
        List<String> lostComponents = new ArrayList<>();
        List<String> lostKeys = new ArrayList<>();
        before.forEach((wire, keys) -> {
            if (!now.containsKey(wire)) {
                if (!captures || captured.has(wire)) {
                    lostComponents.add(wire);
                }
                return;
            }
            for (String key : keys) {
                boolean excused = captures && captured.has(wire) && !captured.mentions(wire, key);
                if (!now.get(wire).contains(key) && !excused) {
                    lostKeys.add(wire + " " + key);
                }
            }
        });
        if (lostComponents.size() > allowed(before.size())) {
            problems.add(lostComponents.size() + " of the " + before.size() + " components and traits of the build before are gone: " + lostComponents);
        }
        int keyCount = before.values().stream().mapToInt(Set::size).sum();
        if (lostKeys.size() > allowed(keyCount)) {
            problems.add(lostKeys.size() + " of the " + keyCount + " keys of the build before are gone: "
                + lostKeys.subList(0, Math.min(lostKeys.size(), 10)) + (lostKeys.size() > 10 ? " ..." : ""));
        }
        return problems;
    }

    private static int allowed(int before) {
        return Math.max(SMALL_DROP, before / SMALL_DROP_SHARE);
    }

    /** @return the keys of every component, trait and envelope by the name the captures use */
    private static Map<String, Set<String>> keys(JsonObject facts) {
        Map<String, Set<String>> keys = new TreeMap<>();
        Map<String, String> groups = Map.of("itemComponents", "item:", "blockComponents", "block:", "traits", "trait:");
        groups.forEach((group, prefix) -> facts.getAsJsonObject(group).entrySet()
            .forEach(entry -> keys.put(prefix + entry.getKey(), keysOf(entry.getValue().getAsJsonObject()))));
        for (Map.Entry<String, JsonElement> envelope : facts.getAsJsonObject("envelopes").entrySet()) {
            keys.put("envelope:" + envelope.getKey(), keysOf(envelope.getValue().getAsJsonObject()));
        }
        return keys;
    }

    private static Set<String> keysOf(JsonObject entry) {
        Set<String> keys = new TreeSet<>();
        for (String list : KEY_LISTS) {
            if (entry.has(list)) {
                entry.getAsJsonArray(list).forEach(key -> keys.add(key.getAsString()));
            }
        }
        return keys;
    }
}
