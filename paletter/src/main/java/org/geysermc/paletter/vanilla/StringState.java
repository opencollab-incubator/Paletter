package org.geysermc.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * A string block state without an enum: string properties of custom blocks, and vanilla string states that are newer
 * than the generated {@link BedrockStates} (e.g. only in a preview version).
 *
 * @since 2.0.0
 */
public final class StringState extends BedrockState<String> {
    private final List<String> values;

    private StringState(String name, List<String> values) {
        super(name);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("String state " + name + " has no values");
        }
        this.values = List.copyOf(values);
    }

    public static @NonNull StringState of(@NonNull String name, @NonNull String @NonNull ... values) {
        return new StringState(name, List.of(values));
    }

    public static @NonNull StringState of(@NonNull String name, @NonNull List<String> values) {
        return new StringState(name, values);
    }

    @Override
    public @NonNull List<String> values() {
        return values;
    }

    @Override
    @NonNull Object toNbt(@NonNull String value) {
        return value;
    }

    @Override
    @Nullable String fromNbt(@NonNull Object value) {
        return value instanceof String string ? string : null;
    }
}
