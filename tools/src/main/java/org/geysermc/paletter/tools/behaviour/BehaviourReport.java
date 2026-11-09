package org.geysermc.paletter.tools.behaviour;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.behaviour.BehaviourKey;
import org.geysermc.paletter.behaviour.BlockBehaviour;
import org.geysermc.paletter.behaviour.BlockShape;
import org.geysermc.paletter.behaviour.ItemBehaviour;
import org.geysermc.paletter.internal.behaviour.BehaviourStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * {@code data/behaviour/REPORT.md}: which source decides each key in each version (or that the version inherits it),
 * where sources disagree, keys no source has, and source fields no key reads. Everything a human has to look at.
 */
final class BehaviourReport {
    private static final int EXAMPLES = 3;
    /** Keys no dump has: what serves them instead. */
    private static final Map<BehaviourKey<?>, String> NOT_DUMPED = Map.of(
        BlockBehaviour.CLIMBABLE, "no source: needs the native capture (BEHAVIOUR-DATA-PLAN section 6)",
        ItemBehaviour.USE_DURATION, "read at runtime from the component data (WIRE) of component based items; native items (bow, trident, potion) need the native capture",
        ItemBehaviour.USE_ANIMATION, "read at runtime from the component data (WIRE) of component based items; native items need the native capture");

    private final List<BehaviourImport.Bundled> bundled;
    private final List<BehaviourImport.Capture> captures;
    private final BehaviourStore store;
    private final List<String> log;

    BehaviourReport(List<BehaviourImport.Bundled> bundled, List<BehaviourImport.Capture> captures, BehaviourStore store, List<String> log) {
        this.bundled = bundled;
        this.captures = captures;
        this.store = store;
        this.log = log;
    }

    String render() {
        StringBuilder out = new StringBuilder();
        out.append("# Behaviour data report\n\nWritten by `behaviour import` (tools); do not edit. Format and lookup rules: `BehaviourStore`.\n\n");
        sources(out);
        coverage(out);
        disagreements(out);
        unmapped(out);
        out.append("## Log\n\n");
        log.forEach(line -> out.append("- ").append(line).append('\n'));
        return out.toString();
    }

    private void sources(StringBuilder out) {
        out.append("## Captures\n\nIn application order (per version the weakest source first; the strongest decides what it covers).\n\n");
        out.append("| Version | Source | Reference | Date | Identified by |\n|---|---|---|---|---|\n");
        for (BehaviourImport.Capture capture : captures) {
            out.append("| ").append(capture.version().name()).append(" | ").append(capture.source()).append(" | `")
                .append(capture.reference()).append("` | ").append(capture.date()).append(" | ").append(capture.match()).append(" |\n");
        }
        out.append('\n');
    }

    private void coverage(StringBuilder out) {
        out.append("## Coverage\n\nPer key and version: who decides the values of the version's block states / items. `inherited from V` means no capture of the version covers them and the value of version V applies.\n\n");
        out.append("| Key |");
        bundled.forEach(b -> out.append(' ').append(b.version().name()).append(" |"));
        out.append("\n|---|").append("---|".repeat(bundled.size())).append('\n');
        List<BehaviourKey<?>> keys = new ArrayList<>(BlockBehaviour.keys());
        keys.addAll(ItemBehaviour.keys());
        for (BehaviourKey<?> key : keys) {
            out.append("| `").append(key).append("` |");
            for (BehaviourImport.Bundled version : bundled) {
                Map<String, Integer> counts = new TreeMap<>();
                Collection<?> ids = key.owner() == BehaviourKey.Owner.BLOCK ? version.states().keySet() : version.items();
                for (Object id : ids) {
                    BehaviourStore.Resolved resolved = key.owner() == BehaviourKey.Owner.BLOCK
                        ? store.block(key, (Integer) id, version.version()) : store.item(key, (String) id, version.version());
                    String label = resolved == null ? "none"
                        : resolved.capture().version().equals(version.version().name()) ? resolved.capture().source().name()
                        : "inherited from " + resolved.capture().version() + " " + resolved.capture().source();
                    counts.merge(label, 1, Integer::sum);
                }
                out.append(' ');
                counts.forEach((label, count) -> out.append(label).append(' ').append(count).append("<br>"));
                out.append(" |");
            }
            out.append('\n');
        }
        out.append("\nKeys no dump has:\n\n");
        NOT_DUMPED.entrySet().stream().sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(Object::toString)))
            .forEach(e -> out.append("- `").append(e.getKey()).append("`: ").append(e.getValue()).append('\n'));
        out.append('\n');
    }

    private void disagreements(StringBuilder out) {
        out.append("## Disagreements\n\nEvery pair of captures of the same version, on the ids both cover. The stronger source is stored; check the other.\n\n");
        boolean any = false;
        for (int i = 0; i < captures.size(); i++) {
            for (int j = i + 1; j < captures.size(); j++) {
                BehaviourImport.Capture a = captures.get(i);
                BehaviourImport.Capture b = captures.get(j);
                if (!a.version().equals(b.version())) {
                    continue;
                }
                BehaviourImport.Bundled palette = bundled.stream().filter(v -> v.version().equals(a.version())).findFirst().orElseThrow();
                List<String> rows = new ArrayList<>();
                compare(a.blocks(), b.blocks(), palette.states().keySet(), id -> describe(palette.states().get(id), id), rows);
                compare(a.items(), b.items(), palette.items(), id -> id, rows);
                if (rows.isEmpty()) {
                    continue;
                }
                any = true;
                out.append("### ").append(a.version().name()).append(": ").append(a.source()).append(" vs ").append(b.source()).append("\n\n");
                out.append("| Key | Compared | Different | Examples (").append(a.source()).append(" / ").append(b.source()).append(") |\n|---|---|---|---|\n");
                rows.forEach(out::append);
                out.append('\n');
            }
        }
        if (!any) {
            out.append("None.\n\n");
        }
    }

    /**
     * One row per key that differs: how many ids, the first examples and, for blocks, which blocks (the name before
     * the first {@code [} of an example).
     */
    private static <I> void compare(Values<I> a, Values<I> b, Set<I> palette, Function<I, String> name, List<String> rows) {
        for (Map.Entry<BehaviourKey<?>, Map<I, Object>> entry : a.byKey.entrySet()) {
            Map<I, Object> other = b.byKey.get(entry.getKey());
            if (other == null) {
                continue;
            }
            int compared = 0;
            List<String> different = new ArrayList<>();
            Map<String, Integer> byName = new TreeMap<>();
            int differentCount = 0;
            for (Map.Entry<I, Object> value : entry.getValue().entrySet()) {
                I id = value.getKey();
                if (!palette.contains(id) || !other.containsKey(id)) {
                    continue;
                }
                compared++;
                if (!Objects.equals(value.getValue(), other.get(id))) {
                    differentCount++;
                    String described = name.apply(id);
                    byName.merge(described.contains("[") ? described.substring(0, described.indexOf('[')) : described, 1, Integer::sum);
                    if (different.size() < EXAMPLES) {
                        different.add(described + ": " + format(value.getValue()) + " / " + format(other.get(id)));
                    }
                }
            }
            if (differentCount > 0) {
                String names = byName.size() <= 1 ? "" : "<br>in " + byName.entrySet().stream().limit(40)
                    .map(e -> e.getKey() + " " + e.getValue()).collect(java.util.stream.Collectors.joining(", "))
                    + (byName.size() > 40 ? ", ... (" + byName.size() + " in all)" : "");
                rows.add("| `" + entry.getKey() + "` | " + compared + " | " + differentCount + " | "
                    + String.join("<br>", different) + names + " |\n");
            }
        }
    }

    private void unmapped(StringBuilder out) {
        Map<String, Integer> fields = new TreeMap<>();
        for (BehaviourImport.Capture capture : captures) {
            capture.blocks().unmappedFields.forEach((field, count) -> fields.merge(capture.source() + " block " + field, count, Integer::sum));
            capture.items().unmappedFields.forEach((field, count) -> fields.merge(capture.source() + " item " + field, count, Integer::sum));
        }
        out.append("## Unmapped source fields\n\nFields a source has that no key reads (add a key and a line in `EndstoneDump`, or ignore them there).\n\n");
        if (fields.isEmpty()) {
            out.append("None.\n\n");
        }
        fields.forEach((field, count) -> out.append("- ").append(field).append(" (").append(count).append(")\n"));
        out.append('\n');
    }

    private static String describe(NbtMap state, int hash) {
        StringBuilder out = new StringBuilder(state.getString("name").replace("minecraft:", "")).append('[');
        new TreeMap<>(state.getCompound("states")).forEach((key, value) -> out.append(out.charAt(out.length() - 1) == '[' ? "" : ",")
            .append(key.replace("minecraft:", "")).append('=').append(value instanceof Byte b ? b != 0 : value));
        return out.append("] (").append(Integer.toUnsignedString(hash)).append(')').toString();
    }

    private static String format(Object value) {
        if (value instanceof BlockShape shape) {
            return Arrays.toString(shape.coordinates());
        }
        return String.valueOf(value);
    }
}
