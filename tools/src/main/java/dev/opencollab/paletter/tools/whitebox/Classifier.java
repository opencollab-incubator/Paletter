package dev.opencollab.paletter.tools.whitebox;

import dev.opencollab.paletter.tools.whitebox.ComponentDiscovery.Component;
import dev.opencollab.paletter.tools.whitebox.ComponentDiscovery.Layout;
import dev.opencollab.paletter.tools.whitebox.Listing.Event;
import dev.opencollab.paletter.tools.whitebox.Listing.Kind;
import dev.opencollab.paletter.tools.whitebox.Scan.Facts;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tells how each component is written: whether it is sent, by the reflection writer, by hand-written code or both, the
 * functions that put tags, the extra writers some classes have, and the keys of a reflection schema.
 */
final class Classifier {
    private final Scan scan;

    Classifier(Scan scan) {
        this.scan = scan;
    }

    /**
     * Fills in each component's network flag, writer, reader and kind.
     *
     * @return the table of the reflection writer class: the one that the writers without keys of their own share
     */
    long classify(List<Component> blocks, Layout blockLayout, List<Component> items, Layout itemLayout) {
        Map<Component, Layout> all = new LinkedHashMap<>();
        blocks.forEach(component -> all.put(component, blockLayout));
        items.forEach(component -> all.put(component, itemLayout));
        Map<Long, Integer> withoutKeys = new HashMap<>();
        Map<Long, Integer> withKeys = new HashMap<>();
        for (Map.Entry<Component, Layout> entry : all.entrySet()) {
            Component component = entry.getKey();
            if (component.table == null) {
                continue;
            }
            long[] slots = component.table.slots();
            component.network = scan.constantBool(slots[entry.getValue().networkSlot()]);
            component.writer = scan.facts(slots[entry.getValue().writerSlot()]);
            component.reader = scan.facts(slots[entry.getValue().readerSlot()]);
            if (Boolean.TRUE.equals(component.network)) {
                Set<Long> tables = new HashSet<>(component.writer.tables);
                tables.addAll(component.writer.nestedTables);
                Map<Long, Integer> counts = component.writer.keys.isEmpty() ? withoutKeys : withKeys;
                tables.forEach(table -> counts.merge(table, 1, Integer::sum));
            }
        }
        long schemaWriter = 0;
        int most = 0;
        for (Map.Entry<Long, Integer> entry : withoutKeys.entrySet()) {
            if (entry.getValue() > most && withKeys.getOrDefault(entry.getKey(), 0) * 4 < entry.getValue()) {
                most = entry.getValue();
                schemaWriter = entry.getKey();
            }
        }
        if (most < 8) {
            scan.notes.add("no shared reflection writer found: every writer is reported as hand-written");
            schemaWriter = 0;
        }

        // The functions that put a tag into a compound: several hand-written writers hand a key to them, and no reader
        // calls them at all
        Map<Long, Integer> writerCalls = new HashMap<>();
        Set<Long> readerCalls = new HashSet<>();
        Set<Long> writers = new HashSet<>();
        for (Component component : all.keySet()) {
            if (component.table == null || !Boolean.TRUE.equals(component.network)) {
                continue;
            }
            writers.add(component.table.slots()[all.get(component).writerSlot()]);
            readerCalls.addAll(component.reader.calls);
            if (!component.writer.keys.isEmpty()) {
                component.writer.keyCalls.forEach(call -> writerCalls.merge(call, 1, Integer::sum));
            }
        }
        Set<Long> helpers = new HashSet<>();
        for (Map.Entry<Long, Integer> entry : writerCalls.entrySet()) {
            if (entry.getValue() >= 3 && !readerCalls.contains(entry.getKey())) {
                helpers.add(entry.getKey());
            }
        }
        scan.putHelpers = helpers;
        if (helpers.isEmpty()) {
            scan.notes.add("no tag-writing helper found: second writers and envelope writers cannot be told from readers");
        }

        for (Map.Entry<Component, Layout> entry : all.entrySet()) {
            Component component = entry.getKey();
            if (component.table == null) {
                continue;
            }
            Layout layout = entry.getValue();
            // The slots after the reader that only this class fills: hooks, and for some a second writer and reader
            Map<Long, Integer> users = new HashMap<>();
            for (Component other : all.keySet()) {
                if (other.table != null && all.get(other) == layout) {
                    for (long slot : new HashSet<>(Arrays.stream(other.table.slots()).boxed().toList())) {
                        users.merge(slot, 1, Integer::sum);
                    }
                }
            }
            long[] slots = component.table.slots();
            for (int i = layout.readerSlot() + 1; i < slots.length; i++) {
                if (users.get(slots[i]) == 1 && scan.size(slots[i]) > 16) {
                    component.overrides.add(i);
                    Facts extra = scan.facts(slots[i]);
                    if (scan.puts(extra)) {
                        component.extraKeys.addAll(extra.keys);
                    }
                }
            }
            // The reflection writer is used by the writer itself or by a function it calls, other writers aside
            boolean reflection = schemaWriter != 0 && component.writer.tables.contains(schemaWriter);
            for (long call : component.writer.calls) {
                long size = scan.size(call);
                if (schemaWriter != 0 && !writers.contains(call) && size != 0 && size <= Scan.HELPER_SIZE && scan.own(call).tables.contains(schemaWriter)) {
                    reflection = true;
                }
            }
            boolean keys = !component.writer.keys.isEmpty() || !component.extraKeys.isEmpty();
            if (!Boolean.TRUE.equals(component.network)) {
                component.kind = component.network == null ? "unknown" : "not-sent";
            } else if (reflection) {
                component.kind = keys ? "mixed" : "reflection";
            } else {
                component.kind = "hand-written";
            }
        }
        return schemaWriter;
    }

    /**
     * The keys an item component's reflection schema registers, in registration order. They are listed by the function
     * that uses a piece of data that only this component's writer uses, and mentions this component's name and no other.
     * Components whose schema is registered together with others in one large function get no list.
     */
    void itemSchemas(List<Component> items) {
        Map<Long, Integer> users = new HashMap<>();
        for (Component item : items) {
            if (item.writer != null) {
                Set<Long> data = new HashSet<>(item.writer.data);
                data.addAll(item.writer.nestedData);
                data.forEach(address -> users.merge(address, 1, Integer::sum));
            }
        }
        List<Long> unique = users.entrySet().stream().filter(entry -> entry.getValue() == 1).map(Map.Entry::getKey).toList();
        Map<Long, Set<Long>> functions = scan.functionsReferring(unique, 1);
        for (Component item : items) {
            if (item.name == null || item.writer == null || !Boolean.TRUE.equals(item.network)) {
                continue;
            }
            Set<Long> own = new HashSet<>(item.writer.data);
            own.addAll(item.writer.nestedData);
            long best = 0;
            for (Map.Entry<Long, Set<Long>> function : functions.entrySet()) {
                long size = scan.size(function.getKey());
                if (function.getValue().stream().noneMatch(own::contains) || size > 0x8000 || best != 0 && size >= scan.size(best)) {
                    continue;
                }
                Set<String> names = new HashSet<>();
                for (Event event : scan.listing(function.getKey())) {
                    String name = scan.componentName(event);
                    if (name == null && event.kind() == Kind.DATA) {
                        name = scan.constantName(event.value());
                    }
                    if (name != null) {
                        names.add(name);
                    }
                }
                if (names.equals(Set.of(item.name))) {
                    best = function.getKey();
                }
            }
            if (best != 0) {
                item.schema = scan.own(best).keys.stream().filter(key -> !Scan.COMPONENT_NAME.matcher(key).matches()).toList();
            }
        }
    }
}
