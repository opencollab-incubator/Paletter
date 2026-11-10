package org.geysermc.paletter.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.item.ItemRegistry;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.vanilla.BedrockItemType;

import java.util.Arrays;
import java.util.List;

/**
 * The values of one {@link BehaviourKey} for every item of a palette: two array loads by runtime id (the runtime ids of
 * a palette's items are dense), no allocation. From {@link Behaviour#items(BehaviourKey)}; built with the palette's
 * behaviour, so lookups never compute anything.
 * <p>
 * Numbers and booleans are stored in primitive arrays: read them with the primitive accessors
 * ({@link #floatByRuntimeId}, {@link #intByRuntimeId}, {@link #booleanByRuntimeId}), which neither box nor return null.
 * {@code get} boxes the value, which is fine off the hot path.
 *
 * @param <T> the value type
 * @since 0.1.0
 */
public final class ItemTable<T> {
    private final ItemRegistry registry;
    /** The items in the order of the column: {@link ItemRegistry#definitions()}. */
    private final List<RegisteredItem> items;
    private final Column column;
    /** The smallest runtime id: {@code positions[runtimeId - minRuntimeId]} is the position of the item in the column, or -1. */
    private final int minRuntimeId;
    private final int[] positions;

    ItemTable(ItemRegistry registry, Column column) {
        this.registry = registry;
        this.items = registry.definitions();
        this.column = column;
        this.minRuntimeId = items.stream().mapToInt(RegisteredItem::getRuntimeId).min().orElse(0);
        int max = items.stream().mapToInt(RegisteredItem::getRuntimeId).max().orElse(-1);
        this.positions = new int[Math.max(0, max - minRuntimeId + 1)];
        Arrays.fill(positions, -1);
        for (int i = 0; i < items.size(); i++) {
            positions[items.get(i).getRuntimeId() - minRuntimeId] = i;
        }
    }

    private int positionOf(int runtimeId) {
        int at = runtimeId - minRuntimeId;
        return at < 0 || at >= positions.length ? -1 : positions[at];
    }

    /**
     * @return the value of an item of the palette, or null if nothing says or the item is not of the palette
     */
    @SuppressWarnings("unchecked")
    public @Nullable T get(@NonNull RegisteredItem item) {
        int position = positionOf(item.getRuntimeId());
        return position < 0 || items.get(position) != item ? null : (T) column.get(position);
    }

    /**
     * @return the value of an item type, or null if nothing says or the palette does not have the item
     */
    public @Nullable T get(@NonNull BedrockItemType type) {
        RegisteredItem item = registry.item(type.identifier());
        return item == null ? null : get(item);
    }

    /**
     * @return the value of the item with this runtime id, or null if nothing says or the palette has no such item
     */
    @SuppressWarnings("unchecked")
    public @Nullable T byRuntimeId(int runtimeId) {
        int position = positionOf(runtimeId);
        return position < 0 ? null : (T) column.get(position);
    }

    /**
     * @return the value of the item with this runtime id, or {@code orElse} if nothing says or the palette has no such
     * item
     * @throws ClassCastException if the key is not a {@link Float} key
     */
    public float floatByRuntimeId(int runtimeId, float orElse) {
        int position = positionOf(runtimeId);
        return position < 0 ? orElse : column.getFloat(position, orElse);
    }

    /**
     * @return like {@link #floatByRuntimeId} for an {@link Integer} key
     * @throws ClassCastException if the key is not an {@link Integer} key
     */
    public int intByRuntimeId(int runtimeId, int orElse) {
        int position = positionOf(runtimeId);
        return position < 0 ? orElse : column.getInt(position, orElse);
    }

    /**
     * @return like {@link #floatByRuntimeId} for a {@link Boolean} key
     * @throws ClassCastException if the key is not a {@link Boolean} key
     */
    public boolean booleanByRuntimeId(int runtimeId, boolean orElse) {
        int position = positionOf(runtimeId);
        return position < 0 ? orElse : column.getBoolean(position, orElse);
    }

    /**
     * @return the number of items, equal to the size of the registry's {@link ItemRegistry#definitions()}
     */
    public int size() {
        return column.size();
    }
}
