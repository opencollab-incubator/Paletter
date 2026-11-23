package dev.opencollab.paletter.tools.blocks;

import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.internal.vanilla.BlockDefinition;
import dev.opencollab.paletter.internal.vanilla.BlockDefinitions;
import dev.opencollab.paletter.internal.vanilla.StateValues;
import org.cloudburstmc.nbt.NbtMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The vanilla block definitions behind palettes: per block its properties in the order Bedrock enumerates its states in,
 * the values of each in Bedrock's order, and the default state. Read from a palette and checked against it: the
 * definitions, put through {@link BlockDefinitions#derive} (what the library does with the generated code), must give the
 * palette again, state for state and in the same order, with the same defaults. Whatever a palette does that the
 * definitions cannot say (a block that is not the full cartesian product of its values, states in an order no property
 * order gives, blocks out of name hash order) fails with what it is, never with a guess.
 */
final class BlockModel {
    private BlockModel() {
    }

    /**
     * @return the definitions of a palette, checked against it
     * @throws IllegalStateException for whatever the definitions cannot say, with the version in the message
     */
    static BlockDefinitions definitions(String version, BlockDefinitions.Palette palette) {
        try {
            return read(version, palette);
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            // A default that is no state of its block, a block of more states than an int counts, ...
            throw new IllegalStateException(version + ": " + e.getMessage(), e);
        }
    }

    private static BlockDefinitions read(String version, BlockDefinitions.Palette palette) {
        Map<String, List<NbtMap>> blocks = new LinkedHashMap<>();
        String previous = null;
        for (NbtMap state : palette.states()) {
            String name = BlockStates.name(state);
            if (!name.equals(previous) && blocks.containsKey(name)) {
                throw new IllegalStateException(version + ": the states of " + name + " are not one run (again after " + previous
                    + "): the definitions give a block's states together");
            }
            blocks.computeIfAbsent(name, n -> new ArrayList<>()).add(BlockStates.states(state));
            previous = name;
        }
        BlockDefinitions definitions = new BlockDefinitions();
        blocks.forEach((name, states) -> {
            List<StateValues> properties = properties(version, name, states);
            BlockDefinitions.Defined defined = definitions.block(name, properties.toArray(StateValues[]::new));
            NbtMap defaultState = palette.defaults().get(name);
            if (defaultState != null) {
                if (!BlockStates.name(defaultState).equals(name)) {
                    throw new IllegalStateException(version + ": the default of " + name + " is a state of " + BlockStates.name(defaultState));
                }
                NbtMap values = BlockStates.states(defaultState);
                for (StateValues p : properties) {
                    if (!values.containsKey(p.name())) {
                        throw new IllegalStateException(version + ": the default of " + name + " lacks " + p.name());
                    }
                }
                defined.defaultState(properties.stream().map(p -> values.get(p.name())).toArray());
            }
        });
        for (String name : palette.defaults().keySet()) {
            if (!blocks.containsKey(name)) {
                throw new IllegalStateException(version + ": a default for " + name + ", which has no states");
            }
        }
        check(version, palette, definitions.derive());
        return definitions;
    }

    /**
     * The properties of one block, the slowest changing first: the states must be an odometer over them, the last one
     * changing fastest. Each property's period (how many states pass before it first changes) gives its place; two
     * with the same period (a property of one value) would have no place of their own.
     */
    static List<StateValues> properties(String version, String name, List<NbtMap> states) {
        Set<String> keys = states.getFirst().keySet();
        for (NbtMap state : states) {
            if (!state.keySet().equals(keys)) {
                throw new IllegalStateException(version + ": the states of " + name + " have different keys: " + keys + " and " + state.keySet());
            }
        }
        Map<String, Integer> periods = new LinkedHashMap<>();
        Map<String, List<Object>> values = new LinkedHashMap<>();
        for (String key : keys) {
            int period = 1;
            while (period < states.size() && states.get(period).get(key).equals(states.getFirst().get(key))) {
                period++;
            }
            periods.put(key, period);
            List<Object> list = new ArrayList<>();
            for (NbtMap state : states) {
                Object value = state.get(key);
                if (!list.contains(value)) {
                    list.add(value);
                }
            }
            values.put(key, list);
        }
        // A property of one value never changes, so its place cannot change the states: such properties go first, by name
        List<String> order = new ArrayList<>(keys);
        order.sort(Comparator.comparing((String key) -> values.get(key).size() == 1 ? 0 : 1)
            .thenComparing(periods::get, Comparator.reverseOrder()).thenComparing(Comparator.naturalOrder()));
        for (int i = 1; i < order.size(); i++) {
            if (values.get(order.get(i)).size() > 1 && periods.get(order.get(i)).equals(periods.get(order.get(i - 1)))) {
                throw new IllegalStateException(version + ": the property order of " + name + " is ambiguous: " + order.get(i - 1) + " and "
                    + order.get(i) + " change at the same rate (" + values.get(order.get(i - 1)) + ", " + values.get(order.get(i)) + ")");
            }
        }
        List<StateValues> properties = new ArrayList<>();
        for (String key : order) {
            properties.add(values(version, name, key, values.get(key)));
        }
        long product = 1;
        for (StateValues property : properties) {
            product *= property.size();
        }
        if (product != states.size()) {
            throw new IllegalStateException(version + ": " + name + " is not the full cartesian product of its values: " + states.size()
                + " states, " + product + " combinations of " + properties);
        }
        return properties;
    }

    /** The values of a property, in the order the states first show them. */
    static StateValues values(String version, String block, String name, List<Object> values) {
        if (values.stream().allMatch(v -> v instanceof Byte)) {
            if (!values.equals(List.of((byte) 0, (byte) 1))) {
                throw new IllegalStateException(version + ": " + block + "." + name + " has the byte values " + values
                    + ", not false then true: the definitions have no way to say that");
            }
            return StateValues.bool(name);
        }
        if (values.stream().allMatch(v -> v instanceof Integer)) {
            int[] ints = values.stream().mapToInt(v -> (Integer) v).toArray();
            boolean run = true;
            for (int i = 1; i < ints.length; i++) {
                run &= ints[i] == ints[0] + i;
            }
            return run ? StateValues.ints(name, ints[0], ints[ints.length - 1]) : StateValues.intList(name, ints);
        }
        if (values.stream().allMatch(v -> v instanceof String)) {
            return StateValues.strings(name, values.toArray(String[]::new));
        }
        throw new IllegalStateException(version + ": " + block + "." + name + " has values of another or of several types: " + values);
    }

    /** Fails on the first place where the derived palette is not the palette. */
    static void check(String version, BlockDefinitions.Palette palette, BlockDefinitions.Palette derived) {
        List<NbtMap> expected = palette.states();
        List<NbtMap> actual = derived.states();
        for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            NbtMap want = expected.get(i);
            NbtMap got = actual.get(i);
            if (!BlockStates.name(want).equals(BlockStates.name(got))) {
                throw new IllegalStateException(version + ": state " + i + " is of " + BlockStates.name(want) + ", the definitions give "
                    + BlockStates.name(got) + " there: the blocks are not ordered by the unsigned FNV-1 64 hash of their identifiers");
            }
            if (!BlockStates.states(want).equals(BlockStates.states(got))) {
                throw new IllegalStateException(version + ": state " + i + " of " + BlockStates.name(want) + " is " + BlockStates.states(want)
                    + ", the definitions give " + BlockStates.states(got) + ": not an odometer over the block's properties");
            }
            if (!new ArrayList<>(BlockStates.states(want).keySet()).equals(new ArrayList<>(BlockStates.states(got).keySet()))) {
                throw new IllegalStateException(version + ": the keys of state " + i + " of " + BlockStates.name(want) + " are in the order "
                    + BlockStates.states(want).keySet() + ", the definitions write them sorted");
            }
        }
        if (expected.size() != actual.size()) {
            throw new IllegalStateException(version + ": " + expected.size() + " states, the definitions give " + actual.size());
        }
        Map<String, NbtMap> defaults = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (NbtMap state : expected) {
            String name = BlockStates.name(state);
            NbtMap given = palette.defaults().get(name);
            // A default that is the first state says what no default says
            if (seen.add(name) && given != null && !given.equals(state)) {
                defaults.put(name, given);
            }
        }
        if (!defaults.equals(derived.defaults())) {
            throw new IllegalStateException(version + ": the defaults " + defaults + " are not what the definitions give: " + derived.defaults());
        }
    }

    /** @return the definitions of each version, in the order given, each checked against its palette */
    static Map<String, BlockDefinitions> versions(Map<String, BlockDefinitions.Palette> palettes) {
        Map<String, BlockDefinitions> versions = new LinkedHashMap<>();
        palettes.forEach((version, palette) -> versions.put(version, definitions(version, palette)));
        return versions;
    }

    /** @return the blocks of a version, by identifier */
    static List<BlockDefinition> sorted(BlockDefinitions definitions) {
        List<BlockDefinition> blocks = new ArrayList<>(definitions.blocks());
        blocks.sort(Comparator.comparing(BlockDefinition::identifier));
        return blocks;
    }
}
