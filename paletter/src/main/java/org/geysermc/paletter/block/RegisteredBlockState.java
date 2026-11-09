package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.geysermc.paletter.Origin;
import org.geysermc.paletter.block.component.BlockComponent;
import org.geysermc.paletter.internal.block.StateConditions;
import org.geysermc.paletter.vanilla.BedrockBlockState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A block state definition in a compiled palette. Instances are unique per palette and compared by identity,
 * so {@code ==} between definitions from the same palette is safe.
 * <p>
 * In {@link BlockNetworkIds#HASHED} palettes, vanilla states are additionally shared between all palettes of a
 * {@link org.geysermc.paletter.Paletter}, across versions: equal state, same instance. The position of a state in its
 * palette is {@link BlockRegistry#indexOf}. {@link #block()} of a shared state is the block of the first palette that
 * registered it, see {@link RegisteredBlock}.
 *
 * @since 1.0.0
 */
public final class RegisteredBlockState implements BlockDefinition {
    private final int runtimeId;
    private final NbtMap state;
    private final boolean custom;
    private volatile @Nullable RegisteredBlock block;
    private int indexInBlock;
    private volatile @Nullable BedrockBlockState key;
    private volatile @Nullable List<BlockComponent> components;

    RegisteredBlockState(int runtimeId, NbtMap state, boolean custom) {
        this.runtimeId = runtimeId;
        this.state = state;
        this.custom = custom;
    }

    /**
     * Binds this state to its block; the first block wins, since vanilla states are shared between palettes.
     */
    synchronized void bind(RegisteredBlock block, int index) {
        if (this.block == null) {
            this.indexInBlock = index;
            this.block = block;
        }
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

    /**
     * @return the block this state belongs to
     */
    public @NonNull RegisteredBlock block() {
        RegisteredBlock block = this.block;
        if (block == null) {
            throw new IllegalStateException(this + " is not part of a block");
        }
        return block;
    }

    public @NonNull Origin origin() {
        return block().origin();
    }

    /**
     * @return the key of this state, or null for a vanilla state that is not in the newest bundled data
     */
    public @Nullable BedrockBlockState key() {
        BedrockBlockState key = this.key;
        if (key == null) {
            key = custom ? block().customKey(indexInBlock) : BedrockBlockState.vanilla(state);
            this.key = key;
        }
        return key;
    }

    /**
     * @return the components in effect for this state: those of the block, plus those of the permutations of its
     * {@link CustomBlock} whose condition matches this state, replacing components of the same name. Empty for vanilla
     */
    public @NonNull List<BlockComponent> components() {
        List<BlockComponent> result = components;
        if (result == null) {
            RegisteredBlock block = block();
            CustomBlock customBlock = block.customBlock();
            if (customBlock == null || customBlock.permutations().isEmpty()) {
                result = block.components();
            } else {
                Map<String, BlockComponent> merged = new LinkedHashMap<>();
                block.components().forEach(component -> merged.put(component.name(), component));
                NbtMap states = states();
                for (CustomBlock.Permutation permutation : customBlock.permutations()) {
                    if (StateConditions.matches(permutation.condition(), states)) {
                        permutation.components().forEach(component -> merged.put(component.name(), component));
                    }
                }
                result = List.copyOf(merged.values());
            }
            components = result;
        }
        return result;
    }

    @Override
    public String toString() {
        return "RegisteredBlockState{" + runtimeId + " " + state.getString("name") + state.getCompound("states") + "}";
    }
}
