package org.geysermc.paletter.api.palette;

import org.geysermc.paletter.api.BedrockVersion;
import org.geysermc.paletter.api.block.BedrockBlock;
import org.geysermc.paletter.api.block.BedrockBlockState;
import org.geysermc.paletter.api.block.CustomBedrockBlock;
import org.geysermc.paletter.api.block.VersionedBedrockBlock;
import org.geysermc.paletter.api.data.BedrockBlockData;

import java.util.Collection;
import java.util.Optional;

/**
 * A palette representing all the blocks within bedrock edition
 */
public interface BlockPalette {
    /**
     * The bedrock version of this palette
     * @return the bedrock version
     */
    BedrockVersion version();

    /**
     * Gets a version-specific instance of a block, throws if no such block exists in the palette
     * @param block the block
     * @return the version-specific data
     */
    VersionedBedrockBlock get(BedrockBlock block);

    /**
     * Get a block state by its runtime ID
     * @param runtimeId the ID
     * @return a block state, if any
     */
    Optional<BedrockBlockState> byRuntimeId(int runtimeId);

    /**
     * Get a block by ID
     * @param identifier the block ID
     * @return a version-specific block, if any
     */
    Optional<VersionedBedrockBlock> byIdentifier(String identifier);

    /**
     * All blocks in the palette
     * @return the blocks
     */
    Collection<VersionedBedrockBlock> blocks();

    /**
     * All states in the palette
     * @return the state
     */
    Collection<BedrockBlockState> allStates();

    /**
     * Gets block data based on a state
     * @param state the state
     * @return the block data
     */
    BedrockBlockData dataFromState(BedrockBlockState state);

    /**
     * Makes a builder out of this block palette
     * @return builder
     */
    Builder toBuilder();

    interface Builder {
        Builder register(CustomBedrockBlock custom);

        BlockPalette build();
    }
}
