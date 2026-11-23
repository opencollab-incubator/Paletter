package dev.opencollab.paletter.internal.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * One vanilla block type of the generated definitions: its identifier, its state properties in the order Bedrock
 * enumerates its states in (the first changes slowest, the last fastest) and its default state. The states are the full
 * cartesian product of the property values. Immutable. Not API.
 */
public final class BlockDefinition {
    private final String identifier;
    private final StateValues[] properties;
    /** The value index of each property in the default state, or null: the first state is the default. */
    private final int @Nullable [] defaultValues;

    BlockDefinition(String identifier, StateValues[] properties, int @Nullable [] defaultValues) {
        this.identifier = identifier;
        this.properties = properties;
        this.defaultValues = defaultValues;
        for (int i = 0; i < properties.length; i++) {
            for (int j = 0; j < i; j++) {
                if (properties[i].name().equals(properties[j].name())) {
                    throw new IllegalArgumentException(identifier + " has the property " + properties[i].name() + " twice");
                }
            }
        }
    }

    public @NonNull String identifier() {
        return identifier;
    }

    /** @return the properties, the slowest changing first */
    public @NonNull List<StateValues> properties() {
        return List.of(properties);
    }

    StateValues[] propertyArray() {
        return properties;
    }

    /** @return the number of states: the product of the value counts */
    public int stateCount() {
        int count = 1;
        for (StateValues property : properties) {
            count = Math.multiplyExact(count, property.size());
        }
        return count;
    }

    /** @return the position of the default state among the block's states */
    public int defaultState() {
        if (defaultValues == null) {
            return 0;
        }
        int index = 0;
        for (int i = 0; i < properties.length; i++) {
            index = index * properties[i].size() + defaultValues[i];
        }
        return index;
    }

    /** @return the value index of each property in state {@code state} of the block: the last property changes fastest */
    public int @NonNull [] valueIndices(int state) {
        int[] indices = new int[properties.length];
        for (int i = properties.length - 1; i >= 0; i--) {
            indices[i] = state % properties[i].size();
            state /= properties[i].size();
        }
        return indices;
    }

    /** @return the same block with the default state given by one value per property, in property order */
    BlockDefinition withDefault(Object... values) {
        if (values.length != properties.length) {
            throw new IllegalArgumentException(identifier + ": a default needs " + properties.length + " values, got " + Arrays.toString(values));
        }
        int[] indices = new int[values.length];
        boolean first = true;
        for (int i = 0; i < values.length; i++) {
            indices[i] = properties[i].indexOf(values[i]);
            if (indices[i] < 0) {
                throw new IllegalArgumentException(identifier + ": " + properties[i].name() + " has no value " + values[i]);
            }
            first &= indices[i] == 0;
        }
        return new BlockDefinition(identifier, properties, first ? null : indices);
    }

    /** @return the same block with {@code added} inserted at {@code position}; the default keeps its values, an added property takes its first */
    BlockDefinition withProperties(int position, StateValues... added) {
        if (position < 0 || position > properties.length) {
            throw new IllegalArgumentException(identifier + " has no property position " + position);
        }
        StateValues[] result = new StateValues[properties.length + added.length];
        System.arraycopy(properties, 0, result, 0, position);
        System.arraycopy(added, 0, result, position, added.length);
        System.arraycopy(properties, position, result, position + added.length, properties.length - position);
        int[] defaults = null;
        if (defaultValues != null) {
            defaults = new int[result.length];
            System.arraycopy(defaultValues, 0, defaults, 0, position);
            System.arraycopy(defaultValues, position, defaults, position + added.length, properties.length - position);
        }
        return new BlockDefinition(identifier, result, defaults);
    }

    /** @return the same block without the property {@code name}; the default keeps the values of the others */
    BlockDefinition withoutProperty(String name) {
        for (int i = 0; i < properties.length; i++) {
            if (properties[i].name().equals(name)) {
                StateValues[] result = new StateValues[properties.length - 1];
                System.arraycopy(properties, 0, result, 0, i);
                System.arraycopy(properties, i + 1, result, i, properties.length - i - 1);
                int[] defaults = null;
                if (defaultValues != null) {
                    defaults = new int[result.length];
                    System.arraycopy(defaultValues, 0, defaults, 0, i);
                    System.arraycopy(defaultValues, i + 1, defaults, i, properties.length - i - 1);
                    defaults = Arrays.stream(defaults).allMatch(index -> index == 0) ? null : defaults;
                }
                return new BlockDefinition(identifier, result, defaults);
            }
        }
        throw new IllegalArgumentException(identifier + " has no property " + name);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BlockDefinition that && identifier.equals(that.identifier) && Arrays.equals(properties, that.properties)
            && defaultState() == that.defaultState();
    }

    @Override
    public int hashCode() {
        return identifier.hashCode() * 31 + Arrays.hashCode(properties);
    }

    @Override
    public String toString() {
        return identifier + Arrays.toString(properties) + (defaultValues == null ? "" : " default " + Arrays.toString(defaultValues));
    }
}
