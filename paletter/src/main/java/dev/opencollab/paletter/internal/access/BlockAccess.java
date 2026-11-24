package dev.opencollab.paletter.internal.access;

import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.CustomBlock;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;

import java.util.List;

/** The package-private members of {@code dev.opencollab.paletter.block} that other packages use. Not API. */
public interface BlockAccess {
    /** {@code CustomBlock#stateProperties(int)}. */
    @NonNull List<BlockProperty<?>> stateProperties(@NonNull CustomBlock block, int protocol);

    /** {@code CustomBlock#states(int)}. */
    @NonNull List<NbtMap> states(@NonNull CustomBlock block, int protocol);

    /** {@code CustomBlock#defaultState(int)}. */
    @NonNull NbtMap defaultState(@NonNull CustomBlock block, int protocol);

    /** {@code BlockProperty.of(String, Type, List)}: a property of values already in their stored types. */
    @NonNull BlockProperty<?> property(@NonNull String name, BlockProperty.@NonNull Type type, @NonNull List<?> values);
}
