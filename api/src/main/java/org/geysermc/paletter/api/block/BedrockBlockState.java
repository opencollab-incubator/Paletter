package org.geysermc.paletter.api.block;

import java.util.Map;

/**
 * A record containing a block type and which states it currently has applied to it
 * @param block the block type
 * @param states the currently applied states
 */
public record BedrockBlockState(BedrockBlock block, Map<String, Object> states) {
}
