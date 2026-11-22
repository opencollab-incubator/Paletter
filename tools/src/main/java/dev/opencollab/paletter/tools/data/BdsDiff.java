package dev.opencollab.paletter.tools.data;

import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.data.VanillaItem;
import dev.opencollab.paletter.tools.bds.BdsDump;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static dev.opencollab.paletter.tools.data.DiffText.*;

/**
 * Script API dumps of BDS: one against a bundled version ({@link Diff#bdsDump}: block states and defaults, items and stack
 * sizes, item tags) and one against another dump ({@link Diff#scriptApi}).
 */
final class BdsDiff extends DiffBuilder {
    /** A Script API dump against a bundled version. */
    static Diff dump(BdsDump dump, VanillaData data, Map<String, Map<String, List<String>>> tagSources) {
        BdsDiff bds = new BdsDiff();
        if (!dump.errors().isEmpty()) {
            bds.putDistinct("addon errors", dump.errors());
        }
        bds.bdsBlocks(dump, data);
        bds.bdsItems(dump, data);
        tagSources.forEach((tagName, tags) -> bds.bdsTags(tagName, dump.itemTags(), tags));
        return bds.diff;
    }

    private void bdsBlocks(BdsDump dump, VanillaData data) {
        // State values per block as the palette has them
        Map<String, Map<String, Set<Object>>> palette = new TreeMap<>();
        Map<String, Integer> stateCounts = new TreeMap<>();
        for (NbtMap state : data.blockStates()) {
            stateCounts.merge(BlockStates.name(state), 1, Integer::sum);
            Map<String, Set<Object>> values = palette.computeIfAbsent(BlockStates.name(state), n -> new TreeMap<>());
            BlockStates.states(state).forEach((key, value) -> values.computeIfAbsent(key, k -> new TreeSet<>(BdsDiff::compareValues)).add(value));
        }

        Set<String> onlyBds = new TreeSet<>(dump.blockDefaults().keySet());
        onlyBds.removeAll(palette.keySet());
        Set<String> onlyPalette = new TreeSet<>(palette.keySet());
        onlyPalette.removeAll(dump.blockDefaults().keySet());
        putDistinct("blocks the Script API knows, but not the block palette", onlyBds);
        putDistinct("blocks in the block palette the Script API does not know", onlyPalette);

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
                Set<Object> set = new TreeSet<>(BdsDiff::compareValues);
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
        putDistinct("blocks whose palette states are not all combinations of the Script API values", notProducts);
        putDistinct("legacy alias states the Script API adds (state: blocks)", aliases.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList());
        putDistinct("block state differences (palette states and their values)", stateProblems);

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
        putDistinct("block defaults that are not palette states (after dropping alias states)", defaultProblems);
        putDistinct("blocks whose default is not their first palette state", nonFirst);
        System.out.println("  " + dump.blockTags().size() + " block tags; " + dump.blockItems().size() + " blocks have an item");
    }

    private void bdsItems(BdsDump dump, VanillaData data) {
        Map<String, VanillaItem> items = new TreeMap<>();
        data.items().forEach(item -> items.put(item.identifier(), item));
        if (dump.items().isEmpty()) {
            return;
        }
        Set<String> onlyBds = new TreeSet<>(dump.items().keySet());
        onlyBds.removeAll(items.keySet());
        Set<String> onlyNetwork = new TreeSet<>(items.keySet());
        onlyNetwork.removeAll(dump.items().keySet());
        putDistinct("items the Script API knows, but not the item list", onlyBds);
        putDistinct("items in the item list the Script API does not know (or could not make a stack of)", onlyNetwork);

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
        putDistinct("max stack size differences (" + checked + " component based items checked)", stackProblems);
    }

    private void bdsTags(String name, Map<String, List<String>> bds, Map<String, List<String>> other) {
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
        putDistinct("item tags only in BDS", onlyBds);
        putDistinct("item tags only in " + name, onlyOther);
        putDistinct("item tag membership (+ only in BDS, - only in " + name + ")", differences);
    }

    private static int compareValues(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Integer.compare(x.intValue(), y.intValue());
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Script API dump against Script API dump

    static Diff scriptApi(BdsDump before, BdsDump after) {
        BdsDiff bds = new BdsDiff();
        Set<String> blocksBefore = before.blockStates().isEmpty() ? before.blockDefaults().keySet() : before.blockStates().keySet();
        Set<String> blocksAfter = after.blockStates().isEmpty() ? after.blockDefaults().keySet() : after.blockStates().keySet();
        bds.put("new blocks", difference(new TreeSet<>(blocksAfter), new TreeSet<>(blocksBefore)).stream()
            .map(name -> name + " " + compact(after.blockStates().getOrDefault(name, Map.of()))).toList());
        bds.put("removed blocks", List.copyOf(difference(new TreeSet<>(blocksBefore), new TreeSet<>(blocksAfter))));

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
        bds.put("block state changes", states);

        List<String> defaults = new ArrayList<>();
        before.blockDefaults().forEach((name, x) -> {
            var y = after.blockDefaults().get(name);
            if (y != null && !x.equals(y)) {
                defaults.add(name + ": " + compact(BlockStates.states(x)) + " -> " + compact(BlockStates.states(y)));
            }
        });
        bds.put("block default changes", defaults);

        bds.put("new items", difference(after.items().keySet(), before.items().keySet()).stream()
            .map(name -> name + " " + describeItem(after.items().get(name))).toList());
        bds.put("removed items", List.copyOf(difference(before.items().keySet(), after.items().keySet())));
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
                items.add(name + ": " + describeItem(x) + " -> " + describeItem(y));
            }
            difference(new TreeSet<>(y.components()), new TreeSet<>(x.components()))
                .forEach(c -> componentChanges.computeIfAbsent("+" + c, k -> new ArrayList<>()).add(name));
            difference(new TreeSet<>(x.components()), new TreeSet<>(y.components()))
                .forEach(c -> componentChanges.computeIfAbsent("-" + c, k -> new ArrayList<>()).add(name));
        });
        bds.put("item changes (stack size, durability, localization key)", items);
        bds.put("Script API item components (+ added to, - removed from)", componentChanges.entrySet().stream()
            .map(e -> e.getKey() + ": " + e.getValue().size() + " items" + (e.getValue().size() <= 10 ? " " + e.getValue()
                : ", e.g. " + e.getValue().subList(0, 5))).toList());

        bds.scriptTags("item tags", before.itemTags(), after.itemTags());
        bds.scriptTags("block tags", before.blockTags(), after.blockTags());

        List<String> blockItems = new ArrayList<>();
        Set<String> names = new TreeSet<>(before.blockItems().keySet());
        names.addAll(after.blockItems().keySet());
        for (String name : names) {
            if (!Objects.equals(before.blockItems().get(name), after.blockItems().get(name))) {
                blockItems.add(name + ": " + before.blockItems().get(name) + " -> " + after.blockItems().get(name));
            }
        }
        bds.put("block to item changes", blockItems);
        bds.put("addon errors", after.errors());
        return bds.diff;
    }

    private static String describeItem(BdsDump.Item item) {
        return "(stack " + item.maxStackSize() + (item.maxDurability() > 0 ? ", durability " + item.maxDurability() : "")
            + ", key " + item.localizationKey() + ", components " + item.components() + ")";
    }

    private void scriptTags(String what, Map<String, List<String>> before, Map<String, List<String>> after) {
        put("new " + what, difference(after.keySet(), before.keySet()).stream()
            .map(tag -> tag + ": " + after.get(tag)).toList());
        put("removed " + what, List.copyOf(difference(before.keySet(), after.keySet())));
        List<String> changed = new ArrayList<>();
        for (String tag : new TreeSet<>(before.keySet())) {
            if (!after.containsKey(tag)) {
                continue;
            }
            Set<String> added = difference(new TreeSet<>(after.get(tag)), new TreeSet<>(before.get(tag)));
            Set<String> removed = difference(new TreeSet<>(before.get(tag)), new TreeSet<>(after.get(tag)));
            if (!added.isEmpty() || !removed.isEmpty()) {
                changed.add(tag + ": +" + added + " -" + removed);
            }
        }
        put(what + " membership", changed);
    }
}
