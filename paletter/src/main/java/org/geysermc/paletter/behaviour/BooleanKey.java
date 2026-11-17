package org.geysermc.paletter.behaviour;

/**
 * A behaviour key whose values are {@code boolean}s: {@code get} returns them unboxed.
 *
 * @since 1.0.0
 */
public final class BooleanKey extends BehaviourKey<Boolean> {
    BooleanKey(String name, Owner owner, int ordinal) {
        super(name, owner, Boolean.class, ordinal);
    }
}
