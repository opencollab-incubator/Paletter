package dev.opencollab.paletter.tools.whitebox;

import dev.opencollab.paletter.tools.whitebox.Listing.Event;
import dev.opencollab.paletter.tools.whitebox.Listing.Kind;
import dev.opencollab.paletter.tools.whitebox.Scan.Facts;
import dev.opencollab.paletter.tools.whitebox.Scan.Table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Finds the components of a binary and the virtual functions of their classes: the block components, which return
 * their name from a function that only returns a static string, and the item components, which one function names and
 * constructs in turn. {@link #BLOCK_ANCHOR} and {@link #ITEM_ANCHOR} anchor the search; if one is not found the
 * discovery fails rather than guess.
 */
final class ComponentDiscovery {
    /** A block component every build has; its name getter locates the family and its table calibrates the slots. */
    static final String BLOCK_ANCHOR = "minecraft:geometry";
    /** An item component every build has; it locates the function that registers the item components. */
    static final String ITEM_ANCHOR = "minecraft:max_stack_size";

    /** Where a family keeps the functions the discovery reads; the writer and reader follow the network slot. */
    record Layout(int nameSlot, int networkSlot) {
        int writerSlot() {
            return networkSlot + 1;
        }

        int readerSlot() {
            return networkSlot + 2;
        }
    }

    static final class Component {
        /** The component name, or null for a class of the family that no name was found for. */
        String name;
        Table table;
        Boolean network;
        Facts writer;
        Facts reader;
        final Set<String> extraKeys = new TreeSet<>();
        final List<Integer> overrides = new ArrayList<>();
        List<String> schema;
        String kind;
    }

    private final Scan scan;
    private final Image image;

    ComponentDiscovery(Scan scan) {
        this.scan = scan;
        this.image = scan.image;
    }

    // ----- Block components --------------------------------------------------------------------------------------------

    Layout blockFamily(List<Component> components) {
        for (long[] getter : image.staticGetters()) {
            scan.getters.put(getter[0], getter[1]);
        }
        Map<Long, List<Long>> slotsOfGetter = new HashMap<>();
        image.pointers((at, to) -> {
            if (scan.getters.containsKey(to)) {
                slotsOfGetter.computeIfAbsent(to, g -> new ArrayList<>()).add(at);
            }
        });

        // The anchor: the getter that returns the static named BLOCK_ANCHOR
        Set<Long> anchorStatics = new HashSet<>();
        for (long getter : slotsOfGetter.keySet()) {
            if (BLOCK_ANCHOR.equals(image.constantString(scan.getters.get(getter)))) {
                anchorStatics.add(scan.getters.get(getter));
            }
        }
        if (anchorStatics.isEmpty()) {
            for (long function : scan.functionsReferring(image.literals(BLOCK_ANCHOR), 1).keySet()) {
                scan.staticNames(scan.listing(function)).forEach((address, name) -> {
                    if (name.equals(BLOCK_ANCHOR)) {
                        anchorStatics.add(address);
                    }
                });
            }
        }
        Layout layout = null;
        for (Map.Entry<Long, List<Long>> entry : slotsOfGetter.entrySet()) {
            long target = scan.getters.get(entry.getKey());
            if (anchorStatics.stream().noneMatch(address -> address >= target && address < target + 32)) {
                continue;
            }
            for (long slot : entry.getValue()) {
                Table table = scan.tableAt(slot);
                int nameSlot = (int) ((slot - table.address()) / 8);
                int networkSlot = scan.networkSlot(table, nameSlot);
                if (networkSlot >= 0) {
                    layout = new Layout(nameSlot, networkSlot);
                }
            }
        }
        if (layout == null) {
            throw new IllegalStateException("No block component named " + BLOCK_ANCHOR + " with a network writer: the block hook needs maintenance");
        }

        // The family: every table with a name getter and a constant in the same slots
        Map<Long, Table> family = new TreeMap<>();
        for (Map.Entry<Long, List<Long>> entry : slotsOfGetter.entrySet()) {
            for (long slot : entry.getValue()) {
                Table table = scan.tableAt(slot);
                if (slot - table.address() == 8L * layout.nameSlot() && table.slots().length > layout.readerSlot()
                    && scan.constantBool(table.slots()[layout.networkSlot()]) != null) {
                    family.put(table.address(), table);
                }
            }
        }
        List<Long> statics = new ArrayList<>();
        for (Table table : family.values()) {
            statics.add(scan.getters.get(table.slots()[layout.nameSlot()]));
        }
        Map<Long, String> names = scan.stringStatics(statics);
        int unnamed = 0;
        for (Table table : family.values()) {
            String name = names.get(scan.getters.get(table.slots()[layout.nameSlot()]));
            if (name == null) {
                unnamed++;
                continue;
            }
            Component component = new Component();
            component.name = name;
            component.table = table;
            components.add(component);
        }
        if (unnamed > 0) {
            scan.notes.add(unnamed + " tables with the layout of a block component have no component name (base classes and look-alikes)");
        }
        return layout;
    }

    // ----- Item components ---------------------------------------------------------------------------------------------

    /**
     * One thing the registration function mentions: a component name, or the table of a class. {@code constant} marks a
     * name read from a static that needs no setting up.
     */
    private record Mention(String name, boolean constant, Table table) {
    }

    /**
     * @return the component names and tables a function mentions, in order. A name is a literal, a short string built in
     * place, or a static that holds one; a table is referred to directly or by a small function that constructs the object
     */
    private List<Mention> mentions(long function) {
        List<Event> events = scan.listing(function);
        Map<Long, String> statics = scan.staticNames(events);
        List<Mention> mentions = new ArrayList<>();
        for (Event event : events) {
            String name = scan.componentName(event);
            boolean constant = false;
            if (name == null && event.kind() == Kind.DATA) {
                name = statics.get(event.value());
                if (name == null) {
                    name = scan.constantName(event.value());
                    constant = name != null;
                }
            }
            if (name != null) {
                mentions.add(new Mention(name, constant, null));
            } else if (event.kind() == Kind.DATA) {
                if (scan.isTableStart(event.value())) {
                    mentions.add(new Mention(null, false, scan.tableAt(event.value())));
                }
            } else if (event.kind() == Kind.CALL && scan.size(event.value()) <= Scan.FACTORY_SIZE) {
                for (Event inner : scan.listing(event.value())) {
                    if (inner.kind() == Kind.DATA && scan.isTableStart(inner.value())) {
                        mentions.add(new Mention(null, false, scan.tableAt(inner.value())));
                    }
                }
            }
        }
        return mentions;
    }

    /** @return whether a table is of the family: a constant in the network slot and base class functions of the anchor */
    private boolean inFamily(Table table, Layout layout, Table anchor) {
        if (table.slots().length <= layout.readerSlot() || scan.constantBool(table.slots()[layout.networkSlot()]) == null) {
            return false;
        }
        // Inherited functions: the same function in the same slot as the anchor, the three network slots aside
        int shared = 0;
        for (int i = 0; i < Math.min(table.slots().length, anchor.slots().length); i++) {
            if (i < layout.networkSlot() || i > layout.readerSlot()) {
                shared += table.slots()[i] == anchor.slots()[i] ? 1 : 0;
            }
        }
        return shared >= 2;
    }

    /**
     * Pairs names with classes. The registration function handles one component after the other, and the order within
     * one follows from how the name is held. A name that has to be built or set up first (a literal, a string built in
     * place, a static with a guard) comes before the object is constructed: the first class after it is its class. A
     * name in a static that needs no setting up is only read when the finished object is registered: the first class
     * since the name before it is its class. (Checked against the class names of the builds that have them.)
     */
    private Map<String, Table> pair(List<Mention> mentions, Layout layout, Table anchor) {
        Map<String, Table> paired = new LinkedHashMap<>();
        Set<Long> used = new HashSet<>();
        String current = null;
        boolean takesNext = false;
        Table waiting = null;
        for (Mention mention : mentions) {
            if (mention.name() != null) {
                paired.putIfAbsent(mention.name(), null);
                if (mention.constant() && waiting != null && paired.get(mention.name()) == null) {
                    paired.put(mention.name(), waiting);
                    used.add(waiting.address());
                }
                waiting = null;
                current = mention.name();
                takesNext = !mention.constant();
            } else if (inFamily(mention.table(), layout, anchor) && !used.contains(mention.table().address())) {
                if (takesNext && paired.get(current) == null) {
                    paired.put(current, mention.table());
                    used.add(mention.table().address());
                } else if (waiting == null) {
                    waiting = mention.table();
                }
            }
        }
        return paired;
    }

    Layout itemFamily(List<Component> components) {
        Map<String, Table> best = Map.of();
        Layout layout = null;
        Table anchor = null;
        for (long function : scan.functionsReferring(image.literals(ITEM_ANCHOR), 1).keySet()) {
            List<Mention> mentions = mentions(function);
            // The anchor's class: the first table after the anchor's name that has a network writer
            Table found = null;
            boolean named = false;
            for (Mention mention : mentions) {
                if (mention.name() != null) {
                    named = mention.name().equals(ITEM_ANCHOR);
                } else if (named && found == null && scan.networkSlot(mention.table(), -1) >= 0) {
                    found = mention.table();
                }
            }
            if (found == null) {
                continue;
            }
            Layout foundLayout = new Layout(-1, scan.networkSlot(found, -1));
            Map<String, Table> paired = pair(mentions, foundLayout, found);
            if (paired.size() > best.size()) {
                best = paired;
                layout = foundLayout;
                anchor = found;
            }
        }
        if (layout == null) {
            throw new IllegalStateException("No function registers " + ITEM_ANCHOR + " with a class that has a network writer: the item hook needs maintenance");
        }
        // Where the registration function constructs the objects elsewhere (the Windows build), the few classes it
        // does mention would be paired with the wrong names: better none than those
        long paired = best.values().stream().filter(table -> table != null).count();
        boolean reliable = paired * 2 >= best.size();
        if (!reliable) {
            scan.notes.add("the item component classes could not be paired with their names: they are listed without names");
        }
        for (Map.Entry<String, Table> entry : best.entrySet()) {
            Component component = new Component();
            component.name = entry.getKey();
            component.table = reliable ? entry.getValue() : null;
            if (component.table == null && reliable) {
                scan.notes.add("item component " + component.name + " is registered, but its class was not found");
            }
            components.add(component);
        }
        unregistered(components, layout, anchor);
        return layout;
    }

    /** Adds the classes of the family that the registration function does not name, without a name. */
    private void unregistered(List<Component> components, Layout layout, Table anchor) {
        Set<Long> known = new HashSet<>();
        components.forEach(component -> known.add(component.table == null ? 0 : component.table.address()));
        Set<Long> starts = new TreeSet<>();
        image.pointers((at, to) -> {
            if (image.isCode(to) && scan.isTableStart(at)) {
                starts.add(at);
            }
        });
        for (long start : starts) {
            Table table = known.contains(start) ? null : scan.tableAt(start);
            if (table == null || !inFamily(table, layout, anchor)) {
                continue;
            }
            Component component = new Component();
            component.table = table;
            components.add(component);
        }
    }
}
