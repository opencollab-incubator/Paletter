package dev.opencollab.paletter.internal.access;

import dev.opencollab.paletter.behaviour.BehaviourKey;
import dev.opencollab.paletter.behaviour.BlockValues;
import dev.opencollab.paletter.behaviour.ItemValues;
import dev.opencollab.paletter.internal.behaviour.Behaviour;
import org.checkerframework.checker.nullness.qual.NonNull;

/** The package-private members of {@code dev.opencollab.paletter.behaviour} that other packages use. Not API. */
public interface BehaviourAccess {
    /** The constructor of {@link BlockValues}: the values of the state at an index of a behaviour. */
    @NonNull BlockValues blockValues(@NonNull Behaviour behaviour, int index);

    /** The constructor of {@link ItemValues}: the values of the item at an index of a behaviour. */
    @NonNull ItemValues itemValues(@NonNull Behaviour behaviour, int index);

    /** {@code BehaviourKey#ordinal()}: the position of a key among all block and item keys. */
    int ordinal(@NonNull BehaviourKey<?> key);
}
