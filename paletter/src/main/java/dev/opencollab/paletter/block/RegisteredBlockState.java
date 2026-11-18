package dev.opencollab.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import dev.opencollab.paletter.annotation.Internal;

/**
 * A block state definition in a compiled palette: the runtime id of a state. Instances are unique per palette and
 * compared by identity, so {@code ==} between definitions from the same palette is safe.
 * <p>
 * Vanilla states are additionally shared between all palettes of a
 * {@link dev.opencollab.paletter.Paletter}, across versions: equal state, same instance. That is why a state does not
 * know its key: ask the palette you have the state from,
 * {@link dev.opencollab.paletter.Palette#blockState(BlockDefinition)}.
 *
 * @since 1.0.0
 */
public final class RegisteredBlockState implements BlockDefinition {
    private final int runtimeId;
    private final NbtMap state;
    private final boolean custom;

    /**
     * Internal: creates the definition of a state, done by the palette compiler.
     *
     * @param runtimeId the runtime id, the hash of the state
     * @param state the state as {@code {name, states}}
     * @param custom whether the state belongs to a custom block added by a layer
     */
    @Internal
    public RegisteredBlockState(int runtimeId, NbtMap state, boolean custom) {
        this.runtimeId = runtimeId;
        this.state = state;
        this.custom = custom;
    }

    /**
     * The runtime id of this state.
     *
     * @return the runtime id: the hash of the state, see {@link BlockHashes#hash}
     */
    @Override
    public int getRuntimeId() {
        return runtimeId;
    }

    /**
     * The state as sent to the client.
     *
     * @return the state as {@code {name, states}}
     */
    public @NonNull NbtMap getState() {
        return state;
    }

    /**
     * The block identifier of this state.
     *
     * @return the {@code name} of the state, e.g. {@code minecraft:stone}
     */
    public @NonNull String identifier() {
        return state.getString("name");
    }

    /**
     * The state values of this state.
     *
     * @return the {@code states} compound of the state
     */
    public @NonNull NbtMap states() {
        return state.getCompound("states");
    }

    /**
     * Whether this is a custom state.
     *
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
