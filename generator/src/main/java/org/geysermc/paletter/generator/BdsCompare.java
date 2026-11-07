package org.geysermc.paletter.generator;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.data.VanillaItem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Cross-checks a BDS addon dump against the network data of the same version: block states and defaults against the
 * block palette, items and stack sizes against the item list and components, item tags against another tag source.
 * Everything here is a report; nothing fails.
 */
final class BdsCompare {
    private static final int LIMIT = 40;

    private BdsCompare() {
    }

    static void compare(BdsDump dump, VanillaData data, Map<String, Map<String, List<String>>> tagSources) {
        System.out.println("Comparing the BDS " + dump.version() + " dump with " + data.version());
        if (!dump.errors().isEmpty()) {
            section("addon errors", dump.errors());
        }
        compareBlocks(dump, data);
        compareItems(dump, data);
        tagSources.forEach((name, tags) -> compareTags(name, dump.itemTags(), tags));
    }

    private static void compareBlocks(BdsDump dump, VanillaData data) {
        // State values per block as the palette has them
        Map<String, Map<String, Set<Object>>> palette = new TreeMap<>();
        Map<String, Integer> stateCounts = new TreeMap<>();
        for (NbtMap state : data.blockStates()) {
            stateCounts.merge(BlockStates.name(state), 1, Integer::sum);
            Map<String, Set<Object>> values = palette.computeIfAbsent(BlockStates.name(state), n -> new TreeMap<>());
            BlockStates.states(state).forEach((key, value) -> values.computeIfAbsent(key, k -> new TreeSet<>(BdsCompare::compareValues)).add(value));
        }

        Set<String> onlyBds = new TreeSet<>(dump.blockDefaults().keySet());
        onlyBds.removeAll(palette.keySet());
        Set<String> onlyPalette = new TreeSet<>(palette.keySet());
        onlyPalette.removeAll(dump.blockDefaults().keySet());
        section("blocks the Script API knows, but not the block palette", onlyBds);
        section("blocks in the block palette the Script API does not know", onlyPalette);

        // The Script API adds legacy alias states (wood_type, color, direction, ...); they are not network states
        Map<String, Integer> aliases = new TreeMap<>();
        List<String> stateProblems = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<Object>>> entry : dump.blockStates().entrySet()) {
            Map<String, Set<Object>> expected = palette.get(entry.getKey());
            if (expected == null) {
                continue;
            }
            Set<String> missing = new TreeSet<>(expected.keySet());
            missing.removeAll(entry.getValue().keySet());
            if (!missing.isEmpty()) {
                stateProblems.add(entry.getKey() + ": palette states " + missing + " are unknown to the Script API");
            }
            entry.getValue().forEach((key, values) -> {
                Set<Object> palettesValues = expected.get(key);
                if (palettesValues == null) {
                    aliases.merge(key, 1, Integer::sum);
                    return;
                }
                Set<Object> set = new TreeSet<>(BdsCompare::compareValues);
                set.addAll(values);
                if (!set.equals(palettesValues)) {
                    stateProblems.add(entry.getKey() + " " + key + ": " + set + " in BDS, " + palettesValues + " in the palette");
                }
            });
        }
        // Is the palette the full cartesian product of the values the Script API reports? Then it could be generated
        List<String> notProducts = new ArrayList<>();
        dump.blockStates().forEach((name, values) -> {
            Map<String, Set<Object>> expected = palette.get(name);
            if (expected == null) {
                return;
            }
            long product = 1;
            for (Map.Entry<String, List<Object>> state : values.entrySet()) {
                if (expected.containsKey(state.getKey())) {
                    product *= state.getValue().size();
                }
            }
            if (product != stateCounts.get(name)) {
                notProducts.add(name + ": " + stateCounts.get(name) + " palette states, " + product + " combinations of the Script API values");
            }
        });
        section("blocks whose palette states are not all combinations of the Script API values", notProducts);
        section("legacy alias states the Script API adds (state: blocks)", aliases.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList());
        section("block state differences (palette states and their values)", stateProblems);

        List<String> defaultProblems = new ArrayList<>();
        Map<String, NbtMap> defaults = dump.projectDefaults(data.blockStates(), defaultProblems);
        Map<String, NbtMap> first = new TreeMap<>();
        data.blockStates().forEach(state -> first.putIfAbsent(BlockStates.name(state), state));
        List<String> nonFirst = new ArrayList<>();
        defaults.forEach((name, state) -> {
            if (!state.equals(first.get(name))) {
                nonFirst.add(name + ": " + BlockStates.states(state).toString().replaceAll("\\s+", " "));
            }
        });
        section("block defaults that are not palette states (after dropping alias states)", defaultProblems);
        section("blocks whose default is not their first palette state", nonFirst);
        System.out.println("  " + dump.blockTags().size() + " block tags; " + dump.blockItems().size() + " blocks have an item");
    }

    private static void compareItems(BdsDump dump, VanillaData data) {
        Map<String, VanillaItem> items = new TreeMap<>();
        data.items().forEach(item -> items.put(item.identifier(), item));
        if (dump.items().isEmpty()) {
            return;
        }
        Set<String> onlyBds = new TreeSet<>(dump.items().keySet());
        onlyBds.removeAll(items.keySet());
        Set<String> onlyNetwork = new TreeSet<>(items.keySet());
        onlyNetwork.removeAll(dump.items().keySet());
        section("items the Script API knows, but not the item list", onlyBds);
        section("items in the item list the Script API does not know (or could not make a stack of)", onlyNetwork);

        List<String> stackProblems = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, BdsDump.Item> entry : dump.items().entrySet()) {
            VanillaItem item = items.get(entry.getKey());
            if (item == null || item.componentData() == null) {
                continue;
            }
            NbtMap properties = item.componentData().getCompound("components", NbtMap.EMPTY).getCompound("item_properties", null);
            if (properties == null || !properties.containsKey("max_stack_size")) {
                continue;
            }
            checked++;
            int network = properties.getInt("max_stack_size");
            if (network != entry.getValue().maxStackSize()) {
                stackProblems.add(entry.getKey() + ": " + entry.getValue().maxStackSize() + " in BDS, " + network + " in the item components");
            }
        }
        section("max stack size differences (" + checked + " component based items checked)", stackProblems);
    }

    private static void compareTags(String name, Map<String, List<String>> bds, Map<String, List<String>> other) {
        Set<String> onlyBds = new TreeSet<>(bds.keySet());
        onlyBds.removeAll(other.keySet());
        Set<String> onlyOther = new TreeSet<>(other.keySet());
        onlyOther.removeAll(bds.keySet());
        List<String> differences = new ArrayList<>();
        for (String tag : new TreeSet<>(bds.keySet())) {
            if (!other.containsKey(tag)) {
                continue;
            }
            Set<String> added = new TreeSet<>(bds.get(tag));
            added.removeAll(other.get(tag));
            Set<String> removed = new TreeSet<>(other.get(tag));
            removed.removeAll(bds.get(tag));
            if (!added.isEmpty() || !removed.isEmpty()) {
                differences.add(tag + ": +" + added + " -" + removed);
            }
        }
        System.out.println("Item tags, BDS vs " + name + ": " + bds.size() + " vs " + other.size() + " tags");
        section("item tags only in BDS", onlyBds);
        section("item tags only in " + name, onlyOther);
        section("item tag membership (+ only in BDS, - only in " + name + ")", differences);
    }

    private static void section(String title, java.util.Collection<String> lines) {
        System.out.println("  " + title + ": " + lines.size());
        int shown = 0;
        for (String line : new LinkedHashSet<>(lines)) {
            if (shown++ == LIMIT) {
                System.out.println("      ... " + (lines.size() - LIMIT) + " more");
                break;
            }
            System.out.println("      " + line);
        }
    }

    private static int compareValues(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Integer.compare(x.intValue(), y.intValue());
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }
}
