package org.geysermc.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A value of an {@link EnumState}: implemented by the generated enums in {@code org.geysermc.paletter.vanilla.state}
 * and by enums of your own for custom string states.
 *
 * @since 2.0.0
 */
public interface StateValue {
    /**
     * @return the string stored in the block state NBT, e.g. {@code north}
     */
    @NonNull String value();
}
