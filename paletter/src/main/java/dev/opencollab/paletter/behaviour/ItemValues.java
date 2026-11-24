package dev.opencollab.paletter.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;
import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.item.RegisteredItem;
import dev.opencollab.paletter.internal.behaviour.Behaviour;
import dev.opencollab.paletter.internal.behaviour.Column;

/**
 * The behaviour values of one item type in one palette: {@code palette.resolve(BedrockItems.APPLE).behaviour()}. Values are per
 * item type, not per aux value. A handle is the palette's values and the position of the item in them: {@code get} reads
 * an array and neither allocates nor boxes. Handles are equal for the same item in the same palette.
 * <p>
 * {@code get} returns the value: what the user's component sets (on a custom item or an override of a vanilla one), else
 * the vanilla value, else BDS's default for custom content. It throws {@link IllegalStateException} where no value is
 * known: e.g. {@link ItemBehaviour#USE_DURATION} of an item whose use is native code (bow, trident, potion), or a key
 * of a custom item whose BDS default is not captured. {@code getOrElse} returns its fallback there instead. The keys are
 * typed by whose values they read: the compiler rejects a {@link BlockBehaviour} key here.
 *
 * @since 1.0.0
 */
public final class ItemValues {
    private final Behaviour behaviour;
    private final int index;

    /**
     * Internal: plumbing of {@link dev.opencollab.paletter.item.ResolvedItem#behaviour()}: public for it, not part of the
     * API.
     *
     * @param behaviour the palette's behaviour values
     * @param index the position of the item in them
     */
    @Internal
    public ItemValues(@NonNull Behaviour behaviour, int index) {
        this.behaviour = behaviour;
        this.index = index;
    }

    /**
     * The item these values are of.
     *
     * @return the registered item (the definition sent to the client) these values are of
     */
    public @NonNull RegisteredItem item() {
        return behaviour.itemAt(index);
    }

    /**
     * Returns the value of a float key.
     *
     * @param key a float key of items
     * @return the value
     * @throws IllegalStateException if no value is known
     */
    public float get(@NonNull FloatKey<ItemValues> key) {
        Column column = behaviour.itemColumn(key);
        if (!column.known(index)) {
            throw behaviour.missing(item(), key);
        }
        return column.getFloat(index);
    }

    /**
     * Returns the value of an int key.
     *
     * @param key an int key of items
     * @return the value
     * @throws IllegalStateException if no value is known
     */
    public int get(@NonNull IntKey<ItemValues> key) {
        Column column = behaviour.itemColumn(key);
        if (!column.known(index)) {
            throw behaviour.missing(item(), key);
        }
        return column.getInt(index);
    }

    /**
     * Returns the value of a boolean key.
     *
     * @param key a boolean key of items
     * @return the value
     * @throws IllegalStateException if no value is known
     */
    public boolean get(@NonNull BooleanKey<ItemValues> key) {
        Column column = behaviour.itemColumn(key);
        if (!column.known(index)) {
            throw behaviour.missing(item(), key);
        }
        return column.getBoolean(index);
    }

    /**
     * Returns the value of an object key.
     *
     * @param <T> the value type of the key
     * @param key an object key of items
     * @return the value, never null
     * @throws IllegalStateException if no value is known
     */
    @SuppressWarnings("unchecked")
    public <T> @NonNull T get(@NonNull ObjectKey<ItemValues, T> key) {
        Object value = behaviour.itemColumn(key).get(index);
        if (value == null) {
            throw behaviour.missing(item(), key);
        }
        return (T) value;
    }

    /**
     * Returns the value of a float key, or a fallback where none is known.
     *
     * @param key a float key of items
     * @param fallback what to return if no value is known
     * @return the value, or {@code fallback} if no value is known
     */
    public float getOrElse(@NonNull FloatKey<ItemValues> key, float fallback) {
        Column column = behaviour.itemColumn(key);
        return column.known(index) ? column.getFloat(index) : fallback;
    }

    /**
     * Returns the value of an int key, or a fallback where none is known.
     *
     * @param key an int key of items
     * @param fallback what to return if no value is known
     * @return the value, or {@code fallback} if no value is known
     */
    public int getOrElse(@NonNull IntKey<ItemValues> key, int fallback) {
        Column column = behaviour.itemColumn(key);
        return column.known(index) ? column.getInt(index) : fallback;
    }

    /**
     * Returns the value of a boolean key, or a fallback where none is known.
     *
     * @param key a boolean key of items
     * @param fallback what to return if no value is known
     * @return the value, or {@code fallback} if no value is known
     */
    public boolean getOrElse(@NonNull BooleanKey<ItemValues> key, boolean fallback) {
        Column column = behaviour.itemColumn(key);
        return column.known(index) ? column.getBoolean(index) : fallback;
    }

    /**
     * Returns the value of an object key, or a fallback where none is known.
     *
     * @param <T> the value type of the key
     * @param key an object key of items
     * @param fallback what to return if no value is known; may be null
     * @return the value, or {@code fallback} if no value is known
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrElse(@NonNull ObjectKey<ItemValues, T> key, T fallback) {
        Object value = behaviour.itemColumn(key).get(index);
        return value == null ? fallback : (T) value;
    }

    /**
     * Tells whether a value is known for a key, that is whether {@code get} returns one instead of throwing.
     *
     * @param key a key of items
     * @return true if a value is known
     * @throws IllegalArgumentException if the key is not an item key
     * @throws IllegalStateException if behaviour values are switched off ({@code Paletter.Builder#behaviour(false)})
     * @since 1.0.0
     */
    public boolean has(@NonNull BehaviourKey<?> key) {
        return behaviour.itemColumn(key).known(index);
    }

    /**
     * For debugging: the value and where it comes from, e.g.
     * {@code 16 from Paletter <capture file> (SCRIPT_API), 26.60}. Says whether the user set the
     * value, whether it is inherited from an older version's capture, and the other values of a disputed one. Builds the
     * text on each call.
     *
     * @param key the key to explain, of items
     * @return the explanation
     * @throws IllegalArgumentException if the key is not an item key
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
