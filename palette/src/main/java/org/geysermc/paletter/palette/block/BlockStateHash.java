package org.geysermc.paletter.palette.block;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

// Bedrock's block-state hash: FNV-1a 32-bit over the little-endian NBT encoding of {name, states}.
// This is how blocks.json's "blockStateHash" field joins back to a block_palette.nbt entry -
// verified against CloudburstMC/Cloudburst's own BlockStateHash.java.
public final class BlockStateHash {
    private static final int FNV1_32_INIT = 0x811c9dc5;
    private static final int FNV1_PRIME_32 = 0x01000193;

    private BlockStateHash() {
    }

    public static int compute(String name, NbtMap states) {
        NbtMap tag = NbtMap.builder()
                .putString("name", name)
                .putCompound("states", states)
                .build();

        byte[] bytes;
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream();
             var writer = NbtUtils.createWriterLE(stream)) {
            writer.writeTag(tag);
            bytes = stream.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        int hash = FNV1_32_INIT;
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= FNV1_PRIME_32;
        }
        return hash;
    }
}
