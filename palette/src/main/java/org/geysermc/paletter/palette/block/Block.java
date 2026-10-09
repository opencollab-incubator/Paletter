package org.geysermc.paletter.palette.block;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;

public record Block(int networkId, long nameHash, String name, int version, int blockId, NbtMap states) {
    public Object getState(String stateKey) {
        return states.get(stateKey);
    }

    public Object getState(String stateKey, Object def) {
        return states.getOrDefault(stateKey, def);
    }

    public <T> T getState(NbtType<T> type, String stateKey) {
        return getState(type, stateKey, null);
    }

    public <T> T getState(NbtType<T> type, String stateKey, T def) {
        Object value = states.get(stateKey);
        if (value != null && value.getClass() == type.getTagClass()) return (T) value;
        return def;
    }

    public int blockStateHash() {
        return BlockStateHash.compute(name, states);
    }
}
