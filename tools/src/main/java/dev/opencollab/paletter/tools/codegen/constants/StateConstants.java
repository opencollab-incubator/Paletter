package dev.opencollab.paletter.tools.codegen.constants;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.opencollab.paletter.internal.vanilla.BlockLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import static dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator.CONSTANT;
import static dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator.CLASS;
import static dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator.HISTORY;
import static dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator.addName;
import static dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator.constantName;

/** The constants of {@code BedrockStates} and the state enums, with the history entries for both. */
final class StateConstants {
    private static final Set<String> DIRECTIONS = Set.of("north", "south", "east", "west", "up", "down");

    private final Generation g;

    StateConstants(Generation g) {
        this.g = g;
    }

    enum Kind { BOOL, INT, ENUM }

    /** A state constant. */
    static final class StateConstant {
        final String constant;
        final String name;
        final Kind kind;
        final List<Integer> ints;
        String enumName;
        final String since;
        String deprecated;
        String replacement;
        int blocks;
        /** The bundled preview that alone has the state ({@code @SincePreview}; not in the history), or null. */
        String preview;

        StateConstant(String constant, String name, Kind kind, List<Integer> ints, String since) {
            this.constant = constant;
            this.name = name;
            this.kind = kind;
            this.ints = ints;
            this.since = since;
        }
    }

    /** A generated enum. */
    static final class EnumModel {
        final String name;
        final String since;
        /** The bundled preview that alone has the enum's states, or null. */
        String preview;
        final Map<String, EnumValue> values = new LinkedHashMap<>();
        final Set<String> states = new TreeSet<>();

        EnumModel(String name, String since) {
            this.name = name;
            this.since = since;
        }
    }

    /**
     * @param preview the bundled preview that alone has the value ({@code @SincePreview}; not in the history), or null
     */
    record EnumValue(String constant, String value, String since, String deprecated, String preview) {
    }

    static final class StateModel {
        final Map<String, StateConstant> constants = new TreeMap<>();
        final Map<String, StateConstant> byName = new HashMap<>();
        final Map<String, EnumModel> enums = new TreeMap<>();
    }

    /**
     * @param previewLayouts the blocks of the bundled preview (empty without one): states and enum values only they have are
     *                       marked with the preview and kept out of the history
     */
    StateModel states(List<BlockLayout> layouts, List<BlockLayout> previewLayouts) {
        // Every state name with its values, in order of first appearance over the blocks sorted by identifier (so the
        // palette order of blocks does not matter), and how many blocks have it
        Map<String, List<Object>> values = new TreeMap<>();
        Map<String, Integer> usage = new HashMap<>();
        List<BlockLayout> byIdentifier = new ArrayList<>(layouts);
        byIdentifier.sort(Comparator.comparing(BlockLayout::identifier));
        for (BlockLayout layout : byIdentifier) {
            for (int p = 0; p < layout.properties().size(); p++) {
                List<Object> list = values.computeIfAbsent(layout.properties().get(p), k -> new ArrayList<>());
                for (Object value : layout.values().get(p)) {
                    if (!list.contains(value)) {
                        list.add(value);
                    }
                }
                usage.merge(layout.properties().get(p), 1, Integer::sum);
            }
        }
        Map<String, List<Object>> releaseValues = new TreeMap<>();
        values.forEach((name, list) -> releaseValues.put(name, List.copyOf(list)));
        List<BlockLayout> previewByIdentifier = new ArrayList<>(previewLayouts);
        previewByIdentifier.sort(Comparator.comparing(BlockLayout::identifier));
        for (BlockLayout layout : previewByIdentifier) {
            for (int p = 0; p < layout.properties().size(); p++) {
                String name = layout.properties().get(p);
                List<Object> list = values.computeIfAbsent(name, k -> new ArrayList<>());
                for (Object value : layout.values().get(p)) {
                    if (!list.contains(value)) {
                        list.add(value);
                    }
                }
                if (!releaseValues.containsKey(name)) {
                    usage.merge(name, 1, Integer::sum);
                }
            }
        }

        Map<String, String> overrides = g.names.states();
        Map<String, String> enumOverrides = g.names.enums();
        values.keySet().stream().filter(name -> g.names.hand().contains("enums " + name)).forEach(name -> g.usedNames.add("enums " + name));
        JsonObject previousStates = ConstantsHistory.object(g.history, "states");
        JsonObject previousEnums = ConstantsHistory.object(g.history, "enums");
        StateModel model = new StateModel();

        // Enums of the history keep their states and values
        Map<String, String> enumOfState = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : previousEnums.entrySet()) {
            JsonObject old = ConstantsHistory.entry(entry, "enums");
            EnumModel model1 = new EnumModel(entry.getKey(), ConstantsHistory.required(old, "since", "enums." + entry.getKey()));
            if (!old.has("states") || !old.has("values")) {
                throw new IllegalStateException(HISTORY + ": enums." + entry.getKey() + " lacks \"states\" or \"values\"");
            }
            for (JsonElement state : old.getAsJsonArray("states")) {
                enumOfState.put(state.getAsString(), entry.getKey());
            }
            for (Map.Entry<String, JsonElement> value : old.getAsJsonObject("values").entrySet()) {
                JsonObject v = value.getValue().getAsJsonObject();
                String where = "enums." + entry.getKey() + "." + value.getKey();
                model1.values.put(ConstantsHistory.required(v, "value", where), new EnumValue(value.getKey(), ConstantsHistory.required(v, "value", where),
                    ConstantsHistory.required(v, "since", where), v.has("deprecated") ? v.get("deprecated").getAsString() : null, null));
            }
            model.enums.put(model1.name, model1);
        }

        Map<String, String> constantOf = new TreeMap<>();
        for (Map.Entry<String, List<Object>> entry : values.entrySet()) {
            String name = entry.getKey();
            List<Object> list = entry.getValue();
            Kind kind;
            if (list.stream().allMatch(v -> v instanceof Byte b && (b == 0 || b == 1))) {
                kind = Kind.BOOL;
            } else if (list.stream().allMatch(v -> v instanceof Integer)) {
                kind = Kind.INT;
            } else if (list.stream().allMatch(v -> v instanceof String)) {
                kind = Kind.ENUM;
            } else {
                throw new IllegalStateException("State " + name + " has values of no supported kind: " + list);
            }
            String constant = overrides.getOrDefault(name, constantName(name));
            if (g.names.hand().contains("states " + name)) {
                g.usedNames.add("states " + name);
            }
            if (!CONSTANT.matcher(constant).matches()) {
                throw new IllegalStateException("State " + name + " gives the constant name " + constant + "; " + addName("states", name, "<NAME>"));
            }
            if (constantOf.containsKey(constant)) {
                throw new IllegalStateException("Duplicate constant BedrockStates." + constant + " for " + constantOf.get(constant) + " and " + name
                    + "; give one a name: " + addName("states", name, "<NAME>"));
            }
            constantOf.put(constant, name);
            JsonObject old = previousStates.has(constant) ? previousStates.getAsJsonObject(constant) : null;
            if (old != null && !ConstantsHistory.required(old, "kind", "states." + constant).equals(kind.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("BedrockStates." + constant + " (" + name + ") changed from " + old.get("kind").getAsString()
                    + " to " + kind + ", which would change the type of the constant; give the state a new name: " + addName("states", name, "<NAME>"));
            }
            List<Integer> ints = new ArrayList<>();
            if (kind == Kind.INT) {
                // A release state keeps the values of the release
                releaseValues.getOrDefault(name, list).forEach(v -> ints.add((Integer) v));
                ints.sort(null);
            }
            StateConstant state = new StateConstant(constant, name, kind, List.copyOf(ints),
                old != null ? ConstantsHistory.required(old, "since", "states." + constant) : g.version);
            state.blocks = usage.get(name);
            state.preview = old == null && !releaseValues.containsKey(name) ? g.previewName() : null;
            if (old == null && state.preview == null) {
                g.added.add("BedrockStates." + constant + " = " + name);
            }
            model.constants.put(constant, state);
            model.byName.put(name, state);
        }

        // String states: keep their enum, join an enum with the same values, or get a new one
        Map<List<String>, List<String>> groups = new LinkedHashMap<>();
        for (StateConstant state : model.constants.values()) {
            if (state.kind != Kind.ENUM) {
                continue;
            }
            List<String> stateValues = new ArrayList<>();
            values.get(state.name).forEach(v -> stateValues.add((String) v));
            String existing = enumOfState.get(state.name);
            if (existing != null) {
                state.enumName = existing;
                continue;
            }
            Set<String> sorted = new TreeSet<>(stateValues);
            for (EnumModel model1 : model.enums.values()) {
                Set<String> enumValues = new TreeSet<>();
                model1.values.values().stream().filter(v -> v.deprecated() == null).forEach(v -> enumValues.add(v.value()));
                if (enumValues.equals(sorted)) {
                    state.enumName = model1.name;
                    break;
                }
            }
            if (state.enumName == null) {
                groups.computeIfAbsent(List.copyOf(sorted), k -> new ArrayList<>()).add(state.name);
            }
        }
        for (List<String> states : groups.values()) {
            String enumName = null;
            for (String state : states) {
                if (enumOverrides.containsKey(state)) {
                    enumName = enumOverrides.get(state);
                }
            }
            String first = states.stream().sorted().findFirst().orElseThrow();
            if (enumName == null) {
                enumName = className(first);
            }
            if (!CLASS.matcher(enumName).matches()) {
                throw new IllegalStateException("Enum name " + enumName + " of " + states + " is no Java class name; " + addName("enums", first, "<ClassName>"));
            }
            if (model.enums.containsKey(enumName)) {
                throw new IllegalStateException("Duplicate enum " + enumName + " for " + states + " and " + model.enums.get(enumName).states
                    + "; " + addName("enums", first, "<ClassName>"));
            }
            EnumModel created = new EnumModel(enumName, g.version);
            created.preview = states.stream().allMatch(state -> model.byName.get(state).preview != null) ? g.previewName() : null;
            model.enums.put(enumName, created);
            for (String state : states) {
                model.byName.get(state).enumName = enumName;
            }
            g.added.add("enum " + enumName + " for " + states);
        }
        // Enum values: the existing ones in their order, then new values in order of first appearance
        for (StateConstant state : model.constants.values()) {
            if (state.kind != Kind.ENUM) {
                continue;
            }
            EnumModel model1 = model.enums.get(state.enumName);
            model1.states.add(state.name);
            for (Object value : values.get(state.name)) {
                EnumValue existing = model1.values.get((String) value);
                if (existing == null) {
                    String constant = ((String) value).replaceAll("[^A-Za-z0-9]+", "_").toUpperCase(java.util.Locale.ROOT);
                    if (!CONSTANT.matcher(constant).matches()) {
                        throw new IllegalStateException("Value " + value + " of " + state.name + " is no Java constant name");
                    }
                    // A value no state of the enum has in the release is the preview's
                    boolean previewOnly = g.preview != null && values.keySet().stream()
                        .filter(other -> model.byName.containsKey(other) && model1.name.equals(model.byName.get(other).enumName))
                        .noneMatch(other -> releaseValues.getOrDefault(other, List.of()).contains(value));
                    model1.values.put((String) value, new EnumValue(constant, (String) value, g.version, null, previewOnly ? g.previewName() : null));
                    if (previousEnums.has(model1.name) && !previewOnly) {
                        g.added.add(model1.name + "." + constant);
                    }
                } else if (existing.deprecated() != null) {
                    model1.values.put((String) value, new EnumValue(existing.constant(), existing.value(), existing.since(), null, null));
                }
            }
        }
        // Values no state has anymore are deprecated; the names in an enum must be unique
        for (EnumModel model1 : model.enums.values()) {
            Set<String> current = new LinkedHashSet<>();
            for (String state : model1.states) {
                values.getOrDefault(state, List.of()).forEach(v -> current.add((String) v));
            }
            Set<String> constants = new LinkedHashSet<>();
            for (Map.Entry<String, EnumValue> entry : new ArrayList<>(model1.values.entrySet())) {
                EnumValue value = entry.getValue();
                if (!current.contains(value.value()) && value.deprecated() == null) {
                    model1.values.put(entry.getKey(), new EnumValue(value.constant(), value.value(), value.since(), g.version, null));
                    g.deprecated.add(model1.name + "." + value.constant() + " (no state has " + value.value() + " anymore)");
                }
                if (!constants.add(value.constant())) {
                    throw new IllegalStateException("Duplicate constant " + model1.name + "." + value.constant());
                }
            }
        }

        // States the data no longer has stay, deprecated
        for (Map.Entry<String, JsonElement> entry : new TreeMap<>(previousStates.asMap()).entrySet()) {
            if (model.constants.containsKey(entry.getKey())) {
                continue;
            }
            JsonObject old = ConstantsHistory.entry(entry, "states");
            String where = "states." + entry.getKey();
            String name = ConstantsHistory.required(old, "name", where);
            Kind kind;
            try {
                kind = Kind.valueOf(ConstantsHistory.required(old, "kind", where).toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(HISTORY + ": " + where + " has the unknown kind " + old.get("kind") + " (bool, int or enum)");
            }
            if (kind == Kind.ENUM && !old.has("enum")) {
                throw new IllegalStateException(HISTORY + ": " + where + " is an enum state without \"enum\"");
            }
            List<Integer> ints = new ArrayList<>();
            if (old.has("values")) {
                for (String value : old.get("values").getAsString().split(",")) {
                    try {
                        ints.add(Integer.parseInt(value.strip()));
                    } catch (NumberFormatException e) {
                        throw new IllegalStateException(HISTORY + ": " + where + " has the value " + value + ", which is no integer");
                    }
                }
            }
            StateConstant state = new StateConstant(entry.getKey(), name, kind, ints, ConstantsHistory.required(old, "since", where));
            state.enumName = old.has("enum") ? old.get("enum").getAsString() : null;
            state.deprecated = old.has("deprecated") ? old.get("deprecated").getAsString() : g.version;
            StateConstant renamedTo = model.byName.get(name);
            if (renamedTo != null && renamedTo.kind == kind) {
                state.replacement = renamedTo.constant;
            }
            if (!old.has("deprecated")) {
                g.deprecated.add("BedrockStates." + entry.getKey() + " (" + name + (state.replacement != null ? ", now " + state.replacement : ", removed") + ")");
            }
            if (kind == Kind.ENUM && !model.enums.containsKey(state.enumName)) {
                throw new IllegalStateException("BedrockStates." + entry.getKey() + " needs enum " + state.enumName + ", which is not in the history");
            }
            model.constants.put(entry.getKey(), state);
        }

        JsonObject statesOut = new JsonObject();
        for (StateConstant state : model.constants.values()) {
            if (state.preview != null) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("name", state.name);
            entry.addProperty("kind", state.kind.name().toLowerCase(Locale.ROOT));
            entry.addProperty("since", state.since);
            if (state.kind == Kind.INT) {
                List<String> ints = new ArrayList<>();
                state.ints.forEach(value -> ints.add(String.valueOf(value)));
                entry.addProperty("values", String.join(",", ints));
            }
            if (state.enumName != null) {
                entry.addProperty("enum", state.enumName);
            }
            if (state.deprecated != null) {
                entry.addProperty("deprecated", state.deprecated);
                if (state.replacement != null) {
                    entry.addProperty("replacement", state.replacement);
                }
            }
            statesOut.add(state.constant, entry);
        }
        g.newHistory.add("states", statesOut);
        JsonObject enumsOut = new JsonObject();
        for (EnumModel model1 : model.enums.values()) {
            if (model1.preview != null) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("since", model1.since);
            JsonArray states = new JsonArray();
            // States of deprecated constants keep their enum
            Set<String> all = new TreeSet<>(model1.states);
            model.constants.values().stream().filter(s -> model1.name.equals(s.enumName) && s.preview == null).forEach(s -> all.add(s.name));
            all.removeIf(state -> model.byName.containsKey(state) && model.byName.get(state).preview != null);
            all.forEach(states::add);
            entry.add("states", states);
            JsonObject valuesOut = new JsonObject();
            for (EnumValue value : model1.values.values()) {
                if (value.preview() != null) {
                    continue;
                }
                JsonObject v = new JsonObject();
                v.addProperty("value", value.value());
                v.addProperty("since", value.since());
                if (value.deprecated() != null) {
                    v.addProperty("deprecated", value.deprecated());
                }
                valuesOut.add(value.constant(), v);
            }
            entry.add("values", valuesOut);
            enumsOut.add(model1.name, entry);
        }
        g.newHistory.add("enums", enumsOut);
        return model;
    }

    /** PascalCase of a state name without namespace and without a trailing direction ({@code _east}, ...). */
    static String className(String state) {
        String path = state.substring(state.indexOf(':') + 1);
        List<String> parts = new ArrayList<>(List.of(path.split("_")));
        if (parts.size() > 1 && DIRECTIONS.contains(parts.getLast())) {
            parts.removeLast();
        }
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return builder.toString();
    }
}
