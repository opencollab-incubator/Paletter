package dev.opencollab.paletter.behaviour;

import dev.opencollab.paletter.annotation.Internal;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;

/**
 * A typed behaviour property of blocks or items: what Bedrock Dedicated Server knows about vanilla content but never
 * sends to clients (friction, destroy time, collision shapes, attack damage, ...). The keys are the constants of
 * {@link BlockBehaviour} and {@link ItemBehaviour}. Their type says what {@code get} returns: a {@link FloatKey} a
 * {@code float}, an {@link IntKey} an {@code int}, a {@link BooleanKey} a {@code boolean} (none of them boxed) and an
 * {@link ObjectKey} its object. Each of those is typed by the values it reads ({@link BlockValues} or {@link ItemValues}),
 * so {@code get} with a key of the other owner does not compile.
 *
 * @param <T> the value type: {@link Float}, {@link Integer}, {@link Boolean}, {@link String} or {@link BlockShape}
 * @since 1.0.0
 */
public abstract sealed class BehaviourKey<T> permits FloatKey, IntKey, BooleanKey, ObjectKey {
    /** Whose property a key is. */
    public enum Owner {
        /**
         * A property of blocks.
         */
        BLOCK,
        /**
         * A property of items.
         */
        ITEM
    }

    private final String name;
    private final Owner owner;
    private final Class<T> type;
    private final int ordinal;

    BehaviourKey(String name, Owner owner, Class<T> type, int ordinal) {
        this.name = Objects.requireNonNull(name);
        this.owner = Objects.requireNonNull(owner);
        this.type = Objects.requireNonNull(type);
        this.ordinal = ordinal;
    }

    /**
     * The name of the key.
     *
     * @return the name, unique per owner and the key of the bundled data, e.g. {@code friction}
         * @since 1.0.0
         */
    public @NonNull String name() {
        return name;
    }

    /**
     * Whose property the key is.
     *
     * @return whether this is a block or an item key
     * @since 1.0.0
     */
    public @NonNull Owner owner() {
        return owner;
    }

    /**
     * The type of the values of the key.
     *
     * @return the class of the value type: {@link Float}, {@link Integer}, {@link Boolean}, {@link String} or {@link BlockShape}
     * @since 1.0.0
     */
    public @NonNull Class<T> type() {
        return type;
    }

    /**
     * Internal: the position of the key, which is where its values are kept.
     *
     * @return the position of the key among every block and item key, {@code 0} to their count - 1
     */
    @Internal
    public int ordinal() {
        return ordinal;
    }

    @Override
    public String toString() {
        return owner.name().toLowerCase(java.util.Locale.ROOT) + ":" + name;
    }
}
