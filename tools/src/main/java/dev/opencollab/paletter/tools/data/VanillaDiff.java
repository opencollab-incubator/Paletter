package dev.opencollab.paletter.tools.data;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.data.VanillaItem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static dev.opencollab.paletter.tools.data.DiffText.*;

/**
 * Two versions of vanilla data (bundled vs freshly captured, previous vs new): blocks and their states, items, item
 * components, data driven blocks, creative layout, item tags, block defaults. See {@link Diff#vanilla}.
 */
final class VanillaDiff extends DiffBuilder {
    /** Two versions of vanilla data; {@code network} compares what the network sends, {@code bds} what only the Script API knows. */
    static Diff of(VanillaData before, VanillaData after, boolean network, boolean bds) {
        VanillaDiff vanilla = new VanillaDiff();
        if (network) {
            vanilla.blocks(before, after);
            vanilla.items(before, after);
            vanilla.dataDriven(before, after);
            vanilla.creative(before, after);
        }
        if (bds) {
            vanilla.tags(before.itemTags(), after.itemTags());
            vanilla.defaults(before, after);
            vanilla.blockItems(before, after);
        }
        return vanilla.diff;
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

    private void blockItems(VanillaData before, VanillaData after) {
        List<String> changed = new ArrayList<>();
        Set<String> names = new TreeSet<>(before.blockItems().keySet());
        names.addAll(after.blockItems().keySet());
        for (String name : names) {
            String x = before.blockItems().get(name);
            String y = after.blockItems().get(name);
            if (!Objects.equals(x, y)) {
                changed.add(name + ": " + (x == null ? "no item" : x) + " -> " + (y == null ? "no item" : y));
            }
        }
        put("block to item relation", changed);
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
}
