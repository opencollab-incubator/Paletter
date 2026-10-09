package org.geysermc.paletter.api.data;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.api.block.BedrockBlockAttributes;
import org.geysermc.paletter.api.block.BedrockBlockState;
import org.geysermc.paletter.api.component.BedrockComponents;

import java.util.Optional;

/**
 * Represents a greater in depth view of a state and what properties that gives the block
 */
public interface BedrockBlockData {
    BedrockBlockState state();

    BlockDefinition definition();

    default int runtimeId() {
        return definition().runtimeId();
    }

    BedrockComponents components();

    NbtMap blockStateTag();

    /**
     * Empty when blocks.json has no entry for this exact state - its state count doesn't fully
     * match block_palette.nbt's (see palette.block.BlockAttributes), so coverage isn't total.
     * @return the attributes of the block, if any
     */
    Optional<BedrockBlockAttributes> attributes();

    boolean isFallback();
}
