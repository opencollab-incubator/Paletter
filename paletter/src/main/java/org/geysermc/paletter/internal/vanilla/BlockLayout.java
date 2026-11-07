package org.geysermc.paletter.internal.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * How the states of one block type are laid out in a palette: its properties (sorted by name), the values of each in
 * order of first appearance, and how a combination of value indices maps to a state's position.
 * <p>
 * A <em>combination</em> is the mixed radix number {@code sum(index(p) * radix(p))} with the first property varying
 * fastest. Bedrock enumerates the states of every block as the full cartesian product of its values, in some property
 * order, so the offset of a state from the first state of its block is {@code sum(index(p) * stride(p))}
 * ({@link #strides()}). Blocks for which that does not hold (not a full product, or not in a stride order) get a
 * lookup table instead ({@link #problem()} says why).
 * <p>
 * Shared by the runtime ({@code org.geysermc.paletter.vanilla}) and {@code :codegen:generateConstants}, which
 * verifies the bundled data and reports blocks needing a table. Not API.
 */
public final class BlockLayout {
    private final String identifier;
    private final int first;
    private final int count;
    private final List<String> properties;
    private final List<List<Object>> values;
    private final int[] radix;
    private final int[] sizes;
    private final int combinations;
    private final int @Nullable [] strides;
    private final int @Nullable [] table;
    private final int @Nullable [] combinationOf;
    private final @Nullable String problem;

    private BlockLayout(String identifier, int first, List<NbtMap> states) {
        this.identifier = identifier;
        this.first = first;
        this.count = states.size();
        Set<String> names = new TreeSet<>();
        for (NbtMap state : states) {
            names.addAll(state.keySet());
        }
        this.properties = List.copyOf(names);
        int size = properties.size();
        List<List<Object>> values = new ArrayList<>(size);
        for (int p = 0; p < size; p++) {
            values.add(new ArrayList<>());
        }
        int[][] indices = new int[count][size];
        for (int o = 0; o < count; o++) {
            NbtMap state = states.get(o);
            for (int p = 0; p < size; p++) {
                Object value = state.get(properties.get(p));
                if (value == null) {
                    throw new IllegalArgumentException(identifier + ": state " + state + " lacks " + properties.get(p)
                        + ", which other states of the block have");
                }
                List<Object> list = values.get(p);
                int index = list.indexOf(value);
                if (index == -1) {
                    index = list.size();
                    list.add(value);
                }
                indices[o][p] = index;
            }
        }
        List<List<Object>> frozen = new ArrayList<>(size);
        values.forEach(list -> frozen.add(List.copyOf(list)));
        this.values = List.copyOf(frozen);

        this.radix = new int[size];
        this.sizes = new int[size];
        long combinations = 1;
        for (int p = 0; p < size; p++) {
            radix[p] = (int) combinations;
            sizes[p] = values.get(p).size();
            combinations *= sizes[p];
            if (combinations > 1 << 24) {
                throw new IllegalArgumentException(identifier + " has more than 2^24 state combinations");
            }
        }
        this.combinations = (int) combinations;

        int[] strides = strides(indices, size);
        String problem = null;
        if (combinations != count) {
            problem = count + " states, but its values have " + combinations + " combinations: not a full product";
        } else if (strides == null) {
            problem = "a full product, but not in a stride order";
        }
        if (problem == null) {
            this.strides = strides;
            this.table = null;
            this.combinationOf = null;
        } else {
            this.strides = null;
            int[] table = new int[this.combinations];
            Arrays.fill(table, -1);
            int[] combinationOf = new int[count];
            for (int o = 0; o < count; o++) {
                int combination = 0;
                for (int p = 0; p < size; p++) {
                    combination += indices[o][p] * radix[p];
                }
                if (table[combination] != -1) {
                    throw new IllegalArgumentException(identifier + ": duplicate state " + states.get(o));
                }
                table[combination] = o;
                combinationOf[o] = combination;
            }
            this.table = table;
            this.combinationOf = combinationOf;
        }
        this.problem = problem;
    }

    /** Strides such that the offset of every state is sum(index * stride), or null if there are none. */
    private static int @Nullable [] strides(int[][] indices, int size) {
        int[] strides = new int[size];
        for (int p = 0; p < size; p++) {
            strides[p] = 0; // stays 0 for a property with a single value
            for (int o = 0; o < indices.length; o++) {
                if (indices[o][p] == 1) {
                    strides[p] = o;
                    break;
                }
            }
        }
        for (int o = 0; o < indices.length; o++) {
            int offset = 0;
            for (int p = 0; p < size; p++) {
                offset += indices[o][p] * strides[p];
            }
            if (offset != o) {
                return null;
            }
        }
        return strides;
    }

    /**
     * Splits a palette into block layouts.
     *
     * @param states block states as {@code {name, states}}, in palette order
     * @throws IllegalArgumentException if the states of a block are not contiguous, or a block has duplicate states or
     *                                  states lacking properties
     */
    public static @NonNull List<BlockLayout> of(@NonNull List<NbtMap> states) {
        List<BlockLayout> layouts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int start = 0;
        while (start < states.size()) {
            String name = states.get(start).getString("name");
            int end = start + 1;
            while (end < states.size() && states.get(end).getString("name").equals(name)) {
                end++;
            }
            if (!seen.add(name)) {
                throw new IllegalArgumentException("The states of " + name + " are not contiguous in the palette");
            }
            List<NbtMap> block = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                block.add(states.get(i).getCompound("states", NbtMap.EMPTY));
            }
            layouts.add(new BlockLayout(name, start, block));
            start = end;
        }
        return List.copyOf(layouts);
    }

    public @NonNull String identifier() {
        return identifier;
    }

    /** @return the palette position of the first state of the block */
    public int first() {
        return first;
    }

    /** @return the amount of states */
    public int count() {
        return count;
    }

    /** @return the property names, sorted */
    public @NonNull List<String> properties() {
        return properties;
    }

    /**
     * @return the values of each property as NBT values ({@code Byte}, {@code Integer} or {@code String}), in order of
     *         first appearance
     */
    public @NonNull List<List<Object>> values() {
        return values;
    }

    /** @return the radix of a property in a combination; the first property varies fastest */
    public int radix(int property) {
        return radix[property];
    }

    /** @return the amount of value combinations: {@link #count()} if the states are a full product */
    public int combinations() {
        return combinations;
    }

    /** @return the offset step of each property's value index, or null if the block needs a table */
    public int @Nullable [] strides() {
        return strides == null ? null : strides.clone();
    }

    /** @return true if offsets are computed from strides, false if the block uses a lookup table */
    public boolean arithmetic() {
        return strides != null;
    }

    /** @return why the block needs a lookup table, or null if its offsets are arithmetic */
    public @Nullable String problem() {
        return problem;
    }

    /** @return the offset of a combination from the first state, or -1 if there is no such state */
    public int offset(int combination) {
        if (table != null) {
            return table[combination];
        }
        int offset = 0;
        for (int p = 0; p < radix.length; p++) {
            offset += ((combination / radix[p]) % sizes[p]) * strides[p];
        }
        return offset;
    }

    /** @return the combination of the state at an offset from the first state */
    public int combination(int offset) {
        if (combinationOf != null) {
            return combinationOf[offset];
        }
        int combination = 0;
        for (int p = 0; p < radix.length; p++) {
            if (strides[p] != 0) {
                combination += ((offset / strides[p]) % sizes[p]) * radix[p];
            }
        }
        return combination;
    }

    @Override
    public String toString() {
        return "BlockLayout{" + identifier + ", " + count + " states, " + properties + (problem == null ? "" : ", table: " + problem) + "}";
    }
}
