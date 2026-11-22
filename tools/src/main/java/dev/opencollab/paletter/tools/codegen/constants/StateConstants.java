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
     * The state values of the bundled data.
     *
     * @param all     every state name with its values (release and preview), in order of first appearance over the blocks
     *                sorted by identifier, so the palette order of blocks does not matter
     * @param release the same for the release blocks only
     * @param usage   how many blocks have each state (preview blocks count only for states the release lacks)
     */
    private record StateValues(Map<String, List<Object>> all, Map<String, List<Object>> release, Map<String, Integer> usage) {
    }

    /**
     * @param previewLayouts the blocks of the bundled preview (empty without one): states and enum values only they have are
     *                       marked with the preview and kept out of the history
     */
    StateModel states(List<BlockLayout> layouts, List<BlockLayout> previewLayouts) {
        StateValues values = collectValues(layouts, previewLayouts);
        values.all().keySet().stream().filter(name -> g.names.hand().contains("enums " + name)).forEach(name -> g.usedNames.add("enums " + name));
        StateModel model = new StateModel();
        Map<String, String> enumOfState = historyEnums(model);
        stateConstants(values, model);
        assignEnums(values, model, enumOfState);
        orderValues(values, model);
        deprecateGoneValues(values, model);
        deprecateGoneStates(model);
        writeHistory(model);
        return model;
    }

    private static StateValues collectValues(List<BlockLayout> layouts, List<BlockLayout> previewLayouts) {
        Map<String, List<Object>> values = new TreeMap<>();
        Map<String, Integer> usage = new HashMap<>();
        for (BlockLayout layout : byIdentifier(layouts)) {
            for (int p = 0; p < layout.properties().size(); p++) {
                addValues(values, layout, p);
                usage.merge(layout.properties().get(p), 1, Integer::sum);
            }
        }
        Map<String, List<Object>> releaseValues = new TreeMap<>();
        values.forEach((name, list) -> releaseValues.put(name, List.copyOf(list)));
        for (BlockLayout layout : byIdentifier(previewLayouts)) {
            for (int p = 0; p < layout.properties().size(); p++) {
                String name = layout.properties().get(p);
                addValues(values, layout, p);
                if (!releaseValues.containsKey(name)) {
                    usage.merge(name, 1, Integer::sum);
                }
            }
        }
        return new StateValues(values, releaseValues, usage);
    }

    private static List<BlockLayout> byIdentifier(List<BlockLayout> layouts) {
        List<BlockLayout> sorted = new ArrayList<>(layouts);
        sorted.sort(Comparator.comparing(BlockLayout::identifier));
        return sorted;
    }

    private static void addValues(Map<String, List<Object>> values, BlockLayout layout, int property) {
        List<Object> list = values.computeIfAbsent(layout.properties().get(property), k -> new ArrayList<>());
        for (Object value : layout.values().get(property)) {
            if (!list.contains(value)) {
                list.add(value);
            }
        }
    }

    /** Puts the enums of the history, with their values, into the model; returns the enum of each state they list. */
    private Map<String, String> historyEnums(StateModel model) {
        Map<String, String> enumOfState = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : ConstantsHistory.object(g.history, "enums").entrySet()) {
            JsonObject old = ConstantsHistory.entry(entry, "enums");
            EnumModel enumModel = new EnumModel(entry.getKey(), ConstantsHistory.required(old, "since", "enums." + entry.getKey()));
            if (!old.has("states") || !old.has("values")) {
                throw new IllegalStateException(HISTORY + ": enums." + entry.getKey() + " lacks \"states\" or \"values\"");
            }
            for (JsonElement state : old.getAsJsonArray("states")) {
                enumOfState.put(state.getAsString(), entry.getKey());
            }
            for (Map.Entry<String, JsonElement> value : old.getAsJsonObject("values").entrySet()) {
                JsonObject v = value.getValue().getAsJsonObject();
                String where = "enums." + entry.getKey() + "." + value.getKey();
                enumModel.values.put(ConstantsHistory.required(v, "value", where), new EnumValue(value.getKey(), ConstantsHistory.required(v, "value", where),
                    ConstantsHistory.required(v, "since", where), v.has("deprecated") ? v.get("deprecated").getAsString() : null, null));
            }
            model.enums.put(enumModel.name, enumModel);
        }
        return enumOfState;
    }

    /** One constant per state of the data, with its kind checked against the history. */
    private void stateConstants(StateValues values, StateModel model) {
        Map<String, String> overrides = g.names.states();
        JsonObject previousStates = ConstantsHistory.object(g.history, "states");
        Map<String, String> constantOf = new TreeMap<>();
        for (Map.Entry<String, List<Object>> entry : values.all().entrySet()) {
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
                values.release().getOrDefault(name, list).forEach(v -> ints.add((Integer) v));
                ints.sort(null);
            }
            StateConstant state = new StateConstant(constant, name, kind, List.copyOf(ints),
                old != null ? ConstantsHistory.required(old, "since", "states." + constant) : g.version);
            state.blocks = values.usage().get(name);
            state.preview = old == null && !values.release().containsKey(name) ? g.previewName() : null;
            if (old == null && state.preview == null) {
                g.added.add("BedrockStates." + constant + " = " + name);
            }
            model.constants.put(constant, state);
            model.byName.put(name, state);
        }
    }

    /** String states keep their enum, join an enum with the same values, or get a new one. */
    private void assignEnums(StateValues values, StateModel model, Map<String, String> enumOfState) {
        Map<String, String> enumOverrides = g.names.enums();
        Map<List<String>, List<String>> groups = new LinkedHashMap<>();
        for (StateConstant state : model.constants.values()) {
            if (state.kind != Kind.ENUM) {
                continue;
            }
            String existing = enumOfState.get(state.name);
            if (existing != null) {
                state.enumName = existing;
                continue;
            }
            Set<String> sorted = new TreeSet<>();
            values.all().get(state.name).forEach(v -> sorted.add((String) v));
            for (EnumModel enumModel : model.enums.values()) {
                Set<String> enumValues = new TreeSet<>();
                enumModel.values.values().stream().filter(v -> v.deprecated() == null).forEach(v -> enumValues.add(v.value()));
                if (enumValues.equals(sorted)) {
                    state.enumName = enumModel.name;
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
    }

    /** Enum values: the existing ones in their order, then new values in order of first appearance. */
    private void orderValues(StateValues values, StateModel model) {
        JsonObject previousEnums = ConstantsHistory.object(g.history, "enums");
        for (StateConstant state : model.constants.values()) {
            if (state.kind != Kind.ENUM) {
                continue;
            }
            EnumModel enumModel = model.enums.get(state.enumName);
            enumModel.states.add(state.name);
            for (Object value : values.all().get(state.name)) {
                EnumValue existing = enumModel.values.get((String) value);
                if (existing == null) {
                    String constant = ((String) value).replaceAll("[^A-Za-z0-9]+", "_").toUpperCase(Locale.ROOT);
                    if (!CONSTANT.matcher(constant).matches()) {
                        throw new IllegalStateException("Value " + value + " of " + state.name + " is no Java constant name");
                    }
                    // A value no state of the enum has in the release is the preview's
                    boolean previewOnly = g.preview != null && values.all().keySet().stream()
                        .filter(other -> model.byName.containsKey(other) && enumModel.name.equals(model.byName.get(other).enumName))
                        .noneMatch(other -> values.release().getOrDefault(other, List.of()).contains(value));
                    enumModel.values.put((String) value, new EnumValue(constant, (String) value, g.version, null, previewOnly ? g.previewName() : null));
                    if (previousEnums.has(enumModel.name) && !previewOnly) {
                        g.added.add(enumModel.name + "." + constant);
                    }
                } else if (existing.deprecated() != null) {
                    enumModel.values.put((String) value, new EnumValue(existing.constant(), existing.value(), existing.since(), null, null));
                }
            }
        }
    }

    /** Values no state has anymore are deprecated; the names in an enum must be unique. */
    private void deprecateGoneValues(StateValues values, StateModel model) {
        for (EnumModel enumModel : model.enums.values()) {
            Set<String> current = new LinkedHashSet<>();
            for (String state : enumModel.states) {
                values.all().getOrDefault(state, List.of()).forEach(v -> current.add((String) v));
            }
            Set<String> constants = new LinkedHashSet<>();
            for (Map.Entry<String, EnumValue> entry : new ArrayList<>(enumModel.values.entrySet())) {
                EnumValue value = entry.getValue();
                if (!current.contains(value.value()) && value.deprecated() == null) {
                    enumModel.values.put(entry.getKey(), new EnumValue(value.constant(), value.value(), value.since(), g.version, null));
                    g.deprecated.add(enumModel.name + "." + value.constant() + " (no state has " + value.value() + " anymore)");
                }
                if (!constants.add(value.constant())) {
                    throw new IllegalStateException("Duplicate constant " + enumModel.name + "." + value.constant());
                }
            }
        }
    }

    /** States the data no longer has stay, deprecated. */
    private void deprecateGoneStates(StateModel model) {
        JsonObject previousStates = ConstantsHistory.object(g.history, "states");
        for (Map.Entry<String, JsonElement> entry : new TreeMap<>(previousStates.asMap()).entrySet()) {
            if (model.constants.containsKey(entry.getKey())) {
                continue;
            }
            JsonObject old = ConstantsHistory.entry(entry, "states");
            String where = "states." + entry.getKey();
            String name = ConstantsHistory.required(old, "name", where);
            Kind kind;
            try {
                kind = Kind.valueOf(ConstantsHistory.required(old, "kind", where).toUpperCase(Locale.ROOT));
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
    }

    /** The history entries of the states and enums; what only the preview has stays out. */
    private void writeHistory(StateModel model) {
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
        for (EnumModel enumModel : model.enums.values()) {
            if (enumModel.preview != null) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("since", enumModel.since);
            JsonArray states = new JsonArray();
            // States of deprecated constants keep their enum
            Set<String> all = new TreeSet<>(enumModel.states);
            model.constants.values().stream().filter(s -> enumModel.name.equals(s.enumName) && s.preview == null).forEach(s -> all.add(s.name));
            all.removeIf(state -> model.byName.containsKey(state) && model.byName.get(state).preview != null);
            all.forEach(states::add);
            entry.add("states", states);
            JsonObject valuesOut = new JsonObject();
            for (EnumValue value : enumModel.values.values()) {
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
            enumsOut.add(enumModel.name, entry);
        }
        g.newHistory.add("enums", enumsOut);
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
