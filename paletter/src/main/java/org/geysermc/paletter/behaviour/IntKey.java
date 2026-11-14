package org.geysermc.paletter.behaviour;

/**
 * A behaviour key whose values are {@code int}s: {@code get} returns them unboxed.
 *
 * @since 0.1.0
 */
public final class IntKey extends BehaviourKey<Integer> {
    IntKey(String name, Owner owner, int ordinal) {
        super(name, owner, Integer.class, ordinal);
    }
}
