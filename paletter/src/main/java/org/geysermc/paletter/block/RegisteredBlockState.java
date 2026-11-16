package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.geysermc.paletter.Lookup;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.vanilla.BedrockBlockState;

import java.util.List;

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
    /** Custom states belong to one block in every palette they are in; vanilla states are shared and not bound. */
    private volatile @Nullable CustomBlock customBlock;
    private int indexInBlock;
    private volatile @Nullable BedrockBlockState key;

    @Internal
    public RegisteredBlockState(int runtimeId, NbtMap state, boolean custom) {
        this.runtimeId = runtimeId;
        this.state = state;
        this.custom = custom;
    }

    /**
     * Binds a custom state to its block and its position there.
     */
    synchronized void bind(CustomBlock block, int index) {
        if (this.customBlock == null) {
            this.indexInBlock = index;
            this.customBlock = block;
        }
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

    /**
     * The key of this state as the newest bundled data and the layer of its block know it, without a palette: for a
     * state of a palette built from other data, or of an older version, use {@link BlockRegistry#key}.
     *
     * @return the key, or null for a vanilla state that is not in the newest bundled data
     */
    public @Nullable BedrockBlockState key() {
        BedrockBlockState key = this.key;
        if (key == null) {
            if (custom) {
                CustomBlock block = customBlock;
                List<BedrockBlockState> keys = block == null ? List.of() : block.type().states();
                key = indexInBlock < keys.size() ? keys.get(indexInBlock) : null;
            } else {
                key = Paletter.bundled().block(state, Lookup.STRICT);
            }
            this.key = key;
        }
        return key;
    }

    @Override
    public String toString() {
        return "RegisteredBlockState{" + runtimeId + " " + state.getString("name") + state.getCompound("states") + "}";
    }
}
