package org.geysermc.paletter.tools.data;

import org.geysermc.paletter.tools.bds.BdsDump;
import org.geysermc.paletter.block.BlockStates;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Differences between two Script API dumps (the committed baseline of the newest bundled release and a new run).
 * Both sides have the same blind spots (legacy alias states, items without a stack), so they cancel out; this works
 * even when the network capture is impossible.
 */
public final class ScriptApiDiff {
    private ScriptApiDiff() {
    }

    public static DataDiff of(BdsDump before, BdsDump after) {
        DataDiff diff = new DataDiff();
        Set<String> blocksBefore = before.blockStates().isEmpty() ? before.blockDefaults().keySet() : before.blockStates().keySet();
        Set<String> blocksAfter = after.blockStates().isEmpty() ? after.blockDefaults().keySet() : after.blockStates().keySet();
        diff.sections.put("new blocks", DataDiff.difference(new TreeSet<>(blocksAfter), new TreeSet<>(blocksBefore)).stream()
            .map(name -> name + " " + DataDiff.compact(after.blockStates().getOrDefault(name, Map.of()))).toList());
        diff.sections.put("removed blocks", List.copyOf(DataDiff.difference(new TreeSet<>(blocksBefore), new TreeSet<>(blocksAfter))));

        List<String> states = new ArrayList<>();
        before.blockStates().forEach((name, x) -> {
            Map<String, List<Object>> y = after.blockStates().get(name);
            if (y == null || x.equals(y)) {
                return;
            }
            Set<String> keys = new TreeSet<>(x.keySet());
            keys.addAll(y.keySet());
            List<String> parts = new ArrayList<>();
            for (String key : keys) {
                if (!x.containsKey(key)) {
                    parts.add("+" + key + "=" + y.get(key));
                } else if (!y.containsKey(key)) {
                    parts.add("-" + key);
                } else if (!x.get(key).equals(y.get(key))) {
                    parts.add(key + ": " + x.get(key) + " -> " + y.get(key));
                }
            }
            states.add(name + ": " + String.join("; ", parts));
        });
        diff.sections.put("block state changes", states);

        List<String> defaults = new ArrayList<>();
        before.blockDefaults().forEach((name, x) -> {
            var y = after.blockDefaults().get(name);
            if (y != null && !x.equals(y)) {
                defaults.add(name + ": " + DataDiff.compact(BlockStates.states(x)) + " -> " + DataDiff.compact(BlockStates.states(y)));
            }
        });
        diff.sections.put("block default changes", defaults);

        diff.sections.put("new items", DataDiff.difference(after.items().keySet(), before.items().keySet()).stream()
            .map(name -> name + " " + describe(after.items().get(name))).toList());
        diff.sections.put("removed items", List.copyOf(DataDiff.difference(before.items().keySet(), after.items().keySet())));
        // Script API component changes usually hit hundreds of items at once: one line per component
        List<String> items = new ArrayList<>();
        Map<String, List<String>> componentChanges = new java.util.TreeMap<>();
        before.items().forEach((name, x) -> {
            BdsDump.Item y = after.items().get(name);
            if (y == null || x.equals(y)) {
                return;
            }
            if (x.maxStackSize() != y.maxStackSize() || x.maxDurability() != y.maxDurability()
                || !Objects.equals(x.localizationKey(), y.localizationKey())) {
                items.add(name + ": " + describe(x) + " -> " + describe(y));
            }
            DataDiff.difference(new TreeSet<>(y.components()), new TreeSet<>(x.components()))
                .forEach(c -> componentChanges.computeIfAbsent("+" + c, k -> new ArrayList<>()).add(name));
            DataDiff.difference(new TreeSet<>(x.components()), new TreeSet<>(y.components()))
                .forEach(c -> componentChanges.computeIfAbsent("-" + c, k -> new ArrayList<>()).add(name));
        });
        diff.sections.put("item changes (stack size, durability, localization key)", items);
        diff.sections.put("Script API item components (+ added to, - removed from)", componentChanges.entrySet().stream()
            .map(e -> e.getKey() + ": " + e.getValue().size() + " items" + (e.getValue().size() <= 10 ? " " + e.getValue()
                : ", e.g. " + e.getValue().subList(0, 5))).toList());

        tags(diff, "item tags", before.itemTags(), after.itemTags());
        tags(diff, "block tags", before.blockTags(), after.blockTags());

        List<String> blockItems = new ArrayList<>();
        Set<String> names = new TreeSet<>(before.blockItems().keySet());
        names.addAll(after.blockItems().keySet());
        for (String name : names) {
            if (!Objects.equals(before.blockItems().get(name), after.blockItems().get(name))) {
                blockItems.add(name + ": " + before.blockItems().get(name) + " -> " + after.blockItems().get(name));
            }
        }
        diff.sections.put("block to item changes", blockItems);
        diff.sections.put("addon errors", after.errors());
        return diff;
    }

    private static String describe(BdsDump.Item item) {
        return "(stack " + item.maxStackSize() + (item.maxDurability() > 0 ? ", durability " + item.maxDurability() : "")
            + ", key " + item.localizationKey() + ", components " + item.components() + ")";
    }

    private static void tags(DataDiff diff, String what, Map<String, List<String>> before, Map<String, List<String>> after) {
        diff.sections.put("new " + what, DataDiff.difference(after.keySet(), before.keySet()).stream()
            .map(tag -> tag + ": " + after.get(tag)).toList());
        diff.sections.put("removed " + what, List.copyOf(DataDiff.difference(before.keySet(), after.keySet())));
        List<String> changed = new ArrayList<>();
        for (String tag : new TreeSet<>(before.keySet())) {
            if (!after.containsKey(tag)) {
                continue;
            }
            Set<String> added = DataDiff.difference(new TreeSet<>(after.get(tag)), new TreeSet<>(before.get(tag)));
            Set<String> removed = DataDiff.difference(new TreeSet<>(before.get(tag)), new TreeSet<>(after.get(tag)));
            if (!added.isEmpty() || !removed.isEmpty()) {
                changed.add(tag + ": +" + added + " -" + removed);
            }
        }
        diff.sections.put(what + " membership", changed);
    }
}
