package dev.opencollab.paletter.tools.whitebox;

import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.whitebox.Listing.Event;
import dev.opencollab.paletter.tools.whitebox.Scan.Facts;
import dev.opencollab.paletter.tools.whitebox.Scan.Table;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static dev.opencollab.paletter.tools.whitebox.FactsJson.array;
import static dev.opencollab.paletter.tools.whitebox.FactsJson.object;

/**
 * The keys written around the components: the item and block envelopes, and the block traits. Found through a key that
 * only that writer mentions.
 */
final class Envelopes {
    /** A key only the item envelope writes, and one only the block envelope writes. */
    static final String ITEM_ENVELOPE_KEY = "item_properties";
    static final String BLOCK_ENVELOPE_KEY = "vanilla_block_data";
    /** A key every block trait writes. */
    static final String TRAIT_KEY = "enabled_states";

    private final Scan scan;
    private final Image image;

    Envelopes(Scan scan) {
        this.scan = scan;
        this.image = scan.image;
    }

    /** The functions that mention a key: as a string literal, or as the number its first eight characters make. */
    private Set<Long> functionsMentioning(String key) {
        Set<Long> functions = new TreeSet<>(scan.functionsReferring(image.literals(key), 1).keySet());
        if (key.length() >= 8) {
            for (long site : image.codeOccurrences(key.substring(0, 8).getBytes(StandardCharsets.US_ASCII))) {
                long function = image.functionOf(site);
                if (function != 0) {
                    functions.add(function);
                }
            }
        }
        functions.removeIf(function -> !scan.facts(function).keys.contains(key));
        return functions;
    }

    /** The keys of the functions that mention an envelope key: the ones that put tags write it, the others read it. */
    JsonObject envelope(String key) {
        Set<String> writes = new TreeSet<>();
        Set<String> reads = new TreeSet<>();
        Set<String> nested = new TreeSet<>();
        Map<String, String> types = new TreeMap<>();
        for (long function : functionsMentioning(key)) {
            Facts facts = scan.facts(function);
            boolean writer = scan.puts(facts);
            (writer ? writes : reads).addAll(facts.keys);
            if (writer) {
                nested.addAll(facts.nestedKeys);
                types.putAll(facts.types);
            }
        }
        nested.removeAll(writes);
        if (writes.isEmpty()) {
            scan.notes.add("no writer found for the envelope with " + key);
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
        Long target = scan.getters.get(function);
        if (target != null) {
            return scan.stringStatics(List.of(target)).get(target);
        }
        long size = scan.size(function);
        if (size == 0 || size > 0x100) {
            return null;
        }
        Set<String> names = new HashSet<>();
        for (Event event : scan.listing(function)) {
            if (scan.componentName(event) != null) {
                names.add(scan.componentName(event));
            }
        }
        return names.size() == 1 ? names.iterator().next() : null;
    }

    /** Block traits: the classes with a virtual function that writes {@link #TRAIT_KEY}. */
    JsonObject traits(long schemaWriter) {
        Map<String, JsonObject> traits = new TreeMap<>();
        Set<Long> functions = functionsMentioning(TRAIT_KEY);
        functions.removeIf(function -> !scan.puts(scan.facts(function)));
        Map<Long, List<Long>> slots = new HashMap<>();
        image.pointers((at, to) -> {
            if (functions.contains(to)) {
                slots.computeIfAbsent(to, f -> new ArrayList<>()).add(at);
            }
        });
        int unnamed = 0;
        for (Map.Entry<Long, List<Long>> entry : new TreeMap<>(slots).entrySet()) {
            Facts facts = scan.facts(entry.getKey());
            Table table = scan.tableAt(entry.getValue().getFirst());
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
                .filter(text -> text != null && Scan.COMPONENT_NAME.matcher(text).matches()).findFirst()
                .orElse(table.className() != null ? table.className() : "unnamed-" + unnamed++);
            traits.put(name, json);
        }
        JsonObject json = new JsonObject();
        traits.forEach(json::add);
        return json;
    }
}
