package org.geysermc.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A string block state with an enum of its values, e.g. {@link BedrockStates#CARDINAL_DIRECTION} with
 * {@link org.geysermc.paletter.vanilla.state.CardinalDirection}. Vanilla states with the same values share an enum.
 *
 * @param <E> the enum
 * @since 1.0.0
 */
public final class EnumState<E extends Enum<E> & StateValue> extends BedrockState<E> {
    private final Class<E> type;
    private final List<E> values;
    private final Map<String, E> byValue;

    private EnumState(String name, Class<E> type) {
        super(name);
        this.type = Objects.requireNonNull(type, "type");
        this.values = List.of(type.getEnumConstants());
        Map<String, E> byValue = new HashMap<>();
        for (E value : values) {
            if (byValue.put(value.value(), value) != null) {
                throw new IllegalArgumentException(type.getName() + " has two constants for " + value.value());
            }
        }
        this.byValue = Map.copyOf(byValue);
    }

    /**
     * Creates a state whose values are the constants of an enum.
     *
     * @param <E> the enum
     * @param name the state name as in the block state NBT
     * @param type the enum of the values; each constant's {@link StateValue#value()} must be unique
     * @return a new state
     * @throws IllegalArgumentException if two constants of the enum have the same value
     */
    public static <E extends Enum<E> & StateValue> @NonNull EnumState<E> of(@NonNull String name, @NonNull Class<E> type) {
        return new EnumState<>(name, type);
    }

    /**
     * The enum of the values.
     *
     * @return the enum of the values
     */
    public @NonNull Class<E> type() {
        return type;
    }

    @Override
    public @NonNull List<E> values() {
        return values;
    }

    /**
     * Looks up a constant by its stored string.
     *
     * @param value a string as stored in the block state NBT
     * @return the constant for the string, or null if there is none
     */
    public @Nullable E value(@NonNull String value) {
        return byValue.get(value);
    }

    @Override
    @NonNull Object toNbt(@NonNull E value) {
        return value.value();
    }

    @Override
    @Nullable E fromNbt(@NonNull Object value) {
        return value instanceof String string ? byValue.get(string) : null;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EnumState<?> other && other.type == type && other.name().equals(name());
    }

    @Override
    public int hashCode() {
        return name().hashCode();
    }
}
