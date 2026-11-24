package dev.opencollab.paletter.behaviour;

/**
 * A behaviour key whose values are objects ({@link String}, {@link BlockShape}).
 *
 * @param <O> whose values the key reads: {@link BlockValues} or {@link ItemValues}, so a key of one cannot be read from
 *            the other
 * @param <T> the value type
 * @since 1.0.0
 */
public final class ObjectKey<O, T> extends BehaviourKey<T> {
    ObjectKey(String name, Owner owner, int ordinal, Class<T> type) {
        super(name, owner, type, ordinal);
    }
}
