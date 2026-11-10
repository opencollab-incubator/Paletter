package org.geysermc.paletter.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.geysermc.paletter.block.BlockRegistry;

/**
 * The values of one {@link BehaviourKey} for every block state of a palette, by the dense index of
 * {@link BlockRegistry#indexOf}: an array load, no allocation. From {@link Behaviour#blocks(BehaviourKey)}.
 * <p>
 * A table of a palette that extends another one ({@code palette.extend(layer)}) shares the vanilla values of the other
 * one's table (the same array) and only has values of its own for the block states the layers added.
 *
 * @param <T> the value type
 * @since 0.1.0
 */
public final class BlockTable<T> {
    private final BlockRegistry registry;
    /** The values of the first {@code sharedSize} states: shared with the tables of the palettes this one extends. */
    private final Object[] shared;
    private final int sharedSize;
    /** The values of the states after them. */
    private final Object[] own;

    BlockTable(BlockRegistry registry, Object[] values) {
        this(registry, values, values.length, new Object[0]);
    }

    private BlockTable(BlockRegistry registry, Object[] shared, int sharedSize, Object[] own) {
        this.registry = registry;
        this.shared = shared;
        this.sharedSize = sharedSize;
        this.own = own;
    }

    /**
     * @return a table over another registry that has the states of this table's registry first, then the {@code added}
     * values of the states after them
     */
    BlockTable<T> extend(BlockRegistry extended, Object[] added) {
        Object[] all = new Object[own.length + added.length];
        System.arraycopy(own, 0, all, 0, own.length);
        System.arraycopy(added, 0, all, own.length, added.length);
        return new BlockTable<>(extended, shared, sharedSize, all);
    }

    /**
     * @return this table over the registry of a palette with the same states
     */
    BlockTable<T> with(BlockRegistry same) {
        return new BlockTable<>(same, shared, sharedSize, own);
    }

    /** @return the array of values shared with the tables this one extends, for tests */
    Object[] shared() {
        return shared;
    }

    /**
     * @param index a dense index of the palette's {@link BlockRegistry}, {@code 0} to {@link #size()} - 1
     * @return the value, or null if nothing says
     */
    @SuppressWarnings("unchecked")
    public @Nullable T get(int index) {
        return (T) (index < sharedSize ? shared[index] : own[index - sharedSize]);
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
        return sharedSize + own.length;
    }
}
