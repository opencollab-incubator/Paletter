package org.geysermc.paletter.tools.whitebox;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.tools.Layout;
import org.geysermc.paletter.tools.data.Nbt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * {@code whitebox-coverage}: what the code of a build can send ({@code whitebox.json}) that none of the build's captures
 * shows. Writes {@code data/bds/<version>/whitebox-coverage.md}, the list of gaps:
 * <ul>
 *     <li>components that are sent over the network and were never captured;</li>
 *     <li>keys and string values a writer mentions that no capture of its component has;</li>
 *     <li>keys a writer mentions that every capture has with the same value, so a branch of the writer was never seen.</li>
 * </ul>
 * The white-box facts never become data: a gap is closed by a capture (a new variant of the spec add-on, a format sweep
 * entry, a probe add-on), and only then does the component model learn the shape. A literal that the code mentions and
 * BDS never sends, or a value that cannot vary, stays in the list.
 */
public final class WhiteboxCoverage {
    public static final String FILE = "whitebox-coverage.md";
    /** The name the block definition around the components is captured under. */
    static final String BLOCK_ENVELOPE = "block:envelope";

    /** Something the code can send that no capture shows: about what, and what is missing. */
    public record Gap(String subject, String missing) {
    }

    /**
     * What captures show, by wire name ({@code item:minecraft:durability}, {@code item:item_properties},
     * {@code block:minecraft:geometry}, {@code trait:minecraft:connection}, {@link #BLOCK_ENVELOPE}): every key below it
     * with the values seen for it. A key of a compound or list has no values.
     */
    public static final class Captured {
        private final Map<String, Map<String, Set<String>>> components = new TreeMap<>();

        /** Adds the component data of an item: {@code {components: {item_properties, item_tags, minecraft:...}}}. */
        public void addItem(NbtMap data) {
            NbtMap components = data.containsKey("components") ? data.getCompound("components") : data;
            components.forEach((name, value) -> add("item:" + name, value));
            // The icon is a component that the envelope carries inside item_properties
            components.getCompound("item_properties", NbtMap.EMPTY).forEach((name, value) -> {
                if (value instanceof NbtMap) {
                    add("item:" + name, value);
                }
            });
        }

        /** Adds the property data of a block: the components (also those of its permutations) and the rest around them. */
        public void addBlock(NbtMap properties) {
            properties.getCompound("components", NbtMap.EMPTY).forEach((name, value) -> add("block:" + name, value));
            Map<String, Object> envelope = new TreeMap<>(properties);
            envelope.put("components", NbtMap.EMPTY);
            List<NbtMap> permutations = new ArrayList<>();
            for (NbtMap permutation : properties.getList("permutations", NbtType.COMPOUND, List.of())) {
                permutation.getCompound("components", NbtMap.EMPTY).forEach((name, value) -> add("block:" + name, value));
                Map<String, Object> rest = new TreeMap<>(permutation);
                rest.put("components", NbtMap.EMPTY);
                permutations.add(NbtMap.fromMap(rest));
            }
            if (!permutations.isEmpty()) {
                envelope.put("permutations", permutations);
            }
            // Traits are captured one by one, under their names, and not as part of the envelope
            for (NbtMap trait : properties.getList("traits", NbtType.COMPOUND, List.of())) {
                add("trait:" + trait.getString("name"), trait);
            }
            envelope.put("traits", NbtMap.EMPTY);
            add(BLOCK_ENVELOPE, NbtMap.fromMap(envelope));
        }

        private void add(String component, Object value) {
            Map<String, Set<String>> keys = components.computeIfAbsent(component, name -> new TreeMap<>());
            if (value instanceof NbtMap map) {
                map.forEach((key, child) -> {
                    Set<String> values = keys.computeIfAbsent(key, k -> new TreeSet<>());
                    if (!(child instanceof NbtMap) && !(child instanceof List<?>)) {
                        values.add(String.valueOf(child));
                    }
                    add(component, child);
                });
            } else if (value instanceof List<?> list) {
                list.forEach(child -> add(component, child));
            } else if (value instanceof String text) {
                // A string in a list: an enum name or an identifier
                keys.computeIfAbsent(text, k -> new TreeSet<>());
            }
        }

        boolean has(String component) {
            return components.containsKey(component);
        }

        /** @return whether a capture of the component has the literal as a key or as a string value */
        boolean mentions(String component, String literal) {
            Map<String, Set<String>> keys = components.getOrDefault(component, Map.of());
            return keys.containsKey(literal) || keys.values().stream().anyMatch(values -> values.contains(literal));
        }

        /** @return the one value every capture has for a key of the component, or null if there are several or none */
        String onlyValue(String component, String key) {
            Set<String> values = components.getOrDefault(component, Map.of()).getOrDefault(key, Set.of());
            return values.size() == 1 ? values.iterator().next() : null;
        }

        Set<String> names() {
            return components.keySet();
        }
    }

    private WhiteboxCoverage() {
    }

    /** Writes the coverage report of a build and returns its gaps. */
    public static List<Gap> write(Path root, String version) throws IOException {
        List<Gap> gaps = gaps(root, version);
        Files.writeString(root.resolve(Layout.BDS).resolve(version).resolve(FILE), report(version, gaps));
        return gaps;
    }

    /** @return the report that the committed facts and captures of a build give */
    public static String report(Path root, String version) throws IOException {
        return report(version, gaps(root, version));
    }

    private static List<Gap> gaps(Path root, String version) throws IOException {
        Path build = root.resolve(Layout.BDS).resolve(version);
        JsonObject mappings = JsonParser.parseString(Files.readString(root.resolve(Layout.MODEL).resolve("mappings.json"))).getAsJsonObject();
        return gaps(Whitebox.parse(build.resolve(Whitebox.FILE)), captured(build), mappings);
    }

    /** Everything the captures of a build show: the add-on captures and the data-driven content of the vanilla capture. */
    public static Captured captured(Path build) throws IOException {
        Captured captured = new Captured();
        List<Path> directories;
        try (Stream<Path> list = Files.list(build)) {
            directories = list.filter(Files::isDirectory).sorted().toList();
        }
        for (Path directory : directories) {
            Path blocks = directory.resolve("custom_block_properties.nbt");
            if (Files.exists(blocks)) {
                for (NbtMap block : Nbt.read(blocks).getList("blocks", NbtType.COMPOUND, List.of())) {
                    captured.addBlock(block.getCompound("properties", block));
                }
            }
            Path items = directory.resolve("custom_item_registry.nbt");
            if (Files.exists(items)) {
                for (NbtMap item : Nbt.read(items).getList("items", NbtType.COMPOUND, List.of())) {
                    captured.addItem(item.getCompound("data", NbtMap.EMPTY));
                }
            }
            Path vanillaBlocks = directory.resolve("raw_start_game_block_properties.nbt");
            if (Files.exists(vanillaBlocks)) {
                for (NbtMap block : Nbt.readGzip(vanillaBlocks).getList("blocks", NbtType.COMPOUND, List.of())) {
                    captured.addBlock(block.getCompound("properties", block));
                }
            }
            Path vanillaItems = directory.resolve("item_components.nbt");
            if (Files.exists(vanillaItems)) {
                Nbt.readGzip(vanillaItems).forEach((name, data) -> {
                    if (data instanceof NbtMap map) {
                        captured.addItem(map);
                    }
                });
            }
        }
        return captured;
    }

    /**
     * @param mappings {@code data/model/mappings.json}, to say what the component model knows about an uncaptured component
     * @return the gaps, in the order of the report
     */
    public static List<Gap> gaps(JsonObject whitebox, Captured captured, JsonObject mappings) {
        List<Gap> gaps = new ArrayList<>();
        for (String family : List.of("item", "block")) {
            for (Map.Entry<String, JsonElement> entry : whitebox.getAsJsonObject(family + "Components").entrySet()) {
                JsonObject component = entry.getValue().getAsJsonObject();
                if (!component.has("network") || !component.get("network").getAsBoolean()) {
                    continue;
                }
                String wire = family + ":" + entry.getKey();
                String subject = family + " " + entry.getKey();
                if (!captured.has(wire)) {
                    gaps.add(new Gap(subject, "never captured (" + component.get("kind").getAsString() + writes(component) + "; " + model(mappings, wire) + ")"));
                } else {
                    literals(gaps, subject, component, captured, List.of(wire), true);
                }
            }
        }
        // A class without a name is matched by what it writes
        for (JsonElement element : whitebox.getAsJsonObject("itemClassesWithoutName").getAsJsonArray("sent")) {
            JsonObject component = element.getAsJsonObject();
            Set<String> writes = strings(component, "writes");
            String subject = "item class " + (component.has("class") ? component.get("class").getAsString() : "without a name")
                + (component.has("count") ? " (" + component.get("count").getAsInt() + " alike)" : "");
            List<String> matches = captured.names().stream()
                .filter(name -> name.startsWith("item:") && !writes.isEmpty() && writes.stream().allMatch(key -> captured.mentions(name, key))).toList();
            if (writes.isEmpty()) {
                gaps.add(new Gap(subject, "sent through reflection: without a name or keys it cannot be matched to a capture"));
            } else if (matches.isEmpty()) {
                gaps.add(new Gap(subject, "no captured item component has " + writes));
            } else {
                literals(gaps, subject + " (captured as " + String.join(", ", matches) + ")", component, captured, matches, true);
            }
        }
        for (Map.Entry<String, JsonElement> entry : whitebox.getAsJsonObject("traits").entrySet()) {
            String subject = "trait " + entry.getKey();
            if (!captured.has("trait:" + entry.getKey())) {
                gaps.add(new Gap(subject, "never captured"));
            } else {
                literals(gaps, subject, entry.getValue().getAsJsonObject(), captured, List.of("trait:" + entry.getKey()), true);
            }
        }
        JsonObject envelopes = whitebox.getAsJsonObject("envelopes");
        // The values an envelope writer hands on from helpers are tables of names (block archetypes), not keys
        literals(gaps, "item envelope", envelopes.getAsJsonObject("item"), captured, List.of("item:item_properties", "item:item_tags"), false);
        literals(gaps, "block envelope", envelopes.getAsJsonObject("block"), captured, List.of(BLOCK_ENVELOPE), false);
        return gaps;
    }

    /**
     * Adds a gap for every key or string value the entry's writers mention that no capture has, and for every key that
     * all captures have with one and the same value.
     *
     * @param places the captured wire names to look under
     * @param nested whether to look at what the functions that the writer calls mention, too
     */
    private static void literals(List<Gap> gaps, String subject, JsonObject entry, Captured captured, List<String> places, boolean nested) {
        for (String list : nested ? List.of("writes", "writesExtra", "schema", "writesNested") : List.of("writes")) {
            for (String literal : strings(entry, list)) {
                // The wire names themselves: item_properties and item_tags are keys of the item envelope
                boolean place = places.stream().anyMatch(name -> name.endsWith(":" + literal));
                if (!place && places.stream().noneMatch(name -> captured.mentions(name, literal))) {
                    gaps.add(new Gap(subject, "`" + literal + "` never captured" + (list.equals("writesNested") ? " (mentioned by a function the writer calls)"
                        : list.equals("schema") ? " (reflection schema)" : "")));
                    continue;
                }
                Set<String> values = new TreeSet<>();
                boolean several = false;
                for (String name : places) {
                    String only = captured.onlyValue(name, literal);
                    several |= only == null && captured.components.getOrDefault(name, Map.of()).getOrDefault(literal, Set.of()).size() > 1;
                    if (only != null) {
                        values.add(only);
                    }
                }
                if (!several && values.size() == 1 && !list.equals("writesNested")) {
                    gaps.add(new Gap(subject, "`" + literal + "` only ever captured as `" + values.iterator().next() + "`"));
                }
            }
        }
    }

    private static String writes(JsonObject component) {
        Set<String> writes = strings(component, "writes");
        writes.addAll(strings(component, "writesExtra"));
        return writes.isEmpty() ? "" : ", writes " + writes;
    }

    /** What the component model says about a component. */
    private static String model(JsonObject mappings, String component) {
        JsonObject known = mappings.getAsJsonObject("components").getAsJsonObject(component);
        if (known == null) {
            return "not in the component model";
        }
        String status = known.has("status") ? known.get("status").getAsString() : "unknown";
        return "component model: " + status + (known.has("baseRejected") ? ", BDS says: " + known.get("baseRejected").getAsString() : "");
    }

    private static Set<String> strings(JsonObject object, String list) {
        Set<String> strings = new TreeSet<>();
        if (object.has(list)) {
            object.getAsJsonArray(list).forEach(element -> strings.add(element.getAsString()));
        }
        return strings;
    }

    static String report(String version, List<Gap> gaps) {
        StringBuilder report = new StringBuilder("<!-- Generated by `whitebox-coverage` (tools, WhiteboxCoverage) from whitebox.json and the captures of BDS "
            + version + ". Do not edit: run the command again. -->\n");
        report.append("# What the code of BDS ").append(version).append(" can send that no capture shows\n\n");
        report.append(gaps.size()).append(gaps.size() == 1 ? " gap" : " gaps").append(". Each closes with a capture that shows it: a variant of the spec add-on, a "
            + "format sweep entry, or a probe add-on. A literal that the code mentions and BDS never sends, or a value that cannot vary, stays listed.\n");
        String subject = null;
        for (Gap gap : gaps) {
            if (!gap.subject().equals(subject)) {
                subject = gap.subject();
                report.append("\n## ").append(subject).append("\n\n");
            }
            report.append("- ").append(gap.missing()).append('\n');
        }
        return report.toString();
    }
}
