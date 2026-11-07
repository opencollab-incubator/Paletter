package org.geysermc.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * A boolean block state: a byte 0 or 1 in the block state NBT, e.g. {@link BedrockStates#OPEN_BIT}.
 *
 * @since 2.0.0
 */
public final class BoolState extends BedrockState<Boolean> {
    private static final List<Boolean> VALUES = List.of(false, true);
    private static final Byte FALSE = 0;
    private static final Byte TRUE = 1;

    private BoolState(String name) {
        super(name);
    }

    public static @NonNull BoolState of(@NonNull String name) {
        return new BoolState(name);
    }

    @Override
    public @NonNull List<Boolean> values() {
        return VALUES;
    }

    @Override
    @NonNull Object toNbt(@NonNull Boolean value) {
        return value ? TRUE : FALSE;
    }

    @Override
    @Nullable Boolean fromNbt(@NonNull Object value) {
        return value instanceof Number number ? number.intValue() != 0 : null;
    }
}
