package org.geysermc.paletter.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.geysermc.paletter.block.BlockRegistry;

/**
 * The values of one {@link BehaviourKey} for every block state of a palette, by the dense index of
 * {@link BlockRegistry#indexOf}: an array load, no allocation. From {@link Behaviour#blocks(BehaviourKey)}; a lookup
 * never computes anything.
 * <p>
 * Numbers and booleans are stored in primitive arrays: read them with the primitive accessors ({@link #getFloat},
 * {@link #getInt}, {@link #getBoolean} and the {@code ByRuntimeId} ones), which neither box nor return null. {@code get}
 * boxes the value, which is fine off the hot path.
 * <p>
 * A table of a palette that extends another one ({@code palette.extend(layer)}) shares the vanilla values of the other
 * one's table (the same arrays) and only has values of its own for the block states the layers added.
 *
 * @param <T> the value type
 * @since 0.1.0
 */
public final class BlockTable<T> {
    private final BlockRegistry registry;
    /** The values of the first {@code sharedSize} states: shared with the tables of the palettes this one extends. */
    private final Column shared;
    private final int sharedSize;
    /** The values of the states after them. */
    private final Column own;

    BlockTable(BlockRegistry registry, Column values, Class<?> type) {
        this(registry, values, values.size(), Column.empty(type));
    }

    private BlockTable(BlockRegistry registry, Column shared, int sharedSize, Column own) {
        this.registry = registry;
        this.shared = shared;
        this.sharedSize = sharedSize;
        this.own = own;
    }

    /**
     * @return a table over another registry that has the states of this table's registry first, then the {@code added}
     * values of the states after them
     */
    BlockTable<T> extend(BlockRegistry extended, @Nullable Object[] added) {
        return new BlockTable<>(extended, shared, sharedSize, own.extend(added));
    }

    /**
     * @return this table over the registry of a palette with the same states
     */
    BlockTable<T> with(BlockRegistry same) {
        return new BlockTable<>(same, shared, sharedSize, own);
    }

    /** @return the values shared with the tables this one extends, for tests */
    Column shared() {
        return shared;
    }

    /**
     * @param index a dense index of the palette's {@link BlockRegistry}, {@code 0} to {@link #size()} - 1
     * @return the value, or null if nothing says
     */
    @SuppressWarnings("unchecked")
    public @Nullable T get(int index) {
        return (T) (index < sharedSize ? shared.get(index) : own.get(index - sharedSize));
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
     * @param index a dense index of the palette's {@link BlockRegistry}
     * @return the value, or {@code orElse} if nothing says
     * @throws ClassCastException if the key is not a {@link Float} key
     */
    public float getFloat(int index, float orElse) {
        return index < sharedSize ? shared.getFloat(index, orElse) : own.getFloat(index - sharedSize, orElse);
    }

    /**
     * @param index a dense index of the palette's {@link BlockRegistry}
     * @return the value, or {@code orElse} if nothing says
     * @throws ClassCastException if the key is not an {@link Integer} key
     */
    public int getInt(int index, int orElse) {
        return index < sharedSize ? shared.getInt(index, orElse) : own.getInt(index - sharedSize, orElse);
    }

    /**
     * @param index a dense index of the palette's {@link BlockRegistry}
     * @return the value, or {@code orElse} if nothing says
     * @throws ClassCastException if the key is not a {@link Boolean} key
     */
    public boolean getBoolean(int index, boolean orElse) {
        return index < sharedSize ? shared.getBoolean(index, orElse) : own.getBoolean(index - sharedSize, orElse);
    }

    /**
     * @return the value of the block state with this runtime id, or {@code orElse} if nothing says or the palette has no
     * such state
     * @throws ClassCastException if the key is not a {@link Float} key
     */
    public float floatByRuntimeId(int runtimeId, float orElse) {
        int index = registry.indexOfRuntimeId(runtimeId);
        return index < 0 ? orElse : getFloat(index, orElse);
    }

    /**
     * @return like {@link #floatByRuntimeId} for an {@link Integer} key
     * @throws ClassCastException if the key is not an {@link Integer} key
     */
    public int intByRuntimeId(int runtimeId, int orElse) {
        int index = registry.indexOfRuntimeId(runtimeId);
        return index < 0 ? orElse : getInt(index, orElse);
    }

    /**
     * @return like {@link #floatByRuntimeId} for a {@link Boolean} key
     * @throws ClassCastException if the key is not a {@link Boolean} key
     */
    public boolean booleanByRuntimeId(int runtimeId, boolean orElse) {
        int index = registry.indexOfRuntimeId(runtimeId);
        return index < 0 ? orElse : getBoolean(index, orElse);
    }

    /**
     * @return the number of block states, equal to the registry's {@link BlockRegistry#size()}
     */
    public int size() {
        return sharedSize + own.size();
    }
}
