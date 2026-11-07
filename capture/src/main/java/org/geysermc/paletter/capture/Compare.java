package org.geysermc.paletter.capture;

import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
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
import java.util.TreeSet;

/** Diffs a capture against a bundled version: items, item components, block properties, creative layout. */
final class Compare {
    private static final int MAX_LINES = 40;

    private final CapturedData capture;
    private final VanillaData reference;
    private boolean identical = true;

    Compare(CapturedData capture, VanillaData reference) {
        this.capture = capture;
        this.reference = reference;
    }

    boolean run() {
        System.out.println("Comparing capture with bundled " + reference.version());
        compareItems();
        compareBlocks();
        compareCreative();
        System.out.println(identical ? "IDENTICAL" : "DIFFERENT");
        return identical;
    }

    private void compareItems() {
        Map<String, VanillaItem> bundled = new LinkedHashMap<>();
        reference.items().forEach(item -> bundled.put(item.identifier(), item));
        report("items only in the capture", difference(capture.items().keySet(), bundled.keySet()));
        report("items only in the bundled data", difference(bundled.keySet(), capture.items().keySet()));
        List<String> ids = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        List<String> components = new ArrayList<>();
        capture.items().forEach((name, wire) -> {
            VanillaItem item = bundled.get(name);
            if (item == null) {
                return;
            }
            if (item.runtimeId() != wire.getShort("id")) {
                ids.add(name + ": " + item.runtimeId() + " -> " + wire.getShort("id"));
            }
            if (item.version() != wire.getInt("version") || item.componentBased() != wire.getBoolean("componentBased")) {
                flags.add(name + ": version " + item.version() + " -> " + wire.getInt("version")
                    + ", componentBased " + item.componentBased() + " -> " + wire.getBoolean("componentBased"));
            }
            NbtMap bundledData = item.componentData() == null ? NbtMap.EMPTY : item.componentData();
            List<String> diff = new ArrayList<>();
            diff("", bundledData, wire.getCompound("data"), diff);
            if (!diff.isEmpty()) {
                components.add(name + ": " + String.join("; ", diff));
            }
        });
        report("item runtime id changes", ids);
        report("item version/componentBased changes", flags);
        report("item component data changes", components);
    }

    private void compareBlocks() {
        Map<String, NbtMap> bundled = new LinkedHashMap<>();
        for (BlockPropertyData block : reference.dataDrivenBlocks()) {
            bundled.put(block.getName(), block.getProperties());
        }
        report("block properties only in the capture", difference(capture.blockProperties().keySet(), bundled.keySet()));
        report("block properties only in the bundled data", difference(bundled.keySet(), capture.blockProperties().keySet()));
        List<String> changes = new ArrayList<>();
        capture.blockProperties().forEach((name, wire) -> {
            NbtMap properties = bundled.get(name);
            if (properties != null) {
                List<String> diff = new ArrayList<>();
                diff("", properties, wire, diff);
                if (!diff.isEmpty()) {
                    changes.add(name + ": " + String.join("; ", diff));
                }
            }
        });
        report("block property changes", changes);
    }

    private void compareCreative() {
        for (CreativeCategory category : CreativeCategory.values()) {
            List<String> wire = flatten(capture.creative().nodes(category));
            List<String> bundled = flatten(reference.creative().nodes(category));
            if (wire.equals(bundled)) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            lines.add("size " + bundled.size() + " -> " + wire.size());
            Set<String> added = difference(new LinkedHashSet<>(wire), new LinkedHashSet<>(bundled));
            Set<String> removed = difference(new LinkedHashSet<>(bundled), new LinkedHashSet<>(wire));
            added.forEach(entry -> lines.add("+ " + entry));
            removed.forEach(entry -> lines.add("- " + entry));
            if (added.isEmpty() && removed.isEmpty()) {
                for (int i = 0; i < Math.min(wire.size(), bundled.size()); i++) {
                    if (!wire.get(i).equals(bundled.get(i))) {
                        lines.add("order differs from #" + i + ": " + bundled.get(i) + " -> " + wire.get(i));
                        break;
                    }
                }
            }
            report("creative " + category, lines);
        }
    }

    private static List<String> flatten(List<CreativeNode> nodes) {
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
            + (stack.blockState() != null ? " " + stack.blockState().getCompound("states") : "")
            + (stack.tag() != null ? " " + stack.tag() : "");
    }

    /** Path level NBT diff; also reports type changes of equal looking values. */
    static void diff(String path, Object a, Object b, List<String> out) {
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
                    diff(child, mapA.get(key), mapB.get(key), out);
                }
            }
        } else if (a instanceof NbtList<?> listA && b instanceof NbtList<?> listB && listA.size() == listB.size()) {
            for (int i = 0; i < listA.size(); i++) {
                diff(path + "[" + i + "]", listA.get(i), listB.get(i), out);
            }
        } else if (!Objects.deepEquals(a, b) || (a != null && b != null && a.getClass() != b.getClass())) {
            out.add(path + ": " + shorten(a) + " -> " + shorten(b));
        }
    }

    private static String shorten(Object value) {
        String text = String.valueOf(value).replaceAll("\\s+", " ");
        return text.length() > 120 ? text.substring(0, 117) + "..." : text;
    }

    private static Set<String> difference(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.removeAll(b);
        return out;
    }

    private void report(String title, java.util.Collection<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        identical = false;
        System.out.println("  " + title + ": " + lines.size());
        lines.stream().limit(MAX_LINES).forEach(line -> System.out.println("      " + line));
        if (lines.size() > MAX_LINES) {
            System.out.println("      ... " + (lines.size() - MAX_LINES) + " more");
        }
    }
}
