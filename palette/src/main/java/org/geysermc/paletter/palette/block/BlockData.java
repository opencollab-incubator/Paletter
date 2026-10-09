package org.geysermc.paletter.palette.block;

import java.util.List;

public record BlockData(int networkId, long nameHash, String name, int version, int blockId, List<BlockStateData> blockStateData) {
}
