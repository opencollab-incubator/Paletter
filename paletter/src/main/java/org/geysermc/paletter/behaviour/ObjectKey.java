package org.geysermc.paletter.behaviour;

/**
 * A behaviour key whose values are objects ({@link String}, {@link BlockShape}).
 *
 * @param <T> the value type
 * @since 1.0.0
 */
public final class ObjectKey<T> extends BehaviourKey<T> {
    ObjectKey(String name, Owner owner, int ordinal, Class<T> type) {
        super(name, owner, type, ordinal);
    }
}
