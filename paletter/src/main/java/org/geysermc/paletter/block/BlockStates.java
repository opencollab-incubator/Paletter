package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;

import java.util.Map;

/**
 * Helpers for block state NBT. Paletter always represents a block state as a compound with exactly two keys:
 * {@code name} (the block identifier) and {@code states} (the state values). Other keys found in palette dumps
 * ({@code version}, {@code name_hash}, {@code network_id}, {@code block_id}) carry no identity and are dropped.
 */
public final class BlockStates {

    private BlockStates() {
    }

    public static @NonNull NbtMap of(@NonNull String name) {
        return of(name, NbtMap.EMPTY);
    }

    public static @NonNull NbtMap of(@NonNull String name, @NonNull Map<String, ?> states) {
        NbtMap stateMap = states instanceof NbtMap nbt ? nbt : NbtMap.fromMap(Map.copyOf(states));
        return NbtMap.builder()
            .putString("name", name)
            .putCompound("states", stateMap)
            .build();
    }

    /**
     * @return the given state reduced to {@code name} and {@code states}. Returns the same instance if it already is
     */
    public static @NonNull NbtMap normalize(@NonNull NbtMap state) {
        String name = state.getString("name", null);
        if (name == null) {
            throw new IllegalArgumentException("Block state has no name: " + state);
        }
        if (state.size() == 2 && state.containsKey("states")) {
            return state;
        }
        return of(name, state.getCompound("states", NbtMap.EMPTY));
    }

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
