package org.geysermc.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.internal.vanilla.BlockLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The properties and states of one block type. States are created once, on first use, so {@code with} only indexes an
 * array.
 */
final class BlockTypeLayout {
    final BedrockBlockType owner;
    final BedrockState<?>[] properties;
    final Object[][] values;
    final int[] radix;
    final int combinations;
    final int count;
    /** Canonical id of the first state in {@link VanillaIndex#blocks}, or -1 for custom blocks. */
    final int base;
    /** Vanilla: offsets from strides or a table. Custom: null, the offset is the combination. */
    private final @Nullable BlockLayout source;
    private final int defaultOffset;
    private volatile BedrockBlockState @Nullable [] byCombination;
    private BedrockBlockState[] byOffset;

    private BlockTypeLayout(BedrockBlockType owner, BedrockState<?>[] properties, Object[][] values, int base,
                            @Nullable BlockLayout source, int defaultOffset) {
        this.owner = owner;
        this.properties = properties;
        this.values = values;
        this.radix = new int[properties.length];
        int combinations = 1;
        for (int p = 0; p < properties.length; p++) {
            radix[p] = combinations;
            combinations *= values[p].length;
        }
        this.combinations = combinations;
        this.count = source == null ? combinations : source.count();
        this.base = base;
        this.source = source;
        this.defaultOffset = defaultOffset;
    }

    static BlockTypeLayout vanilla(BedrockBlockType owner, BlockLayout source, int base, int defaultOffset) {
        int size = source.properties().size();
        BedrockState<?>[] properties = new BedrockState<?>[size];
        Object[][] values = new Object[size][];
        for (int p = 0; p < size; p++) {
            List<Object> list = source.values().get(p);
            values[p] = list.toArray();
            properties[p] = state(source.properties().get(p), list);
        }
        return new BlockTypeLayout(owner, properties, values, base, source, defaultOffset);
    }

    static BlockTypeLayout custom(BedrockBlockType owner, CustomBlock block) {
        List<BlockProperty<?>> declared = block.properties();
        BedrockState<?>[] properties = new BedrockState<?>[declared.size()];
        Object[][] values = new Object[declared.size()][];
        int defaultOffset = 0;
        int radix = 1;
        for (int p = 0; p < declared.size(); p++) {
            BlockProperty<?> property = declared.get(p);
            List<Object> list = new ArrayList<>(property.values());
            values[p] = list.toArray();
            properties[p] = state(property.name(), list);
            Object value = block.defaultStates().get(property.name());
            if (value != null) {
                defaultOffset += list.indexOf(value) * radix;
            }
            radix *= list.size();
        }
        return new BlockTypeLayout(owner, properties, values, -1, null, defaultOffset);
    }

    /**
     * @return the vanilla constant for a state name if it fits the values, else a state made for them
     */
    private static BedrockState<?> state(String name, List<Object> values) {
        BedrockState<?> constant = VanillaTypes.STATES.get(name);
        if (constant != null && values.stream().allMatch(value -> fromNbt(constant, value) != null)) {
            return constant;
        }
        Object first = values.getFirst();
        if (first instanceof Byte && values.stream().allMatch(value -> value instanceof Byte b && (b == 0 || b == 1))) {
            return BoolState.of(name);
        }
        if (values.stream().allMatch(value -> value instanceof Integer)) {
            int[] ints = new int[values.size()];
            for (int i = 0; i < ints.length; i++) {
                ints[i] = (Integer) values.get(i);
            }
            java.util.Arrays.sort(ints);
            return IntState.of(name, ints);
        }
        List<String> strings = new ArrayList<>();
        for (Object value : values) {
            strings.add(String.valueOf(value));
        }
        return StringState.of(name, strings);
    }

    @SuppressWarnings("unchecked")
    private static <T> @Nullable T fromNbt(BedrockState<T> state, Object value) {
        T converted = state.fromNbt(value);
        // A converted value must convert back to the same NBT, e.g. a byte 3 is no boolean
        return converted != null && state.toNbt(converted).equals(value) ? converted : null;
    }

    int offset(int combination) {
        return source == null ? combination : source.offset(combination);
    }

    BedrockBlockState[] byCombination() {
        BedrockBlockState[] states = byCombination;
        if (states == null) {
            synchronized (this) {
                states = byCombination;
                if (states == null) {
                    states = new BedrockBlockState[combinations];
                    BedrockBlockState[] byOffset = new BedrockBlockState[count];
                    for (int c = 0; c < combinations; c++) {
                        int offset = offset(c);
                        if (offset >= 0) {
                            states[c] = byOffset[offset] = new BedrockBlockState(this, c, base < 0 ? -1 : base + offset);
                        }
                    }
                    this.byOffset = byOffset;
                    byCombination = states;
                }
            }
        }
        return states;
    }

    BedrockBlockState byOffset(int offset) {
        byCombination();
        return byOffset[offset];
    }

    BedrockBlockState defaultState() {
        return byOffset(defaultOffset);
    }

    /**
     * @return the index of the property with the state's name, or -1
     */
    int property(BedrockState<?> state) {
        for (int p = 0; p < properties.length; p++) {
            if (properties[p] == state) {
                return p;
            }
        }
        String name = state.name();
        for (int p = 0; p < properties.length; p++) {
            if (properties[p].name().equals(name)) {
                return p;
            }
        }
        return -1;
    }

    int valueIndex(int property, Object nbt) {
        Object[] candidates = values[property];
        for (int i = 0; i < candidates.length; i++) {
            if (candidates[i].equals(nbt)) {
                return i;
            }
        }
        return -1;
    }

    int valueIndex(int property, int value) {
        Object[] candidates = values[property];
        for (int i = 0; i < candidates.length; i++) {
            if (candidates[i] instanceof Integer integer && integer == value) {
                return i;
            }
        }
        return -1;
    }

    int valueOf(int combination, int property) {
        return (combination / radix[property]) % values[property].length;
    }

    Map<String, Object> nbtStates(int combination) {
        Map<String, Object> states = new java.util.LinkedHashMap<>();
        for (int p = 0; p < properties.length; p++) {
            states.put(properties[p].name(), values[p][valueOf(combination, p)]);
        }
        return states;
    }
}
