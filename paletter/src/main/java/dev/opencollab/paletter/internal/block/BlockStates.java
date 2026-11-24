package dev.opencollab.paletter.internal.block;

import it.unimi.dsi.fastutil.Hash;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;

import java.util.Map;
import java.util.Objects;

/**
 * Helpers for block state NBT. Paletter always represents a block state as a compound with exactly two keys:
 * {@code name} (the block identifier) and {@code states} (the state values). Other keys found in palette dumps
 * ({@code version}, {@code name_hash}, {@code network_id}, {@code block_id}) carry no identity and are dropped.
 *
 * @since 1.0.0
 */
public final class BlockStates {
    /**
     * A state value as NBT holds it: a {@link Boolean} (as keys and {@code BlockProperty} hold it) is the byte 0 or 1,
     * anything else is itself.
     */
    public static @org.checkerframework.checker.nullness.qual.NonNull Object nbtValue(
            @org.checkerframework.checker.nullness.qual.NonNull Object value) {
        return value instanceof Boolean flag ? (byte) (flag ? 1 : 0) : value;
    }


    private BlockStates() {
    }

    public static @NonNull NbtMap of(@NonNull String name) {
        return of(name, NbtMap.EMPTY);
    }

    /**
     * @return the state as {@code {name, states}}, over a {@link StateMap}: it is the form palettes keep, so it is compact
     */
    public static @NonNull NbtMap of(@NonNull String name, @NonNull Map<String, ?> states) {
        NbtMap stateMap = states instanceof NbtMap nbt ? nbt : NbtMap.fromMap(Map.copyOf(states));
        return NbtMap.fromMap(new StateMap(Objects.requireNonNull(name), stateMap));
    }

    /**
     * @return the given state reduced to {@code name} and {@code states}. Returns the same instance if it already is
     */
    public static @NonNull NbtMap normalize(@NonNull NbtMap state) {
        if (StateMap.backs(state)) {
            // Made by of(): normalized, and asking for its size would iterate it (see hash)
            return state;
        }
        String name = state.getString("name", null);
        if (name == null) {
            throw new IllegalArgumentException("Block state has no name: " + state);
        }
        if (state.size() == 2 && state.containsKey("states")) {
            return state;
        }
        return of(name, state.getCompound("states", NbtMap.EMPTY));
    }

    /**
     * The hash of a state that Paletter's state indexes use, from its {@code name} and the hash code of its {@code states}
     * (other keys are ignored, as by {@link #normalize}). Both are cached in their objects, so this neither iterates nor
     * allocates.
     * <p>
     * Not {@code NbtMap#hashCode}: that iterates the map, and iterating makes the map and the unmodifiable map inside it keep
     * an entry set view each, about 48 bytes on every state a palette keeps for good. Hashing and comparing a kept state
     * through {@link #hash} and {@link #same} never iterates it.
     */
    public static int hash(@NonNull NbtMap state) {
        return 31 * name(state).hashCode() + states(state).hashCode();
    }

    /**
     * Whether two states are equal (other keys than {@code name} and {@code states} are ignored), comparing their two
     * values: {@code kept} is not iterated (see {@link #hash}), only the {@code states} of {@code state} are. Give a kept
     * state second.
     */
    public static boolean same(@NonNull NbtMap state, @NonNull NbtMap kept) {
        return state == kept || name(state).equals(name(kept)) && states(state).equals(states(kept));
    }

    /**
     * {@link #hash} and {@link #same} for fastutil's custom hash maps and sets of states.
     */
    public static final Hash.Strategy<NbtMap> STRATEGY = new Hash.Strategy<>() {
        @Override
        public int hashCode(NbtMap state) {
            return state == null ? 0 : hash(state);
        }

        @Override
        public boolean equals(NbtMap state, NbtMap kept) {
            return state == kept || state != null && kept != null && same(state, kept);
        }
    };

    public static @NonNull String name(@NonNull NbtMap state) {
        return state.getString("name");
    }

    public static @NonNull NbtMap states(@NonNull NbtMap state) {
        return state.getCompound("states", NbtMap.EMPTY);
    }

    /**
     * @return a copy of the given state with one state value replaced
     */
    public static @NonNull NbtMap with(@NonNull NbtMap state, @NonNull String key, @NonNull Object value) {
        NbtMapBuilder states = states(state).toBuilder();
        states.put(key, value);
        return of(name(state), states.build());
    }
}
