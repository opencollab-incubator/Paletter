package org.geysermc.paletter.tools.whitebox;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.Versions;
import org.geysermc.paletter.tools.whitebox.Listing.Event;
import org.geysermc.paletter.tools.whitebox.Listing.Kind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * {@code whitebox}: what the code of one BDS build says about how item and block components reach the client, read from
 * the public server binary without running it. Writes {@code data/bds/<version>/whitebox.json}: every registered
 * component, whether it is sent over the network, whether its network data is written by the reflection ("cereal")
 * writer, by hand-written code or both, the NBT keys the hand-written code writes and reads, and the keys of the item
 * and block envelopes.
 * <p>
 * The discovery does not depend on symbols or type names. It relies on how the code is built:
 * <ul>
 *     <li>A component is a class with virtual functions, so the binary holds a table of function pointers per class.
 *     All component classes of a family share the table's layout: one slot answers "is this sent to the client?" with
 *     a constant, the next writes the network NBT, the one after reads it.</li>
 *     <li>Block components return their JSON name from a virtual function that only returns a static string. Item
 *     components are named where they are registered: one function that mentions each name and then the class.</li>
 *     <li>Hand-written writers mention their NBT keys; the reflection writer mentions none and uses one shared
 *     writer class instead.</li>
 * </ul>
 * Two well-known components ({@link #BLOCK_ANCHOR}, {@link #ITEM_ANCHOR}) anchor the search; if one is not found the
 * discovery fails rather than guess. Type names are added as {@code class} when the build has them (Linux releases),
 * as a label only. See docs/data-pipeline.md, "White-box discovery".
 */
public final class Whitebox {
    public static final String FILE = "whitebox.json";

    /** A block component every build has; its name getter locates the family and its table calibrates the slots. */
    static final String BLOCK_ANCHOR = "minecraft:geometry";
    /** An item component every build has; it locates the function that registers the item components. */
    static final String ITEM_ANCHOR = "minecraft:max_stack_size";
    /** A key only the item envelope writes, and one only the block envelope writes. */
    static final String ITEM_ENVELOPE_KEY = "item_properties";
    static final String BLOCK_ENVELOPE_KEY = "vanilla_block_data";
    /** A key every block trait writes. */
    static final String TRAIT_KEY = "enabled_states";

    private static final Pattern COMPONENT_NAME = Pattern.compile("minecraft:[a-z0-9_.]+");
    private static final Pattern KEY = Pattern.compile("(?!__)[A-Za-z_][A-Za-z0-9_:.]{0,63}");
    /** Literals of the standard library's own error paths, which are not keys. */
    private static final Set<String> NOT_KEYS = Set.of("vector", "basic_string", "deque", "string", "map", "list", "bad_cast",
        "invalid", "true", "false", "null", "nan", "inf");
    private static final String[] TAG_TYPES = {"end", "byte", "short", "int", "long", "float", "double", "byte_array", "string",
        "list", "compound", "int_array"};
    /** Functions called from a writer are looked into when they are at most this big. */
    private static final int HELPER_SIZE = 0x3000;
    /** A function that only constructs one object is at most this big. */
    private static final int FACTORY_SIZE = 0x1000;

    /** A virtual table: its address, the functions in its slots, and the class name if the build stores one. */
    record Table(long address, long[] slots, String className) {
    }

    /** Where a family keeps the functions the discovery reads; the writer and reader follow the network slot. */
    record Layout(int nameSlot, int networkSlot) {
        int writerSlot() {
            return networkSlot + 1;
        }

        int readerSlot() {
            return networkSlot + 2;
        }
    }

    /** What a function mentions. */
    static final class Facts {
        final Set<String> keys = new LinkedHashSet<>();
        final Map<String, String> types = new LinkedHashMap<>();
        final Set<Long> tables = new LinkedHashSet<>();
        final Set<Long> data = new LinkedHashSet<>();
        final Set<Long> calls = new LinkedHashSet<>();
        /** The calls that come first after a key was mentioned: what the key is handed to. */
        final Set<Long> keyCalls = new LinkedHashSet<>();
        final Set<Long> virtualCalls = new LinkedHashSet<>();
        final Set<String> nestedKeys = new LinkedHashSet<>();
        final Set<Long> nestedTables = new LinkedHashSet<>();
        final Set<Long> nestedData = new LinkedHashSet<>();
        String code = "";
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

    private final Image image;
    private final Map<Long, Facts> factsCache = new HashMap<>();
    private final Map<Long, List<Event>> listingCache = new HashMap<>();
    /** Windows: the addresses of virtual tables, which follow each other without a gap. */
    private long[] tableStarts;
    private final List<String> notes = new ArrayList<>();
    /** Functions that only return the address of a static, and that static. */
    private final Map<Long, Long> getters = new HashMap<>();
    /** The functions that hand-written writers call to put a tag and readers do not. */
    private Set<Long> putHelpers = Set.of();

    private Whitebox(Image image) {
        this.image = image;
    }

    // ----- Entry points ----------------------------------------------------------------------------------------------

    /**
     * @param binary a BDS zip (the server binary is read from it, nothing is extracted) or the server binary itself
     * @return the facts of the build
     */
    public static JsonObject discover(Path binary, String version) throws IOException {
        return discover(Image.load(binary), version);
    }

    static JsonObject discover(Image image, String version) {
        return new Whitebox(image).discover(version);
    }

    /** @return the file name of the facts of a platform: the Linux build is the reference, Windows a cross-check */
    public static String file(String platform) {
        return platform.equals("linux") ? FILE : "whitebox." + platform + ".json";
    }

    /** The newest {@code file} in {@code data/bds} of a build older than {@code version}, or null. */
    public static Path previous(Path bds, String version, String file) throws IOException {
        Path result = null;
        if (!Files.isDirectory(bds)) {
            return null;
        }
        try (var builds = Files.list(bds)) {
            for (Path build : builds.sorted((a, b) -> Versions.compare(a.getFileName().toString(), b.getFileName().toString())).toList()) {
                if (Versions.compare(build.getFileName().toString(), version) < 0 && Files.isRegularFile(build.resolve(file))) {
                    result = build.resolve(file);
                }
            }
        }
        return result;
    }

    public static String json(JsonObject facts) {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(facts) + "\n";
    }

    public static JsonObject parse(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private JsonObject discover(String version) {
        if (image.windows) {
            tableStarts = image.codePointerTables();
        }
        List<Component> blocks = new ArrayList<>();
        Layout blockLayout = blockFamily(blocks);
        List<Component> items = new ArrayList<>();
        Layout itemLayout = itemFamily(items);

        long schemaWriter = classify(blocks, blockLayout, items, itemLayout);
        itemSchemas(items);

        JsonObject json = new JsonObject();
        json.addProperty("_generated", "Generated by `whitebox` (tools, Whitebox) from the " + (image.windows ? "Windows" : "Linux")
            + " server binary of BDS " + version + ", which is not run. Do not edit: run the command again.");
        json.addProperty("bdsVersion", version);
        json.addProperty("platform", image.windows ? "windows" : "linux");
        JsonObject layout = new JsonObject();
        layout.add("item", layout(itemLayout));
        layout.add("block", layout(blockLayout));
        String writerClass = schemaWriter == 0 ? null : image.className(schemaWriter);
        if (writerClass != null) {
            layout.addProperty("reflectionWriter", writerClass);
        }
        json.add("layout", layout);
        json.add("itemComponents", components(items));
        json.add("itemClassesWithoutName", withoutName(items));
        json.add("blockComponents", components(blocks));
        json.add("traits", traits(schemaWriter));
        JsonObject envelopes = new JsonObject();
        envelopes.add("item", envelope(ITEM_ENVELOPE_KEY));
        envelopes.add("block", envelope(BLOCK_ENVELOPE_KEY));
        json.add("envelopes", envelopes);
        JsonArray noteArray = new JsonArray();
        notes.forEach(noteArray::add);
        json.add("notes", noteArray);
        return json;
    }

    private static JsonObject layout(Layout layout) {
        JsonObject json = new JsonObject();
        if (layout.nameSlot() >= 0) {
            json.addProperty("nameSlot", layout.nameSlot());
        }
        json.addProperty("networkSlot", layout.networkSlot());
        json.addProperty("writerSlot", layout.writerSlot());
        json.addProperty("readerSlot", layout.readerSlot());
        return json;
    }

    // ----- Tables -----------------------------------------------------------------------------------------------------

    private boolean isTableStart(long va) {
        if (!image.isCodePointer(va) || image.isCode(va)) {
            return false;
        }
        return image.windows ? Arrays.binarySearch(tableStarts, va) >= 0 : !image.isCodePointer(va - 8);
    }

    private Table tableAt(long start) {
        long[] bounds = image.table(start, tableStarts);
        long[] slots = new long[(int) bounds[1]];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = image.pointer(bounds[0] + 8L * i);
        }
        return new Table(bounds[0], slots, image.className(bounds[0]));
    }

    /** @return the value a function returns without doing anything else, or null: {@code mov al, 1; ret} and the like */
    Boolean constantBool(long function) {
        byte[] code = image.read(function, 3);
        if (code == null || (code[2] & 0xff) != 0xc3) {
            return null;
        }
        int first = code[0] & 0xff;
        int second = code[1] & 0xff;
        if (first == 0xb0 && second <= 1) {
            return second == 1;
        }
        return (first == 0x30 || first == 0x31 || first == 0x32 || first == 0x33) && second == 0xc0 ? Boolean.FALSE : null;
    }

    private long size(long function) {
        long end = image.functionEnd(function);
        return end == 0 ? 0 : end - function;
    }

    /**
     * Finds the network slots in the table of a component that is known to be sent: the first slot that returns a
     * constant and is followed by two functions with real code (the writer and the reader).
     */
    private int networkSlot(Table table, int after) {
        for (int i = after + 1; i + 2 < table.slots().length; i++) {
            if (Boolean.TRUE.equals(constantBool(table.slots()[i])) && size(table.slots()[i + 1]) >= 0x40 && size(table.slots()[i + 2]) >= 0x40) {
                return i;
            }
        }
        return -1;
    }

    // ----- Names of statics -------------------------------------------------------------------------------------------

    private List<Event> listing(long function) {
        return listingCache.computeIfAbsent(function, start -> Listing.of(image, start));
    }

    /** @return the component name an instruction mentions: as a string literal, or as a short string built in place */
    private String componentName(Event event) {
        String text = event.kind() == Kind.DATA ? image.cString(event.value()) : event.kind() == Kind.TEXT ? event.text() : null;
        return text != null && COMPONENT_NAME.matcher(text).matches() ? text : null;
    }

    /**
     * Statics that code sets up from a component name literal: between two calls, a function that mentions exactly one
     * name literal and writable statics is initialising those statics with that name.
     *
     * @return static address to name
     */
    private Map<Long, String> staticNames(List<Event> events) {
        Map<Long, String> names = new HashMap<>();
        Set<String> literals = new HashSet<>();
        Set<Long> statics = new LinkedHashSet<>();
        Set<Long> before = Set.of();
        for (int i = 0; i <= events.size(); i++) {
            Event event = i < events.size() ? events.get(i) : null;
            if (event == null || event.kind() == Kind.CALL || event.kind() == Kind.VIRTUAL_CALL) {
                if (literals.size() == 1) {
                    String name = literals.iterator().next();
                    statics.forEach(address -> names.putIfAbsent(address, name));
                    // A static that is set up on first use has a guard, which the code takes just before: the guard
                    // stands for the name wherever the code checks it
                    if (before.size() == 1) {
                        names.putIfAbsent(before.iterator().next(), name);
                    }
                }
                before = literals.isEmpty() ? Set.copyOf(statics) : Set.of();
                literals.clear();
                statics.clear();
            } else if (componentName(event) != null) {
                literals.add(componentName(event));
            } else if (event.kind() == Kind.DATA && image.isWritable(event.value())) {
                statics.add(event.value());
            }
        }
        return names;
    }

    /** The functions that refer to any of the addresses (or up to {@code span} bytes after one). */
    private Map<Long, Set<Long>> functionsReferring(Collection<Long> addresses, int span) {
        long[] targets = addresses.stream().mapToLong(Long::longValue).sorted().distinct().toArray();
        Map<Long, Set<Long>> functions = new TreeMap<>();
        for (Image.Reference reference : image.references(targets, span)) {
            long function = image.functionOf(reference.site());
            if (function != 0) {
                functions.computeIfAbsent(function, f -> new TreeSet<>()).add(reference.target());
            }
        }
        return functions;
    }

    /** @return the text of the static strings at the addresses, as far as they can be resolved */
    private Map<Long, String> stringStatics(Collection<Long> addresses) {
        Map<Long, String> names = new HashMap<>();
        List<Long> open = new ArrayList<>();
        for (long address : addresses) {
            String constant = image.constantString(address);
            if (constant != null) {
                names.put(address, constant);
            } else {
                open.add(address);
            }
        }
        if (open.isEmpty()) {
            return names;
        }
        // Set up at startup: find the code that does it
        Map<Long, String> initialised = new TreeMap<>();
        for (long function : functionsReferring(open, 32).keySet()) {
            initialised.putAll(staticNames(listing(function)));
        }
        for (long address : open) {
            for (Map.Entry<Long, String> entry : ((TreeMap<Long, String>) initialised).subMap(address, address + 32).entrySet()) {
                names.putIfAbsent(address, entry.getValue());
            }
        }
        return names;
    }

    // ----- Block components --------------------------------------------------------------------------------------------

    private Layout blockFamily(List<Component> components) {
        for (long[] getter : image.staticGetters()) {
            getters.put(getter[0], getter[1]);
        }
        Map<Long, List<Long>> slotsOfGetter = new HashMap<>();
        image.pointers((at, to) -> {
            if (getters.containsKey(to)) {
                slotsOfGetter.computeIfAbsent(to, g -> new ArrayList<>()).add(at);
            }
        });

        // The anchor: the getter that returns the static named BLOCK_ANCHOR
        Set<Long> anchorStatics = new HashSet<>();
        for (long getter : slotsOfGetter.keySet()) {
            if (BLOCK_ANCHOR.equals(image.constantString(getters.get(getter)))) {
                anchorStatics.add(getters.get(getter));
            }
        }
        if (anchorStatics.isEmpty()) {
            for (long function : functionsReferring(image.literals(BLOCK_ANCHOR), 1).keySet()) {
                staticNames(listing(function)).forEach((address, name) -> {
                    if (name.equals(BLOCK_ANCHOR)) {
                        anchorStatics.add(address);
                    }
                });
            }
        }
        Layout layout = null;
        for (Map.Entry<Long, List<Long>> entry : slotsOfGetter.entrySet()) {
            long target = getters.get(entry.getKey());
            if (anchorStatics.stream().noneMatch(address -> address >= target && address < target + 32)) {
                continue;
            }
            for (long slot : entry.getValue()) {
                Table table = tableAt(slot);
                int nameSlot = (int) ((slot - table.address()) / 8);
                int networkSlot = networkSlot(table, nameSlot);
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
                Table table = tableAt(slot);
                if (slot - table.address() == 8L * layout.nameSlot() && table.slots().length > layout.readerSlot()
                    && constantBool(table.slots()[layout.networkSlot()]) != null) {
                    family.put(table.address(), table);
                }
            }
        }
        List<Long> statics = new ArrayList<>();
        for (Table table : family.values()) {
            statics.add(getters.get(table.slots()[layout.nameSlot()]));
        }
        Map<Long, String> names = stringStatics(statics);
        int unnamed = 0;
        for (Table table : family.values()) {
            String name = names.get(getters.get(table.slots()[layout.nameSlot()]));
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
            notes.add(unnamed + " tables with the layout of a block component have no component name (base classes and look-alikes)");
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
        List<Event> events = listing(function);
        Map<Long, String> statics = staticNames(events);
        List<Mention> mentions = new ArrayList<>();
        for (Event event : events) {
            String name = componentName(event);
            boolean constant = false;
            if (name == null && event.kind() == Kind.DATA) {
                name = statics.get(event.value());
                if (name == null) {
                    name = constantName(event.value());
                    constant = name != null;
                }
            }
            if (name != null) {
                mentions.add(new Mention(name, constant, null));
            } else if (event.kind() == Kind.DATA) {
                if (isTableStart(event.value())) {
                    mentions.add(new Mention(null, false, tableAt(event.value())));
                }
            } else if (event.kind() == Kind.CALL && size(event.value()) <= FACTORY_SIZE) {
                for (Event inner : listing(event.value())) {
                    if (inner.kind() == Kind.DATA && isTableStart(inner.value())) {
                        mentions.add(new Mention(null, false, tableAt(inner.value())));
                    }
                }
            }
        }
        return mentions;
    }

    /**
     * @return the component name in a static that the file itself initialises. The static is a string, a pointer to a
     * literal followed by its length, or a hashed string, which keeps either of the two after an 8-byte hash
     */
    private String constantName(long va) {
        for (int offset = 0; offset <= 8; offset += 8) {
            String text = image.constantString(va + offset);
            if (text == null) {
                text = image.cString(image.pointer(va + offset));
                if (text != null && text.length() != image.u64(va + offset + 8)) {
                    text = null;
                }
            }
            if (text != null && COMPONENT_NAME.matcher(text).matches()) {
                return text;
            }
        }
        return null;
    }

    /** @return whether a table is of the family: a constant in the network slot and base class functions of the anchor */
    private boolean inFamily(Table table, Layout layout, Table anchor) {
        if (table.slots().length <= layout.readerSlot() || constantBool(table.slots()[layout.networkSlot()]) == null) {
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

    private Layout itemFamily(List<Component> components) {
        Map<String, Table> best = Map.of();
        Layout layout = null;
        Table anchor = null;
        for (long function : functionsReferring(image.literals(ITEM_ANCHOR), 1).keySet()) {
            List<Mention> mentions = mentions(function);
            // The anchor's class: the first table after the anchor's name that has a network writer
            Table found = null;
            boolean named = false;
            for (Mention mention : mentions) {
                if (mention.name() != null) {
                    named = mention.name().equals(ITEM_ANCHOR);
                } else if (named && found == null && networkSlot(mention.table(), -1) >= 0) {
                    found = mention.table();
                }
            }
            if (found == null) {
                continue;
            }
            Layout foundLayout = new Layout(-1, networkSlot(found, -1));
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
            notes.add("the item component classes could not be paired with their names: they are listed without names");
        }
        for (Map.Entry<String, Table> entry : best.entrySet()) {
            Component component = new Component();
            component.name = entry.getKey();
            component.table = reliable ? entry.getValue() : null;
            if (component.table == null && reliable) {
                notes.add("item component " + component.name + " is registered, but its class was not found");
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
            if (image.isCode(to) && isTableStart(at)) {
                starts.add(at);
            }
        });
        for (long start : starts) {
            Table table = known.contains(start) ? null : tableAt(start);
            if (table == null || !inFamily(table, layout, anchor)) {
                continue;
            }
            Component component = new Component();
            component.table = table;
            components.add(component);
        }
    }

    // ----- What functions mention ---------------------------------------------------------------------------------------

    private static boolean isKey(String text) {
        return text != null && KEY.matcher(text).matches() && !NOT_KEYS.contains(text);
    }

    /** @return what a function mentions itself and, as {@code nested}, what the helpers it calls mention */
    Facts facts(long function) {
        Facts cached = factsCache.get(function);
        if (cached != null) {
            return cached;
        }
        Facts facts = own(function);
        factsCache.put(function, facts);
        for (long call : facts.calls) {
            long size = size(call);
            if (size == 0 || size > HELPER_SIZE) {
                continue;
            }
            Facts helper = own(call);
            facts.nestedKeys.addAll(helper.keys);
            facts.nestedTables.addAll(helper.tables);
            facts.nestedData.addAll(helper.data);
        }
        facts.nestedKeys.removeAll(facts.keys);
        return facts;
    }

    private Facts own(long function) {
        Facts facts = new Facts();
        CRC32 code = new CRC32();
        String lastKey = null;
        boolean keySinceCall = false;
        for (Event event : listing(function)) {
            String mark;
            switch (event.kind()) {
                case TEXT -> {
                    mark = "t" + event.text();
                    if (isKey(event.text())) {
                        facts.keys.add(event.text());
                        lastKey = event.text();
                        keySinceCall = true;
                    }
                }
                case DATA -> {
                    String literal = image.cString(event.value());
                    mark = literal != null ? "s" + literal : "d";
                    if (isKey(literal)) {
                        facts.keys.add(literal);
                        lastKey = literal;
                        keySinceCall = true;
                    } else if (literal == null) {
                        facts.data.add(event.value());
                        if (isTableStart(event.value())) {
                            facts.tables.add(event.value());
                        }
                    }
                }
                case CALL -> {
                    mark = "c";
                    facts.calls.add(event.value());
                    if (keySinceCall) {
                        facts.keyCalls.add(event.value());
                        keySinceCall = false;
                    }
                    String type = lastKey == null ? null : helperType(event.value());
                    if (type != null) {
                        facts.types.putIfAbsent(lastKey, type);
                        lastKey = null;
                    }
                }
                case VIRTUAL_CALL -> {
                    mark = "v" + event.value();
                    facts.virtualCalls.add(event.value());
                }
                default -> {
                    mark = "i" + event.value() + "@" + event.extra();
                    if (isTagType(event) && lastKey != null) {
                        facts.types.putIfAbsent(lastKey, TAG_TYPES[(int) event.value()]);
                        lastKey = null;
                    }
                }
            }
            code.update(mark.getBytes(StandardCharsets.UTF_8));
        }
        facts.code = String.format("%08x", code.getValue());
        return facts;
    }

    /**
     * Linux (libc++): a tag is added to a compound by adding the key to its map and then storing the tag's type number at
     * offset 0x60 of the new map node. That store names the NBT type of the key before it.
     */
    private boolean isTagType(Event event) {
        return !image.windows && event.kind() == Kind.STORE && event.extra() == 0x60 && event.value() >= 1 && event.value() <= 11;
    }

    /** @return the tag type a small function stores, if it stores exactly one: the type of a {@code putByte}-like helper */
    private String helperType(long function) {
        long size = size(function);
        if (size == 0 || size > FACTORY_SIZE) {
            return null;
        }
        Set<Long> types = new HashSet<>();
        for (Event event : listing(function)) {
            if (isTagType(event)) {
                types.add(event.value());
            }
        }
        return types.size() == 1 ? TAG_TYPES[types.iterator().next().intValue()] : null;
    }

    private boolean puts(Facts facts) {
        return facts.calls.stream().anyMatch(putHelpers::contains);
    }

    // ----- Classification -----------------------------------------------------------------------------------------------

    /**
     * Fills in each component's network flag, writer, reader and kind.
     *
     * @return the table of the reflection writer class: the one that the writers without keys of their own share
     */
    private long classify(List<Component> blocks, Layout blockLayout, List<Component> items, Layout itemLayout) {
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
            component.network = constantBool(slots[entry.getValue().networkSlot()]);
            component.writer = facts(slots[entry.getValue().writerSlot()]);
            component.reader = facts(slots[entry.getValue().readerSlot()]);
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
            notes.add("no shared reflection writer found: every writer is reported as hand-written");
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
        putHelpers = helpers;
        if (helpers.isEmpty()) {
            notes.add("no tag-writing helper found: second writers and envelope writers cannot be told from readers");
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
                if (users.get(slots[i]) == 1 && size(slots[i]) > 16) {
                    component.overrides.add(i);
                    Facts extra = facts(slots[i]);
                    if (puts(extra)) {
                        component.extraKeys.addAll(extra.keys);
                    }
                }
            }
            // The reflection writer is used by the writer itself or by a function it calls, other writers aside
            boolean reflection = schemaWriter != 0 && component.writer.tables.contains(schemaWriter);
            for (long call : component.writer.calls) {
                long size = size(call);
                if (schemaWriter != 0 && !writers.contains(call) && size != 0 && size <= HELPER_SIZE && own(call).tables.contains(schemaWriter)) {
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
    private void itemSchemas(List<Component> items) {
        Map<Long, Integer> users = new HashMap<>();
        for (Component item : items) {
            if (item.writer != null) {
                Set<Long> data = new HashSet<>(item.writer.data);
                data.addAll(item.writer.nestedData);
                data.forEach(address -> users.merge(address, 1, Integer::sum));
            }
        }
        List<Long> unique = users.entrySet().stream().filter(entry -> entry.getValue() == 1).map(Map.Entry::getKey).toList();
        Map<Long, Set<Long>> functions = functionsReferring(unique, 1);
        for (Component item : items) {
            if (item.name == null || item.writer == null || !Boolean.TRUE.equals(item.network)) {
                continue;
            }
            Set<Long> own = new HashSet<>(item.writer.data);
            own.addAll(item.writer.nestedData);
            long best = 0;
            for (Map.Entry<Long, Set<Long>> function : functions.entrySet()) {
                long size = size(function.getKey());
                if (function.getValue().stream().noneMatch(own::contains) || size > 0x8000 || best != 0 && size >= size(best)) {
                    continue;
                }
                Set<String> names = new HashSet<>();
                for (Event event : listing(function.getKey())) {
                    String name = componentName(event);
                    if (name == null && event.kind() == Kind.DATA) {
                        name = constantName(event.value());
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
                item.schema = own(best).keys.stream().filter(key -> !COMPONENT_NAME.matcher(key).matches()).toList();
            }
        }
    }

    // ----- Envelopes and traits -----------------------------------------------------------------------------------------

    /** The functions that mention a key: as a string literal, or as the number its first eight characters make. */
    private Set<Long> functionsMentioning(String key) {
        Set<Long> functions = new TreeSet<>(functionsReferring(image.literals(key), 1).keySet());
        if (key.length() >= 8) {
            for (long site : image.codeOccurrences(key.substring(0, 8).getBytes(StandardCharsets.US_ASCII))) {
                long function = image.functionOf(site);
                if (function != 0) {
                    functions.add(function);
                }
            }
        }
        functions.removeIf(function -> !facts(function).keys.contains(key));
        return functions;
    }

    /** The keys of the functions that mention an envelope key: the ones that put tags write it, the others read it. */
    private JsonObject envelope(String key) {
        Set<String> writes = new TreeSet<>();
        Set<String> reads = new TreeSet<>();
        Set<String> nested = new TreeSet<>();
        Map<String, String> types = new TreeMap<>();
        for (long function : functionsMentioning(key)) {
            Facts facts = facts(function);
            boolean writer = puts(facts);
            (writer ? writes : reads).addAll(facts.keys);
            if (writer) {
                nested.addAll(facts.nestedKeys);
                types.putAll(facts.types);
            }
        }
        nested.removeAll(writes);
        if (writes.isEmpty()) {
            notes.add("no writer found for the envelope with " + key);
        }
        JsonObject json = new JsonObject();
        json.add("writes", array(writes));
        json.add("writesNested", array(nested));
        json.add("types", object(types));
        json.add("reads", array(reads));
        return json;
    }

    /**
     * @return the component name a virtual function returns: it either only returns a static string, or it is a small
     * function that sets a static up from one name literal on first use; null for any other function
     */
    private String returnedName(long function) {
        Long target = getters.get(function);
        if (target != null) {
            return stringStatics(List.of(target)).get(target);
        }
        long size = size(function);
        if (size == 0 || size > 0x100) {
            return null;
        }
        Set<String> names = new HashSet<>();
        for (Event event : listing(function)) {
            if (componentName(event) != null) {
                names.add(componentName(event));
            }
        }
        return names.size() == 1 ? names.iterator().next() : null;
    }

    /** Block traits: the classes with a virtual function that writes {@link #TRAIT_KEY}. */
    private JsonObject traits(long schemaWriter) {
        Map<String, JsonObject> traits = new TreeMap<>();
        Set<Long> functions = functionsMentioning(TRAIT_KEY);
        functions.removeIf(function -> !puts(facts(function)));
        Map<Long, List<Long>> slots = new HashMap<>();
        image.pointers((at, to) -> {
            if (functions.contains(to)) {
                slots.computeIfAbsent(to, f -> new ArrayList<>()).add(at);
            }
        });
        int unnamed = 0;
        for (Map.Entry<Long, List<Long>> entry : new TreeMap<>(slots).entrySet()) {
            Facts facts = facts(entry.getKey());
            Table table = tableAt(entry.getValue().getFirst());
            JsonObject json = new JsonObject();
            if (table.className() != null) {
                json.addProperty("class", table.className());
            }
            boolean reflection = schemaWriter != 0 && (facts.tables.contains(schemaWriter) || facts.nestedTables.contains(schemaWriter));
            json.addProperty("kind", reflection ? "mixed" : "hand-written");
            json.add("writes", array(new TreeSet<>(facts.keys)));
            if (!facts.nestedKeys.isEmpty()) {
                json.add("writesNested", array(new TreeSet<>(facts.nestedKeys)));
            }
            if (!facts.types.isEmpty()) {
                json.add("types", object(new TreeMap<>(facts.types)));
            }
            json.addProperty("code", facts.code);
            // A trait returns its name from a virtual function, like a block component
            String name = Arrays.stream(table.slots()).mapToObj(this::returnedName)
                .filter(text -> text != null && COMPONENT_NAME.matcher(text).matches()).findFirst()
                .orElse(table.className() != null ? table.className() : "unnamed-" + unnamed++);
            traits.put(name, json);
        }
        JsonObject json = new JsonObject();
        traits.forEach(json::add);
        return json;
    }

    // ----- JSON ---------------------------------------------------------------------------------------------------------

    private JsonObject components(List<Component> components) {
        Map<String, JsonObject> sorted = new TreeMap<>();
        for (Component component : components) {
            if (component.name != null && sorted.putIfAbsent(component.name, json(component)) != null) {
                notes.add("more than one class is named " + component.name + ": only the first is listed");
            }
        }
        JsonObject json = new JsonObject();
        sorted.forEach(json::add);
        return json;
    }

    /**
     * The classes of the family that have no component name: the ones that are sent in full (sorted by what they
     * write, as a build without type names has nothing else to tell them apart), the others by class name or count.
     */
    private JsonObject withoutName(List<Component> components) {
        Map<String, JsonObject> sent = new TreeMap<>();
        Map<String, Integer> alike = new HashMap<>();
        Set<String> notSent = new TreeSet<>();
        int notSentCount = 0;
        for (Component component : components) {
            if (component.name != null) {
                continue;
            }
            if (Boolean.TRUE.equals(component.network)) {
                JsonObject json = json(component);
                sent.put(json.toString(), json);
                alike.merge(json.toString(), 1, Integer::sum);
            } else {
                notSentCount++;
                if (component.table.className() != null) {
                    notSent.add(component.table.className());
                }
            }
        }
        JsonObject json = new JsonObject();
        JsonArray array = new JsonArray();
        sent.forEach((text, entry) -> {
            if (alike.get(text) > 1) {
                entry.addProperty("count", alike.get(text));
            }
            array.add(entry);
        });
        json.add("sent", array);
        json.addProperty("notSent", notSentCount);
        if (!notSent.isEmpty()) {
            json.add("notSentClasses", array(notSent));
        }
        return json;
    }

    private JsonObject json(Component component) {
        JsonObject json = new JsonObject();
        if (component.table == null) {
            json.addProperty("kind", "unknown");
            return json;
        }
        if (component.table.className() != null) {
            json.addProperty("class", component.table.className());
        }
        json.addProperty("network", Boolean.TRUE.equals(component.network));
        json.addProperty("kind", component.kind);
        if (Boolean.TRUE.equals(component.network)) {
            json.add("writes", array(new TreeSet<>(component.writer.keys)));
            if (!component.extraKeys.isEmpty()) {
                json.add("writesExtra", array(component.extraKeys));
            }
            if (!component.writer.nestedKeys.isEmpty()) {
                json.add("writesNested", array(new TreeSet<>(component.writer.nestedKeys)));
            }
            if (!component.writer.types.isEmpty()) {
                json.add("types", object(new TreeMap<>(component.writer.types)));
            }
            if (!component.reader.keys.isEmpty()) {
                json.add("reads", array(new TreeSet<>(component.reader.keys)));
            }
            if (component.schema != null) {
                json.add("schema", array(component.schema));
            }
            json.addProperty("code", component.writer.code);
        }
        if (!component.overrides.isEmpty()) {
            JsonArray overrides = new JsonArray();
            component.overrides.forEach(overrides::add);
            json.add("hooks", overrides);
        }
        return json;
    }

    private static JsonArray array(Collection<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static JsonObject object(Map<String, String> values) {
        JsonObject object = new JsonObject();
        values.forEach(object::addProperty);
        return object;
    }
}
