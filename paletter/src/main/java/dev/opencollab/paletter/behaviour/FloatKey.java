package dev.opencollab.paletter.behaviour;

/**
 * A behaviour key whose values are {@code float}s: {@code get} returns them unboxed.
 *
 * @param <O> whose values the key reads: {@link BlockValues} or {@link ItemValues}, so a key of one cannot be read from
 *            the other
 * @since 1.0.0
 */
public final class FloatKey<O> extends BehaviourKey<Float> {
    FloatKey(String name, Owner owner, int ordinal) {
        super(name, owner, Float.class, ordinal);
    }
}
