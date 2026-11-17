package org.geysermc.paletter.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.internal.behaviour.Behaviour;
import org.geysermc.paletter.internal.behaviour.Column;

/**
 * The behaviour values of one block state in one palette: {@code palette.resolve(state).behaviour()}. A handle is the
 * palette's values and the position of the state in them: {@code get} reads an array and neither allocates nor boxes.
 * Call {@code palette.resolve(...).behaviour().get(key)} on the hot path as it is; inlined, the handle costs nothing. Handles
 * are equal for the same state in the same palette; compare them with {@code equals}, not {@code ==}.
 * <p>
 * {@code get} returns the value: what the user's component sets, else the vanilla value, else BDS's default for custom
 * content. It throws {@link IllegalStateException} where no value is known: a custom block that does not set
 * a key whose BDS default is not captured ({@link BlockBehaviour#DESTROY_TIME}, {@link BlockBehaviour#OUTLINE_SHAPE}).
 * {@code getOrElse} returns its fallback there instead. Both throw {@link IllegalArgumentException} for an {@link ItemBehaviour} key.
 *
 * @since 0.1.0
 */
public final class BlockValues {
    private final Behaviour behaviour;
    private final int index;

    /**
     * Plumbing of {@link Palette#resolve(org.geysermc.paletter.vanilla.BedrockBlockState)}: public for it, not part of the API.
     */
    @Internal
    public BlockValues(@NonNull Behaviour behaviour, int index) {
        this.behaviour = behaviour;
        this.index = index;
    }

    /**
     * @return the state these values are of
     */
    public @NonNull RegisteredBlockState state() {
        return behaviour.stateAt(index);
    }

    /**
     * @throws IllegalStateException if no value is known
     */
    public float get(@NonNull FloatKey key) {
        Column column = behaviour.blockColumn(key);
        if (!column.known(index)) {
            throw behaviour.missing(state(), key);
        }
        return column.getFloat(index);
    }

    /**
     * @throws IllegalStateException if no value is known
     */
    public int get(@NonNull IntKey key) {
        Column column = behaviour.blockColumn(key);
        if (!column.known(index)) {
            throw behaviour.missing(state(), key);
        }
        return column.getInt(index);
    }

    /**
     * @throws IllegalStateException if no value is known
     */
    public boolean get(@NonNull BooleanKey key) {
        Column column = behaviour.blockColumn(key);
        if (!column.known(index)) {
            throw behaviour.missing(state(), key);
        }
        return column.getBoolean(index);
    }

    /**
     * @throws IllegalStateException if no value is known
     */
    @SuppressWarnings("unchecked")
    public <T> @NonNull T get(@NonNull ObjectKey<T> key) {
        Object value = behaviour.blockColumn(key).get(index);
        if (value == null) {
            throw behaviour.missing(state(), key);
        }
        return (T) value;
    }

    /**
     * @return the value, or {@code fallback} if no value is known
     */
    public float getOrElse(@NonNull FloatKey key, float fallback) {
        Column column = behaviour.blockColumn(key);
        return column.known(index) ? column.getFloat(index) : fallback;
    }

    /**
     * @return the value, or {@code fallback} if no value is known
     */
    public int getOrElse(@NonNull IntKey key, int fallback) {
        Column column = behaviour.blockColumn(key);
        return column.known(index) ? column.getInt(index) : fallback;
    }

    /**
     * @return the value, or {@code fallback} if no value is known
     */
    public boolean getOrElse(@NonNull BooleanKey key, boolean fallback) {
        Column column = behaviour.blockColumn(key);
        return column.known(index) ? column.getBoolean(index) : fallback;
    }

    /**
     * @return the value, or {@code fallback} if no value is known
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrElse(@NonNull ObjectKey<T> key, T fallback) {
        Object value = behaviour.blockColumn(key).get(index);
        return value == null ? fallback : (T) value;
    }

    /**
     * For debugging: the value and where it comes from, e.g.
     * {@code 0.989 from CloudburstMC/Data@0021592 (CLOUDBURST_DATA, third party), 26.60}. Says whether the user set the
     * value, whether it is inherited from an older version's capture, and the other values of a disputed one. Builds the
     * text on each call.
     */
    public @NonNull String explain(@NonNull BehaviourKey<?> key) {
        return behaviour.explain(state(), key);
    }

    /**
     * @return true for the handle of the same state in the same palette (or in the palette that palette extends)
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof BlockValues other && other.behaviour == behaviour && other.index == index;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(behaviour) * 31 + index;
    }

    @Override
    public String toString() {
        return "BlockValues{" + state() + "}";
    }
}
