package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.bds.BdsDump;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.tools.client.CapturedData;
import org.geysermc.paletter.internal.creative.RawStack;
import org.geysermc.paletter.internal.data.ExperimentContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a set of experiment toggles changes, from a capture (and Script API dump) without them against one with them.
 * Runtime ids are ignored: custom and experimental items shift them, they are per server content, not per toggle.
 */
public final class ExperimentAttribution {
    private ExperimentAttribution() {
    }

    /**
     * @param itemsAdded items in the registry only with the toggles
     * @param itemsChanged items whose version, component based flag or component data the toggles change
     * @param scriptItemsAdded items the Script API ({@code ItemTypes}) only lists with the toggles
     * @param blocksAdded blocks the Script API ({@code BlockTypes}) only lists with the toggles
     * @param dataDrivenBlocks blocks whose start game block properties are new or changed
     * @param creativeAdded creative stacks only sent with the toggles, as {@code id} or {@code id:damage}
     * @param removed anything that is gone with the toggles, prefixed with its kind
     */
    record Diff(List<String> itemsAdded, List<String> itemsChanged, List<String> scriptItemsAdded, List<String> blocksAdded,
                List<String> dataDrivenBlocks, List<String> creativeAdded, List<String> removed) {

        boolean isEmpty() {
            return itemsAdded.isEmpty() && itemsChanged.isEmpty() && scriptItemsAdded.isEmpty() && blocksAdded.isEmpty()
                && dataDrivenBlocks.isEmpty() && creativeAdded.isEmpty() && removed.isEmpty();
        }

        /** What goes into the bundle: every item and block the toggles add or turn on. */
        ExperimentContent content(Set<String> creativeBefore) {
            Set<String> items = new TreeSet<>(itemsAdded);
            items.addAll(itemsChanged);
            items.addAll(scriptItemsAdded);
            for (String stack : creativeAdded) {
                String id = stack.contains(":") && stack.lastIndexOf(':') > stack.indexOf(':') ? stack.substring(0, stack.lastIndexOf(':')) : stack;
                if (!creativeBefore.contains(id)) {
                    items.add(id); // the toggle puts an item into the creative inventory for the first time
                }
            }
            Set<String> blocks = new TreeSet<>(blocksAdded);
            blocks.addAll(dataDrivenBlocks);
            return new ExperimentContent(List.copyOf(items), List.copyOf(blocks), creativeAdded);
        }

        List<String> lines() {
            List<String> lines = new ArrayList<>();
            add(lines, "items added", itemsAdded);
            add(lines, "items changed", itemsChanged);
            add(lines, "Script API items added", scriptItemsAdded);
            add(lines, "Script API blocks added", blocksAdded);
            add(lines, "data driven blocks new or changed", dataDrivenBlocks);
            add(lines, "creative stacks added", creativeAdded);
            add(lines, "removed", removed);
            return lines;
        }

        private static void add(List<String> lines, String what, List<String> values) {
            if (!values.isEmpty()) {
                lines.add(what + " (" + values.size() + "): " + String.join(", ", values.stream().map(v -> "`" + v + "`").toList()));
            }
        }
    }

    /**
     * @param baseDump the Script API dump without the toggles, or null
     * @param dump the Script API dump with them, or null
     */
    static Diff diff(CapturedData base, CapturedData with, BdsDump baseDump, BdsDump dump) {
        List<String> removed = new ArrayList<>();
        List<String> itemsAdded = new ArrayList<>();
        List<String> itemsChanged = new ArrayList<>();
        with.items().forEach((name, item) -> {
            NbtMap before = base.items().get(name);
            if (before == null) {
                itemsAdded.add(name);
            } else if (!withoutId(before).equals(withoutId(item))) {
                itemsChanged.add(name);
            }
        });
        base.items().keySet().stream().filter(name -> !with.items().containsKey(name)).forEach(name -> removed.add("item " + name));

        List<String> scriptItems = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        if (baseDump != null && dump != null) {
            dump.items().keySet().stream().filter(name -> !baseDump.items().containsKey(name)).forEach(scriptItems::add);
            baseDump.items().keySet().stream().filter(name -> !dump.items().containsKey(name)).forEach(name -> removed.add("Script API item " + name));
            dump.blockStates().keySet().stream().filter(name -> !baseDump.blockStates().containsKey(name)).forEach(blocks::add);
            baseDump.blockStates().keySet().stream().filter(name -> !dump.blockStates().containsKey(name)).forEach(name -> removed.add("Script API block " + name));
        }

        List<String> dataDriven = new ArrayList<>();
        with.blockProperties().forEach((name, properties) -> {
            if (!properties.equals(base.blockProperties().get(name))) {
                dataDriven.add(name);
            }
        });
        base.blockProperties().keySet().stream().filter(name -> !with.blockProperties().containsKey(name))
            .forEach(name -> removed.add("data driven block " + name));

        Set<String> creativeBefore = creativeKeys(base);
        Set<String> creativeAfter = creativeKeys(with);
        List<String> creativeAdded = creativeAfter.stream().filter(key -> !creativeBefore.contains(key)).toList();
        creativeBefore.stream().filter(key -> !creativeAfter.contains(key)).forEach(key -> removed.add("creative " + key));

        return new Diff(sorted(itemsAdded), sorted(itemsChanged), sorted(scriptItems), sorted(blocks), sorted(dataDriven),
            creativeAdded, sorted(removed));
    }

    /** Creative stacks as {@code id} (damage 0) or {@code id:damage}; block states and NBT are not distinguished. */
    static Set<String> creativeKeys(CapturedData data) {
        Set<String> keys = new TreeSet<>();
        data.creative().stacks().forEach(stack -> keys.add(key(stack)));
        return keys;
    }

    /** Identifiers of every item in the creative inventory. */
    static Set<String> creativeItems(CapturedData data) {
        Set<String> ids = new TreeSet<>();
        data.creative().stacks().forEach(stack -> ids.add(stack.identifier()));
        return ids;
    }

    private static String key(RawStack stack) {
        return stack.damage() == 0 ? stack.identifier() : stack.identifier() + ":" + stack.damage();
    }

    private static NbtMap withoutId(NbtMap item) {
        org.cloudburstmc.nbt.NbtMapBuilder builder = item.toBuilder();
        builder.remove("id");
        return builder.build();
    }

    private static List<String> sorted(List<String> values) {
        return List.copyOf(new TreeSet<>(values));
    }

    /** Content of several toggles together that the single toggle captures do not explain (interactions). */
    static List<String> unexplained(Diff all, Map<String, Diff> each) {
        Set<String> explained = new TreeSet<>();
        each.values().forEach(diff -> explained.addAll(tagged(diff)));
        return tagged(all).stream().filter(entry -> !explained.contains(entry)).toList();
    }

    private static List<String> tagged(Diff diff) {
        List<String> entries = new ArrayList<>();
        diff.itemsAdded().forEach(v -> entries.add("item added " + v));
        diff.itemsChanged().forEach(v -> entries.add("item changed " + v));
        diff.scriptItemsAdded().forEach(v -> entries.add("Script API item " + v));
        diff.blocksAdded().forEach(v -> entries.add("Script API block " + v));
        diff.dataDrivenBlocks().forEach(v -> entries.add("data driven block " + v));
        diff.creativeAdded().forEach(v -> entries.add("creative " + v));
        diff.removed().forEach(v -> entries.add("removed " + v));
        return entries;
    }
}
