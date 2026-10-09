package org.geysermc.paletter.palette.block;

import org.cloudburstmc.nbt.NbtMap;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public record BlockPalette(List<Block> blocks, Map<Integer, BlockAttributes> attributesByHash) {
    public int getBlockId(String blockName) {
        return blocks.stream().filter(block -> block.name().equals(blockName))
                .findFirst().map(Block::blockId).orElse(-1);
    }

    // blocks.json doesn't cover every block_palette.nbt state (different snapshot), so this can miss.
    public Optional<BlockAttributes> attributesFor(Block block) {
        return Optional.ofNullable(attributesByHash.get(block.blockStateHash()));
    }

    public List<BlockStateData> getBlockStateData(int blockId) {
        List<Block> blocks = this.blocks.stream().filter(b -> b.blockId() == blockId).toList();
        if (blocks.isEmpty()) return null;

        Map<String, Set<Object>> stateMap = new HashMap<>();

        for (Block block : blocks) {
            for (Map.Entry<String, Object> entry : block.states().entrySet()) {
                if (!stateMap.containsKey(entry.getKey())) stateMap.put(entry.getKey(), new HashSet<>());
                stateMap.get(entry.getKey()).add(entry.getValue());
            }
        }

        List<BlockStateData> data = new ArrayList<>();

        for (Map.Entry<String, Set<Object>> entry : stateMap.entrySet()) {
            data.add(new BlockStateData(entry.getKey(), Collections.unmodifiableSet(entry.getValue())));
        }

        return data;
    }

    public BlockStateData getBlockStateData(int blockId, String key) {
        List<Block> blocks = this.blocks.stream().filter(b -> b.blockId() == blockId).toList();
        if (blocks.isEmpty()) return null;

        if (blocks.getFirst().getState(key) == null) return null;

        Set<Object> values = new HashSet<>();

        for (Block block : blocks) {
            Object value = block.getState(key);
            if (value == null) throw new IllegalStateException("Not all block states contain the key %s, the palette is invalid.".formatted(key));
            values.add(value);
        }

        return new BlockStateData(key, Collections.unmodifiableSet(values));
    }

    public List<BlockData> generateBlockData() {
        List<BlockData> blockData = new ArrayList<>();
        Set<Integer> handledBlocks = new HashSet<>();

        for (Block block : blocks) {
            if (handledBlocks.contains(block.blockId())) continue;
            handledBlocks.add(block.blockId());

            blockData.add(new BlockData(
                    block.networkId(), block.nameHash(),
                    block.name(), block.version(), block.blockId(),
                    getBlockStateData(block.blockId())
            ));
        }

        return blockData;
    }

    public static BlockPalette fromNbt(List<NbtMap> list, List<BlockAttributes> attributes) {
        List<Block> blocks = new ArrayList<>();
        for (NbtMap map : list) {
            blocks.add(new Block(
                    map.getInt("network_id"),
                    map.getLong("name_hash"),
                    map.getString("name"),
                    map.getInt("version"),
                    map.getInt("block_id"),
                    map.getCompound("states")
            ));
        }

        Map<Integer, BlockAttributes> attributesByHash = attributes.stream()
                .collect(Collectors.toMap(BlockAttributes::blockStateHash, Function.identity(), (first, duplicate) -> first));

        return new BlockPalette(blocks, attributesByHash);
    }
}
