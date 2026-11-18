package dev.opencollab.paletter.tools.whitebox;

import dev.opencollab.paletter.tools.whitebox.Listing.Event;
import dev.opencollab.paletter.tools.whitebox.Listing.Kind;

import java.nio.charset.StandardCharsets;
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
 * What the code of one binary says: its virtual tables, the names and keys that functions mention, and the small
 * functions that return a constant or a static string. The steps of {@link Whitebox} read the binary through this.
 */
final class Scan {
    static final Pattern COMPONENT_NAME = Pattern.compile("minecraft:[a-z0-9_.]+");
    private static final Pattern KEY = Pattern.compile("(?!__)[A-Za-z_][A-Za-z0-9_:.]{0,63}");
    /** Literals of the standard library's own error paths, which are not keys. */
    private static final Set<String> NOT_KEYS = Set.of("vector", "basic_string", "deque", "string", "map", "list", "bad_cast",
        "invalid", "true", "false", "null", "nan", "inf");
    private static final String[] TAG_TYPES = {"end", "byte", "short", "int", "long", "float", "double", "byte_array", "string",
        "list", "compound", "int_array"};
    /** Functions called from a writer are looked into when they are at most this big. */
    static final int HELPER_SIZE = 0x3000;
    /** A function that only constructs one object is at most this big. */
    static final int FACTORY_SIZE = 0x1000;

    /** A virtual table: its address, the functions in its slots, and the class name if the build stores one. */
    record Table(long address, long[] slots, String className) {
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

    final Image image;
    private final Map<Long, Facts> factsCache = new HashMap<>();
    private final Map<Long, List<Event>> listingCache = new HashMap<>();
    /** Windows: the addresses of virtual tables, which follow each other without a gap. */
    private long[] tableStarts;
    final List<String> notes = new ArrayList<>();
    /** Functions that only return the address of a static, and that static. */
    final Map<Long, Long> getters = new HashMap<>();
    /** The functions that hand-written writers call to put a tag and readers do not. */
    Set<Long> putHelpers = Set.of();

    Scan(Image image) {
        this.image = image;
        if (image.windows) {
            tableStarts = image.codePointerTables();
        }
    }

    // ----- Tables -----------------------------------------------------------------------------------------------------

    boolean isTableStart(long va) {
        if (!image.isCodePointer(va) || image.isCode(va)) {
            return false;
        }
        return image.windows ? Arrays.binarySearch(tableStarts, va) >= 0 : !image.isCodePointer(va - 8);
    }

    Table tableAt(long start) {
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

    long size(long function) {
        long end = image.functionEnd(function);
        return end == 0 ? 0 : end - function;
    }

    /**
     * Finds the network slots in the table of a component that is known to be sent: the first slot that returns a
     * constant and is followed by two functions with real code (the writer and the reader).
     */
    int networkSlot(Table table, int after) {
        for (int i = after + 1; i + 2 < table.slots().length; i++) {
            if (Boolean.TRUE.equals(constantBool(table.slots()[i])) && size(table.slots()[i + 1]) >= 0x40 && size(table.slots()[i + 2]) >= 0x40) {
                return i;
            }
        }
        return -1;
    }

    // ----- Names of statics -------------------------------------------------------------------------------------------

    List<Event> listing(long function) {
        return listingCache.computeIfAbsent(function, start -> Listing.of(image, start));
    }

    /** @return the component name an instruction mentions: as a string literal, or as a short string built in place */
    String componentName(Event event) {
        String text = event.kind() == Kind.DATA ? image.cString(event.value()) : event.kind() == Kind.TEXT ? event.text() : null;
        return text != null && COMPONENT_NAME.matcher(text).matches() ? text : null;
    }

    /**
     * Statics that code sets up from a component name literal: between two calls, a function that mentions exactly one
     * name literal and writable statics is initialising those statics with that name.
     *
     * @return static address to name
     */
    Map<Long, String> staticNames(List<Event> events) {
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
    Map<Long, Set<Long>> functionsReferring(Collection<Long> addresses, int span) {
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
    Map<Long, String> stringStatics(Collection<Long> addresses) {
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

    /**
     * @return the component name in a static that the file itself initialises. The static is a string, a pointer to a
     * literal followed by its length, or a hashed string, which keeps either of the two after an 8-byte hash
     */
    String constantName(long va) {
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

    static boolean isKey(String text) {
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

    Facts own(long function) {
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
    boolean isTagType(Event event) {
        return !image.windows && event.kind() == Kind.STORE && event.extra() == 0x60 && event.value() >= 1 && event.value() <= 11;
    }

    /** @return the tag type a small function stores, if it stores exactly one: the type of a {@code putByte}-like helper */
    String helperType(long function) {
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

    boolean puts(Facts facts) {
        return facts.calls.stream().anyMatch(putHelpers::contains);
    }
}
