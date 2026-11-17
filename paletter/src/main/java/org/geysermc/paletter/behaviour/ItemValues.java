package org.geysermc.paletter.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.internal.behaviour.Behaviour;
import org.geysermc.paletter.internal.behaviour.Column;

/**
 * The behaviour values of one item type in one palette: {@code palette.resolve(BedrockItems.APPLE.base()).behaviour()}. Values are per
 * item type, not per aux value. A handle is the palette's values and the position of the item in them: {@code get} reads
 * an array and neither allocates nor boxes. Handles are equal for the same item in the same palette.
 * <p>
 * {@code get} returns the value: what the user's component sets (on a custom item or an override of a vanilla one), else
 * the vanilla value, else BDS's default for custom content. It throws {@link IllegalStateException} where no value is
 * known: e.g. {@link ItemBehaviour#USE_DURATION} of an item whose use is native code (bow, trident, potion), or a key
 * of a custom item whose BDS default is not captured. {@code getOrElse} returns its fallback there instead. Both throw
 * {@link IllegalArgumentException} for a {@link BlockBehaviour} key.
 *
 * @since 0.1.0
 */
public final class ItemValues {
    private final Behaviour behaviour;
    private final int index;

    /**
     * Plumbing of {@link org.geysermc.paletter.item.ResolvedItem#behaviour()}: public for it, not part of the API.
     */
    @Internal
    public ItemValues(@NonNull Behaviour behaviour, int index) {
        this.behaviour = behaviour;
        this.index = index;
    }

    /**
     * @return the item these values are of
     */
    public @NonNull RegisteredItem item() {
        return behaviour.itemAt(index);
    }

    /**
     * @throws IllegalStateException if no value is known
     */
    public float get(@NonNull FloatKey key) {
        Column column = behaviour.itemColumn(key);
        if (!column.known(index)) {
            throw behaviour.missing(item(), key);
        }
        return column.getFloat(index);
    }

    /**
     * @throws IllegalStateException if no value is known
     */
    public int get(@NonNull IntKey key) {
        Column column = behaviour.itemColumn(key);
        if (!column.known(index)) {
            throw behaviour.missing(item(), key);
        }
        return column.getInt(index);
    }

    /**
     * @throws IllegalStateException if no value is known
     */
    public boolean get(@NonNull BooleanKey key) {
        Column column = behaviour.itemColumn(key);
        if (!column.known(index)) {
            throw behaviour.missing(item(), key);
        }
        return column.getBoolean(index);
    }

    /**
     * @throws IllegalStateException if no value is known
     */
    @SuppressWarnings("unchecked")
    public <T> @NonNull T get(@NonNull ObjectKey<T> key) {
        Object value = behaviour.itemColumn(key).get(index);
        if (value == null) {
            throw behaviour.missing(item(), key);
        }
        return (T) value;
    }

    /**
     * @return the value, or {@code fallback} if no value is known
     */
    public float getOrElse(@NonNull FloatKey key, float fallback) {
        Column column = behaviour.itemColumn(key);
        return column.known(index) ? column.getFloat(index) : fallback;
    }

    /**
     * @return the value, or {@code fallback} if no value is known
     */
    public int getOrElse(@NonNull IntKey key, int fallback) {
        Column column = behaviour.itemColumn(key);
        return column.known(index) ? column.getInt(index) : fallback;
    }

    /**
     * @return the value, or {@code fallback} if no value is known
     */
    public boolean getOrElse(@NonNull BooleanKey key, boolean fallback) {
        Column column = behaviour.itemColumn(key);
        return column.known(index) ? column.getBoolean(index) : fallback;
    }

    /**
     * @return the value, or {@code fallback} if no value is known
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrElse(@NonNull ObjectKey<T> key, T fallback) {
        Object value = behaviour.itemColumn(key).get(index);
        return value == null ? fallback : (T) value;
    }

    /**
     * For debugging: the value and where it comes from, e.g.
     * {@code 16 from Paletter data/bds/1.26.63.1/dump.json.gz (SCRIPT_API), 26.60}. Says whether the user set the
     * value, whether it is inherited from an older version's capture, and the other values of a disputed one. Builds the
     * text on each call.
     */
    public @NonNull String explain(@NonNull BehaviourKey<?> key) {
        return behaviour.explain(item(), key);
    }

    /**
     * @return true for the handle of the same item in the same palette (or in the palette that palette extends)
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof ItemValues other && other.behaviour == behaviour && other.index == index;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(behaviour) * 31 + index;
    }

    @Override
    public String toString() {
        return "ItemValues{" + item() + "}";
    }
}
