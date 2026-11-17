package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.geysermc.paletter.annotation.Internal;

/**
 * A block state definition in a compiled palette: the runtime id of a state. Instances are unique per palette and
 * compared by identity, so {@code ==} between definitions from the same palette is safe.
 * <p>
 * Vanilla states are additionally shared between all palettes of a
 * {@link org.geysermc.paletter.Paletter}, across versions: equal state, same instance. That is why a state does not
 * know its block, the state set of a block can differ between versions. Ask the palette you have the state from,
 * {@link BlockRegistry#block(RegisteredBlockState)}, for its {@link RegisteredBlock}, which also has the effective
 * {@linkplain RegisteredBlock#components(RegisteredBlockState) components} and
 * {@linkplain RegisteredBlock#properties(RegisteredBlockState) properties} of a state. The position of a state in its
 * palette is {@link BlockRegistry#indexOf}.
 *
 * @since 0.1.0
 */
public final class RegisteredBlockState implements BlockDefinition {
    private final int runtimeId;
    private final NbtMap state;
    private final boolean custom;

    @Internal
    public RegisteredBlockState(int runtimeId, NbtMap state, boolean custom) {
        this.runtimeId = runtimeId;
        this.state = state;
        this.custom = custom;
    }

    /**
     * @return the runtime id: the hash of the state, see {@link BlockHashes#hash}
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
