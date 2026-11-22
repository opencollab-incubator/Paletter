package dev.opencollab.paletter.tools.data;

import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.internal.creative.RawGroup;
import dev.opencollab.paletter.internal.creative.RawNode;
import dev.opencollab.paletter.internal.creative.RawStack;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.data.VanillaItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** What the comparisons of {@link Diff} share: set differences, NBT paths and the text of states and stacks. */
final class DiffText {
    private DiffText() {
    }

    static Map<String, List<NbtMap>> statesByBlock(List<NbtMap> states) {
        Map<String, List<NbtMap>> blocks = new LinkedHashMap<>();
        states.forEach(state -> blocks.computeIfAbsent(BlockStates.name(state), n -> new ArrayList<>()).add(state));
        return blocks;
    }

    static Set<String> keys(List<NbtMap> states) {
        Set<String> keys = new TreeSet<>();
        states.forEach(state -> keys.addAll(BlockStates.states(state).keySet()));
        return keys;
    }

    static Map<String, VanillaItem> itemMap(VanillaData data) {
        Map<String, VanillaItem> items = new TreeMap<>();
        data.items().forEach(item -> items.put(item.identifier(), item));
        return items;
    }

    static List<String> flatten(List<RawNode> nodes) {
        List<String> out = new ArrayList<>();
        for (RawNode node : nodes) {
            if (node instanceof RawGroup group) {
                out.add("[group " + group.name() + " icon " + (group.icon() == null ? "-" : describe(group.icon())) + "]");
                group.stacks().forEach(stack -> out.add(group.name() + " / " + describe(stack)));
            } else {
                out.add(describe((RawStack) node));
            }
        }
        return out;
    }

    static String describe(NbtMap state) {
        return BlockStates.name(state) + " " + compact(BlockStates.states(state));
    }

    static String describe(RawStack stack) {
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

    static String shorten(Object value) {
        String text = compact(value);
        return text.length() > 120 ? text.substring(0, 117) + "..." : text;
    }

    static <T extends Comparable<T>> Set<T> difference(Set<T> a, Set<T> b) {
        Set<T> out = new TreeSet<>(a);
        out.removeAll(b);
        return out;
    }

    static <T> Set<T> union(Set<T> a, Set<T> b) {
        Set<T> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return union;
    }
}
