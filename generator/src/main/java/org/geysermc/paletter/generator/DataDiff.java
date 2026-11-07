package org.geysermc.paletter.generator;

import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativeGroup;
import org.geysermc.paletter.creative.CreativeNode;
import org.geysermc.paletter.creative.CreativeStack;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.data.VanillaItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Semantic differences between two versions of vanilla data (bundled vs freshly captured, or the previous version vs
 * a new one): blocks and their states, items, item components, data driven blocks, the creative layout, item tags and
 * block defaults. Every section is a list of lines; empty sections mean no difference.
 */
final class DataDiff {
    final Map<String, List<String>> sections = new LinkedHashMap<>();

    static DataDiff of(VanillaData before, VanillaData after, boolean network, boolean bds) {
        DataDiff diff = new DataDiff();
        if (network) {
            diff.blocks(before, after);
            diff.items(before, after);
            diff.dataDriven(before, after);
            diff.creative(before, after);
        }
        if (bds) {
            diff.tags(before.itemTags(), after.itemTags());
            diff.defaults(before, after);
        }
        return diff;
    }

    boolean identical() {
        return sections.values().stream().allMatch(List::isEmpty);
    }

    int count() {
        return sections.values().stream().mapToInt(List::size).sum();
    }

    private void blocks(VanillaData before, VanillaData after) {
        Map<String, List<NbtMap>> a = statesByBlock(before.blockStates());
        Map<String, List<NbtMap>> b = statesByBlock(after.blockStates());
        put("new blocks", difference(b.keySet(), a.keySet()).stream()
            .map(name -> name + " (" + b.get(name).size() + " states)").toList());
        put("removed blocks", difference(a.keySet(), b.keySet()).stream().toList());
        List<String> changed = new ArrayList<>();
        for (String name : new TreeSet<>(a.keySet())) {
            List<NbtMap> x = a.get(name);
            List<NbtMap> y = b.get(name);
            if (y == null || x.equals(y)) {
                continue;
            }
            Set<NbtMap> added = new LinkedHashSet<>(y);
            x.forEach(added::remove);
            Set<NbtMap> removed = new LinkedHashSet<>(x);
            y.forEach(removed::remove);
            if (added.isEmpty() && removed.isEmpty()) {
                changed.add(name + ": same states, different order");
            } else {
                changed.add(name + ": " + x.size() + " -> " + y.size() + " states; state keys " + keys(x) + " -> " + keys(y)
                    + (added.isEmpty() ? "" : "; e.g. new " + BlockStates.states(added.iterator().next())));
            }
        }
        put("blocks with changed states", changed);
        if (changed.isEmpty() && a.keySet().equals(b.keySet()) && !before.blockStates().equals(after.blockStates())) {
            put("block palette order", List.of("same states, different palette order (sequential runtime ids change)"));
        }
    }

    private void items(VanillaData before, VanillaData after) {
        Map<String, VanillaItem> a = itemMap(before);
        Map<String, VanillaItem> b = itemMap(after);
        put("new items", difference(b.keySet(), a.keySet()).stream()
            .map(name -> name + " (id " + b.get(name).runtimeId() + (b.get(name).componentBased() ? ", component based" : "") + ")").toList());
        put("removed items", List.copyOf(difference(a.keySet(), b.keySet())));
        List<String> ids = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        List<String> components = new ArrayList<>();
        a.forEach((name, x) -> {
            VanillaItem y = b.get(name);
            if (y == null) {
                return;
            }
            if (x.runtimeId() != y.runtimeId()) {
                ids.add(name + ": " + x.runtimeId() + " -> " + y.runtimeId());
            }
            if (x.version() != y.version() || x.componentBased() != y.componentBased()) {
                flags.add(name + ": version " + x.version() + " -> " + y.version() + ", componentBased " + x.componentBased() + " -> " + y.componentBased());
            }
            List<String> lines = new ArrayList<>();
            nbt("", x.componentData() == null ? NbtMap.EMPTY : x.componentData(), y.componentData() == null ? NbtMap.EMPTY : y.componentData(), lines);
            if (!lines.isEmpty()) {
                components.add(name + ": " + String.join("; ", lines));
            }
        });
        put("item runtime id changes", ids);
        put("item version/componentBased changes", flags);
        put("item component data changes", components);
    }

    private void dataDriven(VanillaData before, VanillaData after) {
        Map<String, NbtMap> a = new TreeMap<>();
        before.dataDrivenBlocks().forEach(block -> a.put(block.getName(), block.getProperties()));
        Map<String, NbtMap> b = new TreeMap<>();
        for (BlockPropertyData block : after.dataDrivenBlocks()) {
            b.put(block.getName(), block.getProperties());
        }
        put("new data driven blocks", List.copyOf(difference(b.keySet(), a.keySet())));
        put("removed data driven blocks", List.copyOf(difference(a.keySet(), b.keySet())));
        List<String> changed = new ArrayList<>();
        a.forEach((name, x) -> {
            NbtMap y = b.get(name);
            if (y != null) {
                List<String> lines = new ArrayList<>();
                nbt("", x, y, lines);
                if (!lines.isEmpty()) {
                    changed.add(name + ": " + String.join("; ", lines));
                }
            }
        });
        put("data driven block property changes", changed);
    }

    private void creative(VanillaData before, VanillaData after) {
        List<String> lines = new ArrayList<>();
        for (CreativeCategory category : CreativeCategory.values()) {
            List<String> x = flatten(before.creative().nodes(category));
            List<String> y = flatten(after.creative().nodes(category));
            if (x.equals(y)) {
                continue;
            }
            Set<String> added = difference(new LinkedHashSet<>(y), new LinkedHashSet<>(x));
            Set<String> removed = difference(new LinkedHashSet<>(x), new LinkedHashSet<>(y));
            lines.add(category + ": " + x.size() + " -> " + y.size() + " entries");
            added.forEach(entry -> lines.add(category + " + " + entry));
            removed.forEach(entry -> lines.add(category + " - " + entry));
            if (added.isEmpty() && removed.isEmpty()) {
                for (int i = 0; i < x.size(); i++) {
                    if (!x.get(i).equals(y.get(i))) {
                        lines.add(category + " order differs from #" + i + ": " + x.get(i) + " -> " + y.get(i));
                        break;
                    }
                }
            }
        }
        put("creative layout", lines);
    }

    private void tags(Map<String, List<String>> before, Map<String, List<String>> after) {
        put("new item tags", List.copyOf(difference(after.keySet(), before.keySet())));
        put("removed item tags", List.copyOf(difference(before.keySet(), after.keySet())));
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
        put("item tag membership", changed);
    }

    private void defaults(VanillaData before, VanillaData after) {
        List<String> changed = new ArrayList<>();
        Set<String> names = new TreeSet<>(before.blockDefaults().keySet());
        names.addAll(after.blockDefaults().keySet());
        for (String name : names) {
            NbtMap x = before.blockDefaults().get(name);
            NbtMap y = after.blockDefaults().get(name);
            if (!Objects.equals(x, y)) {
                changed.add(name + ": " + (x == null ? "first state" : compact(BlockStates.states(x))) + " -> "
                    + (y == null ? "first state" : compact(BlockStates.states(y))));
            }
        }
        put("block defaults (non first state)", changed);
    }

    private void put(String title, List<String> lines) {
        sections.put(title, lines);
    }

    private static Map<String, List<NbtMap>> statesByBlock(List<NbtMap> states) {
        Map<String, List<NbtMap>> blocks = new LinkedHashMap<>();
        states.forEach(state -> blocks.computeIfAbsent(BlockStates.name(state), n -> new ArrayList<>()).add(state));
        return blocks;
    }

    private static Set<String> keys(List<NbtMap> states) {
        Set<String> keys = new TreeSet<>();
        states.forEach(state -> keys.addAll(BlockStates.states(state).keySet()));
        return keys;
    }

    private static Map<String, VanillaItem> itemMap(VanillaData data) {
        Map<String, VanillaItem> items = new TreeMap<>();
        data.items().forEach(item -> items.put(item.identifier(), item));
        return items;
    }

    static List<String> flatten(List<CreativeNode> nodes) {
        List<String> out = new ArrayList<>();
        for (CreativeNode node : nodes) {
            if (node instanceof CreativeGroup group) {
                out.add("[group " + group.name() + " icon " + (group.icon() == null ? "-" : describe(group.icon())) + "]");
                group.stacks().forEach(stack -> out.add(group.name() + " / " + describe(stack)));
            } else {
                out.add(describe((CreativeStack) node));
            }
        }
        return out;
    }

    private static String describe(CreativeStack stack) {
        return stack.identifier() + (stack.damage() != 0 ? "@" + stack.damage() : "") + (stack.count() != 1 ? "x" + stack.count() : "")
            + (stack.blockState() != null ? " " + compact(stack.blockState().getCompound("states")) : "")
            + (stack.tag() != null ? " " + compact(stack.tag()) : "");
    }

    /** Path level NBT diff; also reports type changes of equal looking values. */
    static void nbt(String path, Object a, Object b, List<String> out) {
        if (a instanceof NbtMap mapA && b instanceof NbtMap mapB) {
            Set<String> keys = new LinkedHashSet<>(mapA.keySet());
            keys.addAll(mapB.keySet());
            for (String key : keys) {
                String child = path.isEmpty() ? key : path + "." + key;
                if (!mapA.containsKey(key)) {
                    out.add("+" + child + "=" + shorten(mapB.get(key)));
                } else if (!mapB.containsKey(key)) {
                    out.add("-" + child);
                } else {
                    nbt(child, mapA.get(key), mapB.get(key), out);
                }
            }
        } else if (a instanceof NbtList<?> listA && b instanceof NbtList<?> listB && listA.size() == listB.size()) {
            for (int i = 0; i < listA.size(); i++) {
                nbt(path + "[" + i + "]", listA.get(i), listB.get(i), out);
            }
        } else if (!Objects.deepEquals(a, b) || (a != null && b != null && a.getClass() != b.getClass())) {
            out.add(path + ": " + shorten(a) + " -> " + shorten(b));
        }
    }

    static String compact(Object value) {
        return String.valueOf(value).replaceAll("\\s+", " ");
    }

    private static String shorten(Object value) {
        String text = compact(value);
        return text.length() > 120 ? text.substring(0, 117) + "..." : text;
    }

    static <T extends Comparable<T>> Set<T> difference(Set<T> a, Set<T> b) {
        Set<T> out = new TreeSet<>(a);
        out.removeAll(b);
        return out;
    }
}
