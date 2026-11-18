package dev.opencollab.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A value of an {@link EnumState}: implemented by the generated enums in {@code dev.opencollab.paletter.vanilla.state}
 * and by enums of your own for custom string states.
 *
 * @since 1.0.0
 */
public interface StateValue {
    /**
     * The string stored in the block state NBT.
     *
     * @return the string stored in the block state NBT, e.g. {@code north}
     */
    @NonNull String value();
}
