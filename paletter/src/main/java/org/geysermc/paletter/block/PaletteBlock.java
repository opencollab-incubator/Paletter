package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;

/**
 * A block state definition in a compiled palette. Instances are unique per palette and compared by identity,
 * so {@code ==} between definitions from the same palette is safe.
 */
public final class PaletteBlock implements BlockDefinition {
    private final int runtimeId;
    private final int index;
    private final NbtMap state;
    private final boolean custom;

    PaletteBlock(int runtimeId, int index, NbtMap state, boolean custom) {
        this.runtimeId = runtimeId;
        this.index = index;
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
     * @return the dense position of this state in its palette, {@code 0} to {@code size() - 1}. Equal to the runtime
     * id in {@link BlockNetworkIds#SEQUENTIAL} palettes
     */
    public int index() {
        return index;
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
        return "PaletteBlock{" + runtimeId + " " + state.getString("name") + state.getCompound("states") + "}";
    }
}
