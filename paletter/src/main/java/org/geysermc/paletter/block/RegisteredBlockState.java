package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;

/**
 * A block state definition in a compiled palette. Instances are unique per palette and compared by identity,
 * so {@code ==} between definitions from the same palette is safe.
 * <p>
 * In {@link BlockNetworkIds#HASHED} palettes, vanilla states are additionally shared between all palettes of a
 * {@link org.geysermc.paletter.Paletter}, across versions: equal state, same instance. The position of a state in its
 * palette is {@link BlockRegistry#indexOf}.
 *
 * @since 1.0.0
 */
public final class RegisteredBlockState implements BlockDefinition {
    private final int runtimeId;
    private final NbtMap state;
    private final boolean custom;

    RegisteredBlockState(int runtimeId, NbtMap state, boolean custom) {
        this.runtimeId = runtimeId;
        this.state = state;
        this.custom = custom;
    }

    /**
     * @return the runtime id: a palette position, or the state hash in {@link BlockNetworkIds#HASHED} palettes
     */
    @Override
    public int getRuntimeId() {
        return runtimeId;
    }

    /**
     * @return the state as {@code {name, states}}
     */
    public @NonNull NbtMap getState() {
        return state;
    }

    public @NonNull String identifier() {
        return state.getString("name");
    }

    public @NonNull NbtMap states() {
        return state.getCompound("states");
    }

    /**
     * @return true if this state belongs to a custom block added by a layer
     */
    public boolean isCustom() {
        return custom;
    }

    @Override
    public String toString() {
        return "RegisteredBlockState{" + runtimeId + " " + state.getString("name") + state.getCompound("states") + "}";
    }
}
