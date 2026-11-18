package dev.opencollab.paletter.behaviour;

/**
 * A behaviour key whose values are {@code float}s: {@code get} returns them unboxed.
 *
 * @since 1.0.0
 */
public final class FloatKey extends BehaviourKey<Float> {
    FloatKey(String name, Owner owner, int ordinal) {
        super(name, owner, Float.class, ordinal);
    }
}
