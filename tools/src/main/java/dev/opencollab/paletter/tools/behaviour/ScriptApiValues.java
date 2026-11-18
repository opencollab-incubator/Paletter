package dev.opencollab.paletter.tools.behaviour;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.behaviour.BehaviourKey;
import dev.opencollab.paletter.behaviour.BlockBehaviour;
import dev.opencollab.paletter.behaviour.ItemBehaviour;
import dev.opencollab.paletter.tools.codegen.component.BehaviourKeys;
import dev.opencollab.paletter.tools.codegen.component.BehaviourKeys.Conversion;
import dev.opencollab.paletter.tools.codegen.component.BehaviourKeys.Key;
import dev.opencollab.paletter.tools.codegen.component.BehaviourKeys.Owner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The values of the keys whose sources are Script API properties ({@code SAPI:} in {@link BehaviourKeys}), read from a
 * behaviour dump ({@link BehaviourDump}) with the key's conversion. The Script API names block states its own way (legacy
 * alias states too); a state is the palette state whose states it has. A world-dependent property (the dump's world check)
 * is only read for blocks the check did not find depending on the world, and only after the key's first source.
 */
final class ScriptApiValues {
    private final JsonObject dump;
    private final List<String> log;
    /** Sources whose colour had no 0xRRGGBBAA, once per value. */
    private final List<String> unexact = new ArrayList<>();

    ScriptApiValues(JsonObject dump, List<String> log) {
        this.dump = dump;
        this.log = log;
    }

    /**
     * @param palette the bundled version's states by hash
     * @return the block values, one {@link Values} per set of states the keys cover: a capture covers exactly the ids it
     * has values for, so a key without a value for a state never confirms another source's value
     */
    List<Values<Integer>> blocks(Map<Integer, NbtMap> palette, String where) {
        Map<String, List<JsonElement>> columns = new HashMap<>(BehaviourDump.columns(dump, "permutation"));
        columns.putAll(BehaviourDump.columns(dump, "placed"));
        Map<String, Set<String>> dependent = dependent();
        JsonArray states = dump.getAsJsonArray("states");
        Integer[] hashes = hashes(states, palette, where);

        Map<Key, Map<Integer, Object>> byKey = new LinkedHashMap<>();
        for (Key key : BehaviourKeys.KEYS) {
            List<String> sources = key.sources("SAPI:");
            if (key.owner() != Owner.BLOCK || sources.isEmpty()) {
                continue;
            }
            Map<Integer, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < states.size(); i++) {
                String block = states.get(i).getAsJsonArray().get(0).getAsString();
                if (hashes[i] == null || values.containsKey(hashes[i])) {
                    continue;
                }
                int state = i;
                Object value = null;
                for (int s = 0; s < sources.size() && value == null; s++) {
                    String source = sources.get(s);
                    if (BehaviourKeys.expand(source).stream().anyMatch(p -> dependent.getOrDefault(p, Set.of()).contains(block))) {
                        continue;
                    }
                    value = convert(key, source, property -> columns.containsKey(property) ? columns.get(property).get(state) : null, where);
                }
                if (value != null) {
                    values.put(hashes[i], value);
                }
            }
            byKey.put(key, values);
        }
        if (!unexact.isEmpty()) {
            log.add(where + ": " + unexact.size() + " colours of " + new TreeSet<>(unexact) + " are no whole bytes (BDS keeps colours as floats; "
                + "Education Edition elements): not stored, rounding would be a guess");
            unexact.clear();
        }
        return grouped(byKey, BlockBehaviour::key);
    }

    /** @return the item values, grouped as {@link #blocks} */
    List<Values<String>> items(Set<String> palette, String where) {
        JsonObject items = dump.getAsJsonObject("items");
        Map<Key, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Key key : BehaviourKeys.KEYS) {
            List<String> sources = key.sources("SAPI:");
            if (key.owner() != Owner.ITEM || sources.isEmpty()) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> item : items.entrySet()) {
                if (!palette.contains(item.getKey())) {
                    continue;
                }
                JsonObject properties = item.getValue().getAsJsonObject();
                Object value = null;
                for (int s = 0; s < sources.size() && value == null; s++) {
                    value = convert(key, sources.get(s), properties::get, where + " " + item.getKey());
                }
                if (value == null && key.conversion() == Conversion.ABSENT_ZERO) {
                    value = 0;
                }
                if (value != null) {
                    values.put(item.getKey(), value);
                }
            }
            byKey.put(key, values);
        }
        return grouped(byKey, ItemBehaviour::key);
    }

    private interface Lookup {
        JsonElement get(String property);
    }

    /** @return the key's value from one source, or null if the source has none (absent, or an error the dump recorded) */
    private Object convert(Key key, String source, Lookup lookup, String where) {
        if (key.conversion() == Conversion.DIRECTION_BITS) {
            int bits = 0;
            List<String> properties = BehaviourKeys.expand(source);
            for (int d = 0; d < properties.size(); d++) {
                JsonElement answer = value(lookup.get(properties.get(d)), where);
                if (answer == null) {
                    return null;
                }
                bits |= answer.getAsBoolean() ? 1 << d : 0;
            }
            return bits;
        }
        JsonElement value = value(lookup.get(source), where);
        if (value == null) {
            return null;
        }
        if (key.conversion() == Conversion.RGBA) {
            JsonObject rgba = value.getAsJsonObject();
            int color = 0;
            for (String channel : List.of("red", "green", "blue", "alpha")) {
                double scaled = rgba.get(channel).getAsDouble() * 255;
                if (Math.abs(scaled - Math.rint(scaled)) > 1e-3) {
                    // no 0xRRGGBBAA says it (Education Edition elements): rounding would be a guess, the source has no value
                    unexact.add(source);
                    return null;
                }
                color = color << 8 | (int) Math.rint(scaled);
            }
            return color;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return switch (key.type()) {
            case "Boolean" -> primitive.getAsBoolean();
            case "Integer" -> {
                if (primitive.getAsDouble() != Math.rint(primitive.getAsDouble())) {
                    throw new IllegalStateException(where + ": " + source + " " + primitive + " is not a whole number");
                }
                yield primitive.getAsInt();
            }
            case "Float" -> primitive.getAsFloat();
            case "String" -> primitive.getAsString();
            default -> throw new IllegalStateException(key.name() + ": the Script API gives no " + key.type());
        };
    }

    /** @return the value, or null if there is none or the dump recorded the error reading it */
    private JsonElement value(JsonElement value, String where) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().startsWith("error: ")) {
            log.add(where + ": " + value.getAsString());
            return null;
        }
        return value;
    }

    /** @return the world-dependent properties of the dump's world check, with the blocks that showed it */
    private Map<String, Set<String>> dependent() {
        Map<String, Set<String>> result = new HashMap<>();
        for (Map.Entry<String, JsonElement> property : dump.getAsJsonObject("world").getAsJsonObject("dependent").entrySet()) {
            Set<String> blocks = new HashSet<>();
            property.getValue().getAsJsonObject().getAsJsonArray("blocks").forEach(block -> blocks.add(block.getAsString()));
            result.put(property.getKey(), blocks);
        }
        return result;
    }

    /**
     * @return the palette hash of each Script API state, null where the palette has no such state; the Script API's legacy
     * alias states (that the palette lacks) are left out, its booleans are the palette's bits
     */
    static Integer[] hashes(JsonArray states, Map<Integer, NbtMap> palette, List<String> log, String where) {
        Map<String, Set<String>> names = new HashMap<>();
        Map<String, Integer> byKey = new HashMap<>();
        palette.forEach((hash, state) -> {
            String block = state.getString("name");
            NbtMap values = state.getCompound("states");
            names.computeIfAbsent(block, b -> new HashSet<>()).addAll(values.keySet());
            Map<String, String> canonical = new TreeMap<>();
            values.forEach((name, value) -> canonical.put(name, value.toString()));
            byKey.put(block + canonical, hash);
        });
        Integer[] hashes = new Integer[states.size()];
        int missing = 0;
        for (int i = 0; i < states.size(); i++) {
            JsonArray state = states.get(i).getAsJsonArray();
            String block = state.get(0).getAsString();
            Set<String> known = names.getOrDefault(block, Set.of());
            Map<String, String> canonical = new TreeMap<>();
            for (Map.Entry<String, JsonElement> value : state.get(1).getAsJsonObject().entrySet()) {
                if (known.contains(value.getKey())) {
                    JsonPrimitive primitive = value.getValue().getAsJsonPrimitive();
                    canonical.put(value.getKey(), primitive.isBoolean() ? (primitive.getAsBoolean() ? "1" : "0")
                        : primitive.isNumber() ? String.valueOf(primitive.getAsInt()) : primitive.getAsString());
                }
            }
            hashes[i] = byKey.get(block + canonical);
            if (hashes[i] == null) {
                missing++;
            }
        }
        if (missing > 0) {
            log.add(where + ": " + missing + " Script API states are no state of the palette (blocks it does not have)");
        }
        return hashes;
    }

    private Integer[] hashes(JsonArray states, Map<Integer, NbtMap> palette, String where) {
        Integer[] hashes = hashes(states, palette, log, where);
        Set<Integer> found = new HashSet<>(java.util.Arrays.asList(hashes));
        List<String> absent = new ArrayList<>();
        palette.forEach((hash, state) -> {
            if (!found.contains(hash)) {
                absent.add(state.getString("name") + new TreeMap<>(state.getCompound("states")));
            }
        });
        if (!absent.isEmpty()) {
            log.add(where + ": " + absent.size() + " palette states the Script API did not list: " + new TreeSet<>(absent));
        }
        return hashes;
    }

    interface Keys {
        BehaviourKey<?> key(String name);
    }

    static <I> List<Values<I>> grouped(Map<Key, Map<I, Object>> byKey, Keys keys) {
        Map<Set<I>, Values<I>> groups = new LinkedHashMap<>();
        byKey.forEach((key, values) -> {
            if (values.isEmpty()) {
                return;
            }
            BehaviourKey<?> behaviourKey = keys.key(key.name());
            if (behaviourKey == null) {
                throw new IllegalStateException("No generated key " + key.name() + ": run ./gradlew :tools:generateComponentApi");
            }
            Values<I> group = groups.computeIfAbsent(new HashSet<>(values.keySet()), ids -> new Values<>());
            values.forEach((id, value) -> group.put(behaviourKey, id, value));
        });
        return new ArrayList<>(groups.values());
    }
}
