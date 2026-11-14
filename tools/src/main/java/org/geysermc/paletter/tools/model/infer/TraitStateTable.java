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
import org.geysermc.paletter.tools.data.Nbt;
import org.geysermc.paletter.tools.model.Model;

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
 *     name; the one value that names no state adds the states that no value names ({@code minecraft:cardinal_connections}
 *     the four {@code connection_*}). Anything else fails;</li>
 *     <li><b>values</b>: those of the vanilla state of the same name ({@code constants-history.json}, in BDS's palette order),
 *     which must be the docs' values; a state no vanilla block has keeps the docs' values in their order;</li>
 *     <li><b>default</b> (the first value): verified where BDS sent the hash of a default state that has the state: the
 *     creative inventory of the capture add-on ({@code data/bds/<build>/addon}) holds custom blocks with traits. A hash
 *     that differs from the table's default state fails the inference.</li>
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
        Map<String, Map<String, List<State>>> table = new TreeMap<>();
        for (Model.Component trait : model.components()) {
            if (trait.category().equals("trait")) {
                table.put(trait.name(), statesOf(trait, history));
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
            if (state != null) {
                byValue.put(value, List.of(state));
                unnamed.remove(value);
            } else if (unmatched != null) {
                throw new IllegalStateException(trait.id() + ": enabled_states " + unmatched + " and " + value
                    + " name no state, so which adds what is open");
            } else {
                unmatched = value;
            }
        }
        if (unmatched != null) {
            if (unnamed.isEmpty()) {
                throw new IllegalStateException(trait.id() + ": enabled_states " + unmatched + " adds no documented state");
            }
            byValue.put(unmatched, unnamed.stream().map(documented::get).toList());
        } else if (!unnamed.isEmpty()) {
            throw new IllegalStateException(trait.id() + ": no enabled_states value adds " + unnamed);
        }
        return byValue;
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
     * Compares the default state of every custom block with traits in a capture of the capture add-on with the hash BDS
     * sent for it in the creative inventory.
     *
     * @return state name -> where its default is verified
     */
    private static Map<String, String> verify(Path root, Map<String, Map<String, List<State>>> table) throws IOException {
        Map<String, String> verified = new TreeMap<>();
        Path blocks = root.resolve(Layout.CAPTURE_ADDON).resolve("behavior_pack/blocks");
        List<Path> captures;
        try (Stream<Path> builds = Files.list(root.resolve(Layout.BDS))) {
            captures = builds.map(build -> build.resolve("addon")).filter(dir -> Files.exists(dir.resolve("creative_items.json.gz"))).sorted().toList();
        }
        for (Path capture : captures) {
            String build = capture.getParent().getFileName().toString();
            Map<String, Long> sent = new LinkedHashMap<>();
            try (Reader reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(capture.resolve("creative_items.json.gz"))), StandardCharsets.UTF_8)) {
                for (JsonElement e : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("items")) {
                    JsonObject item = e.getAsJsonObject();
                    if (item.has("block_runtime_id")) {
                        sent.putIfAbsent(item.get("id").getAsString(), item.get("block_runtime_id").getAsLong());
                    }
                }
            }
            for (NbtMap block : Nbt.read(capture.resolve("custom_block_properties.nbt")).getList("blocks", NbtType.COMPOUND)) {
                String name = block.getString("name");
                NbtMap properties = block.getCompound("properties");
                Path file = blocks.resolve(name.substring(name.indexOf(':') + 1) + ".json");
                if (!properties.containsKey("traits") || !sent.containsKey(name) || !Files.exists(file)) {
                    continue;
                }
                JsonObject traits = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("minecraft:block")
                    .getAsJsonObject("description").getAsJsonObject("traits");
                NbtMapBuilder states = NbtMap.builder();
                for (NbtMap property : properties.getList("properties", NbtType.COMPOUND, List.of())) {
                    states.put(property.getString("name"), ((NbtList<?>) property.get("enum")).getFirst());
                }
                List<String> added = new ArrayList<>();
                for (Map.Entry<String, JsonElement> trait : traits.entrySet()) {
                    for (JsonElement value : trait.getValue().getAsJsonObject().getAsJsonArray("enabled_states")) {
                        for (State state : table.get(trait.getKey()).get(value.getAsString())) {
                            states.put(state.name(), state.first());
                            added.add(state.name());
                        }
                    }
                }
                long hash = Integer.toUnsignedLong(BlockHashes.hash(NbtMap.builder().putString("name", name).putCompound("states", states.build()).build()));
                if (hash != sent.get(name)) {
                    throw new IllegalStateException("BDS " + build + " sent hash " + sent.get(name) + " for the default state of " + name
                        + ", the trait states' first values give " + hash + " (" + states.build() + "): data/model/mappings.json traitStates is wrong");
                }
                for (String state : added) {
                    verified.putIfAbsent(state, "verified (name and default): BDS " + build + " sent hash " + hash + " for the default state of " + name);
                }
            }
        }
        return verified;
    }
}
