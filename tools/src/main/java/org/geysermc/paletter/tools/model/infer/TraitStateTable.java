package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.block.BlockHashes;
import org.geysermc.paletter.tools.Layout;
import org.geysermc.paletter.tools.bds.BdsDump;
import org.geysermc.paletter.tools.data.Nbt;
import org.geysermc.paletter.tools.model.Model;
import org.geysermc.paletter.tools.model.Schema;
import org.geysermc.paletter.tools.update.SpecCapture;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * The block states each trait adds, per value of its {@code enabled_states} ({@code mappings.json} {@code traitStates}).
 * The client adds them; the wire carries only the trait, so the spec captures do not show them. They are derived from:
 * <ul>
 *     <li><b>names</b>: the docs' "adds state" notes of the trait. A value of {@code enabled_states} adds the state of its own
 *     name, a value naming states by parts the states of its parts ({@code minecraft:corner_and_cardinal_direction}:
 *     {@code minecraft:corner} and {@code minecraft:cardinal_direction}, as the hash BDS sent says); the one value that
 *     names no state adds the states that no value names ({@code minecraft:cardinal_connections} the four
 *     {@code connection_*}). A value the docs name no state for, when every documented state is taken
 *     ({@code minecraft:sixteen_way_rotation}, which BDS's schema lists), adds the state of its own name if BDS's block states
 *     ({@link BdsStates}) have one, else it is skipped and reported. Anything else fails;</li>
 *     <li><b>values</b>: those of the vanilla state of the same name ({@code constants-history.json}, in BDS's palette order),
 *     which must be the docs' values; a state no vanilla block has keeps the docs' values in their order; a state the docs do
 *     not name has BDS's values, in BDS's order;</li>
 *     <li><b>default</b> (the first value): verified where BDS sent the hash of a default state that has the state: the
 *     creative inventory of a spec capture ({@code data/bds/<build>/spec}) holds one block per combination of every
 *     trait's {@code enabled_states}. A hash that differs from the table's default state fails the inference.</li>
 * </ul>
 */
final class TraitStateTable {
    private static final Pattern ADDS_STATE = Pattern.compile("adds state ([a-z_]+:[a-z_]+): (Boolean|Integer|String) (.+)");

    record State(String name, String type, List<JsonPrimitive> values, String valuesFrom) {
        Object first() {
            JsonPrimitive value = values.getFirst();
            return switch (type) {
                case "bool" -> (byte) value.getAsInt();
                case "int" -> value.getAsInt();
                default -> value.getAsString();
            };
        }
    }

    /**
     * BDS's block states by name with their values in BDS's order ({@code state_types} of the Script API dump,
     * {@code BlockStates.getAll()}), from the newest committed dump that keeps that order ({@code data/bds/<build>/dump.json.gz},
     * format 4 on; older dumps sorted the values).
     */
    record BdsStates(String build, Map<String, List<Object>> types) {
        static final BdsStates NONE = new BdsStates(null, Map.of());

        static BdsStates newest(Path root) throws IOException {
            List<Path> builds;
            try (Stream<Path> list = Files.list(root.resolve(Layout.BDS))) {
                builds = list.filter(build -> Files.exists(build.resolve(DUMP)))
                    .sorted((a, b) -> Schema.compareVersions(b.getFileName().toString(), a.getFileName().toString())).toList();
            }
            for (Path build : builds) {
                BdsDump dump = BdsDump.read(build.resolve(DUMP));
                if (!dump.stateTypes().isEmpty()) {
                    return new BdsStates(build.getFileName().toString(), dump.stateTypes());
                }
            }
            return NONE;
        }

        /** @return the state of this name with BDS's values, or null if BDS has none */
        State state(String name) {
            List<Object> values = types.get(name);
            if (values == null || values.isEmpty()) {
                return null;
            }
            String type = values.getFirst() instanceof Byte ? "bool" : values.getFirst() instanceof Integer ? "int" : "string";
            List<JsonPrimitive> json = values.stream().map(value -> switch (value) {
                case Byte b -> new JsonPrimitive(b.intValue());
                case Integer i -> new JsonPrimitive(i);
                default -> new JsonPrimitive(value.toString());
            }).toList();
            return new State(name, type, json, "BDS " + build + " (Script API BlockStates.getAll(), in its order; the docs name no state for it)");
        }
    }

    private static final String DUMP = "dump.json.gz";

    private TraitStateTable() {
    }

    /**
     * @return trait -> enabled_states value -> the states it adds, each with its values and where its default is verified
     */
    static JsonObject infer(Path root, Model model) throws IOException {
        if (model.components().stream().noneMatch(c -> c.category().equals("trait"))) {
            return new JsonObject();
        }
        JsonObject history = JsonParser.parseString(Files.readString(root.resolve(Layout.MODEL).resolve("constants-history.json")))
            .getAsJsonObject();
        BdsStates bds = BdsStates.newest(root);
        Map<String, Map<String, List<State>>> table = new TreeMap<>();
        for (Model.Component trait : model.components()) {
            if (trait.category().equals("trait")) {
                List<String> skipped = new ArrayList<>();
                table.put(trait.name(), statesOf(trait, history, bds, skipped));
                skipped.forEach(line -> System.out.println("Skipped: " + line + " (no dump of BDS's block states has a state of its name: not in the trait state table)"));
            }
        }
        Map<String, String> verified = verify(root, table);
        JsonObject out = new JsonObject();
        table.forEach((trait, byValue) -> {
            JsonObject values = new JsonObject();
            byValue.forEach((value, states) -> {
                JsonArray list = new JsonArray();
                for (State state : states) {
                    JsonObject json = new JsonObject();
                    json.addProperty("name", state.name());
                    json.addProperty("type", state.type());
                    JsonArray stateValues = new JsonArray();
                    state.values().forEach(stateValues::add);
                    json.add("values", stateValues);
                    json.addProperty("valuesFrom", state.valuesFrom());
                    json.addProperty("default", verified.getOrDefault(state.name(),
                        "unverified: no capture holds the hash of a default state with it"));
                    list.add(json);
                }
                values.add(value, list);
            });
            out.add(trait, values);
        });
        return out;
    }

    static Map<String, List<State>> statesOf(Model.Component trait, JsonObject history) {
        return statesOf(trait, history, BdsStates.NONE, new ArrayList<>());
    }

    /**
     * @param bds BDS's block states: an {@code enabled_states} value the docs name no state for ({@code minecraft:sixteen_way_rotation},
     *            which BDS's schema lists) adds the state of its own name, as the documented values do, with BDS's values for it
     * @param skipped gets such a value that BDS has no state of that name for either: it is left out of the table and reported
     */
    static Map<String, List<State>> statesOf(Model.Component trait, JsonObject history, BdsStates bds, List<String> skipped) {
        Map<String, State> documented = new LinkedHashMap<>();
        for (String note : trait.notes()) {
            Matcher m = ADDS_STATE.matcher(note.trim());
            if (m.matches()) {
                documented.put(m.group(1), state(trait, m.group(1), m.group(2), List.of(m.group(3).split("\\s*[/,]\\s*")), history));
            }
        }
        Model.Field enabled = trait.field("enabled_states");
        if (enabled == null || documented.isEmpty()) {
            throw new IllegalStateException(trait.id() + ": the docs list no enabled_states or no added states");
        }
        Map<String, List<State>> byValue = new LinkedHashMap<>();
        Set<String> unnamed = new LinkedHashSet<>(documented.keySet());
        String unmatched = null;
        for (JsonElement e : enabled.enumValues()) {
            String value = e.getAsString();
            State state = documented.get(value);
            List<String> parts = namedParts(value, documented.keySet());
            if (state != null) {
                byValue.put(value, List.of(state));
                unnamed.remove(value);
            } else if (parts != null) {
                byValue.put(value, parts.stream().map(documented::get).toList());
                parts.forEach(unnamed::remove);
            } else if (unmatched != null) {
                throw new IllegalStateException(trait.id() + ": enabled_states " + unmatched + " and " + value
                    + " name no state, so which adds what is open");
            } else {
                unmatched = value;
            }
        }
        if (unmatched != null) {
            if (unnamed.isEmpty()) {
                State state = bds.state(unmatched);
                if (state != null) {
                    byValue.put(unmatched, List.of(state));
                } else {
                    skipped.add(trait.id() + ": enabled_states " + unmatched + " adds no documented state");
                }
            } else {
                byValue.put(unmatched, unnamed.stream().map(documented::get).toList());
            }
        } else if (!unnamed.isEmpty()) {
            throw new IllegalStateException(trait.id() + ": no enabled_states value adds " + unnamed);
        }
        return byValue;
    }

    /**
     * The states a value names by parts ({@code minecraft:corner_and_cardinal_direction}: {@code minecraft:corner} and
     * {@code minecraft:cardinal_direction}), or null unless every part is a documented state.
     */
    static List<String> namedParts(String value, Set<String> documented) {
        int colon = value.indexOf(':');
        String[] parts = value.substring(colon + 1).split("_and_");
        if (parts.length < 2) {
            return null;
        }
        List<String> names = java.util.Arrays.stream(parts).map(part -> value.substring(0, colon + 1) + part).toList();
        return documented.containsAll(names) ? names : null;
    }

    private static State state(Model.Component trait, String name, String docsType, List<String> docsValues, JsonObject history) {
        JsonObject vanilla = null;
        for (Map.Entry<String, JsonElement> e : history.getAsJsonObject("states").entrySet()) {
            JsonObject s = e.getValue().getAsJsonObject();
            if (s.get("name").getAsString().equals(name) && !s.has("deprecated")) {
                vanilla = s;
            }
        }
        String type = switch (docsType) {
            case "Boolean" -> "bool";
            case "Integer" -> "int";
            default -> "string";
        };
        List<JsonPrimitive> values = new ArrayList<>();
        if (vanilla == null) {
            docsValues.forEach(v -> values.add(type.equals("int") ? new JsonPrimitive(Integer.parseInt(v)) : new JsonPrimitive(v)));
            return new State(name, type, values, "the docs (no vanilla block has the state)");
        }
        String kind = vanilla.get("kind").getAsString();
        List<String> vanillaValues = new ArrayList<>();
        switch (kind) {
            case "bool" -> {
                vanillaValues.addAll(List.of("false", "true"));
                values.add(new JsonPrimitive(0));
                values.add(new JsonPrimitive(1));
            }
            case "enum" -> history.getAsJsonObject("enums").getAsJsonObject(vanilla.get("enum").getAsString()).getAsJsonObject("values")
                .entrySet().forEach(v -> {
                    String value = v.getValue().getAsJsonObject().get("value").getAsString();
                    vanillaValues.add(value);
                    values.add(new JsonPrimitive(value));
                });
            default -> throw new IllegalStateException(trait.id() + ": the vanilla state " + name + " is " + kind + ", not the docs' " + docsType);
        }
        if (!(kind.equals("bool") ? type.equals("bool") : type.equals("string")) || !Set.copyOf(vanillaValues).equals(Set.copyOf(docsValues))) {
            throw new IllegalStateException(trait.id() + ": the docs' " + name + " (" + docsType + " " + docsValues
                + ") is not the vanilla state (" + kind + " " + vanillaValues + ")");
        }
        return new State(name, type, values, "the vanilla state " + name + (kind.equals("enum") ? " (" + vanilla.get("enum").getAsString() + ")" : ""));
    }

    /**
     * Compares the default state of every trait variant BDS accepted in a spec capture ({@code data/bds/<build>/spec}: one
     * variant per combination of {@code enabled_states}) with the hash BDS sent for it in the creative inventory.
     *
     * @return state name -> where its default is verified
     */
    private static Map<String, String> verify(Path root, Map<String, Map<String, List<State>>> table) throws IOException {
        Map<String, String> verified = new TreeMap<>();
        List<Path> captures;
        try (Stream<Path> builds = Files.list(root.resolve(Layout.BDS))) {
            captures = builds.map(build -> build.resolve("spec")).filter(dir -> Files.exists(dir.resolve(SpecCapture.CREATIVE_ITEMS))).sorted().toList();
        }
        for (Path capture : captures) {
            String build = capture.getParent().getFileName().toString();
            Map<String, Long> sent = new LinkedHashMap<>();
            try (Reader reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(capture.resolve(SpecCapture.CREATIVE_ITEMS))), StandardCharsets.UTF_8)) {
                for (JsonElement e : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("items")) {
                    JsonObject item = e.getAsJsonObject();
                    if (item.has("block_runtime_id")) {
                        sent.putIfAbsent(item.get("id").getAsString(), item.get("block_runtime_id").getAsLong());
                    }
                }
            }
            Map<String, JsonObject> traitVariants = new LinkedHashMap<>();
            for (JsonElement e : JsonParser.parseString(Files.readString(capture.resolve("variants.json"), StandardCharsets.UTF_8)).getAsJsonArray()) {
                JsonObject v = e.getAsJsonObject();
                if (v.get("component").getAsString().startsWith("block:trait:") && v.get("json") instanceof JsonObject json
                    && json.get("enabled_states") instanceof JsonArray) {
                    traitVariants.put(v.get("identifier").getAsString(), v);
                }
            }
            for (NbtMap block : Nbt.read(capture.resolve("custom_block_properties.nbt")).getList("blocks", NbtType.COMPOUND)) {
                String name = block.getString("name");
                JsonObject variant = traitVariants.get(name);
                if (variant == null || !sent.containsKey(name)) {
                    continue;
                }
                String trait = variant.get("component").getAsString().substring("block:trait:".length());
                NbtMapBuilder states = NbtMap.builder();
                for (NbtMap property : block.getCompound("properties").getList("properties", NbtType.COMPOUND, List.of())) {
                    states.put(property.getString("name"), ((NbtList<?>) property.get("enum")).getFirst());
                }
                List<String> added = new ArrayList<>();
                for (JsonElement value : variant.getAsJsonObject("json").getAsJsonArray("enabled_states")) {
                    for (State state : table.get(trait).getOrDefault(value.getAsString(), List.of())) {
                        states.put(state.name(), state.first());
                        added.add(state.name());
                    }
                }
                long hash = Integer.toUnsignedLong(BlockHashes.hash(NbtMap.builder().putString("name", name).putCompound("states", states.build()).build()));
                if (hash != sent.get(name)) {
                    throw new IllegalStateException("BDS " + build + " sent hash " + sent.get(name) + " for the default state of " + name
                        + " (" + trait + " " + variant.get("label").getAsString() + "), the trait states' first values give " + hash
                        + " (" + states.build() + "): data/model/mappings.json traitStates is wrong");
                }
                for (String state : added) {
                    verified.putIfAbsent(state, "verified (name and default): BDS " + build + " sent hash " + hash + " for the default state of "
                        + name + " (enabled_states " + variant.getAsJsonObject("json").getAsJsonArray("enabled_states") + ")");
                }
            }
        }
        return verified;
    }
}
