package org.geysermc.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * A block state property, typed: {@link BoolState} (stored as a byte 0 or 1), {@link IntState}, {@link EnumState}
 * (a string state with a generated enum) or {@link StringState} (a string state without one, e.g. of a custom block).
 * The vanilla ones are constants of {@link BedrockStates}; which values a particular block accepts is up to the block,
 * see {@link BedrockBlockType#values(BedrockState)}.
 * <p>
 * Two states are equal if they have the same name and kind. A block matches a state by name, so a custom block property
 * named like a vanilla state can be used with the vanilla constant.
 *
 * @param <T> the value type
 * @since 2.0.0
 */
public abstract sealed class BedrockState<T> permits BoolState, IntState, EnumState, StringState {
    private final String name;

    BedrockState(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /**
     * @return the state name as in the block state NBT, e.g. {@code minecraft:cardinal_direction} or {@code age}
     */
    public final @NonNull String name() {
        return name;
    }

    /**
     * @return every value of this state. For vanilla states: the values of every block with the state together
     */
    public abstract @NonNull List<T> values();

    /**
     * @return the NBT value: a {@code Byte}, {@code Integer} or {@code String}
     */
    abstract @NonNull Object toNbt(@NonNull T value);

    /**
     * @return the value of an NBT value, or null if it is none of this state
     */
    abstract @Nullable T fromNbt(@NonNull Object value);

    @Override
    public boolean equals(Object o) {
        return o instanceof BedrockState<?> other && other.getClass() == getClass() && other.name.equals(name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
