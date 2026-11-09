package org.geysermc.paletter.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.geysermc.paletter.block.BlockRegistry;

/**
 * The values of one {@link BehaviourKey} for every block state of a palette, by the dense index of
 * {@link BlockRegistry#indexOf}: an array load, no allocation. From {@link Behaviour#blocks(BehaviourKey)}.
 *
 * @param <T> the value type
 * @since 0.1.0
 */
public final class BlockTable<T> {
    private final BlockRegistry registry;
    private final Object[] values;

    BlockTable(BlockRegistry registry, Object[] values) {
        this.registry = registry;
        this.values = values;
    }

    /**
     * @param index a dense index of the palette's {@link BlockRegistry}, {@code 0} to {@link #size()} - 1
     * @return the value, or null if nothing says
     */
    @SuppressWarnings("unchecked")
    public @Nullable T get(int index) {
        return (T) values[index];
    }

    /**
     * @return the value of a block state of the palette, or null if nothing says or the state is not of the palette
     */
    public @Nullable T get(@NonNull BlockDefinition state) {
        int index = registry.indexOf(state);
        return index < 0 ? null : get(index);
    }

    /**
     * @return the value of the block state with this runtime id (state hash), or null if nothing says or the palette has
     * no such state
     */
    public @Nullable T byRuntimeId(int runtimeId) {
        int index = registry.indexOfRuntimeId(runtimeId);
        return index < 0 ? null : get(index);
    }

    /**
     * @return the number of block states, equal to the registry's {@link BlockRegistry#size()}
     */
    public int size() {
        return values.length;
    }
}
