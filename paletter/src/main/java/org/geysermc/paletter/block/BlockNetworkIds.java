package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.TreeMap;

/**
 * How block runtime ids are assigned, i.e. which ids are written to chunks, block updates and item stacks.
 * Chosen per palette, see {@link org.geysermc.paletter.Paletter#palette(int, BlockNetworkIds, java.util.List)}.
 *
 * @since 1.0.0
 */
public enum BlockNetworkIds {
    /**
     * Ids are positions in the palette: vanilla order, re-sorted by name hash once custom blocks exist. Ids of the same
     * state differ between versions, and adding a custom block shifts vanilla ids.
     */
    SEQUENTIAL,
    /**
     * Ids are hashes of the block state ({@link #hash(NbtMap)}), what the client expects when
     * {@code StartGamePacket#blockNetworkIdsHashed} is set. A state has the same id in every version and in every
     * palette, custom blocks included, so tables and encoded chunks can be shared between client versions.
     */
    HASHED;

    /**
     * The id {@code minecraft:unknown} has in hashed palettes; the game special cases it.
     */
    public static final int UNKNOWN_HASH = -2;

    private static final int FNV1_32_OFFSET_BASIS = 0x811c9dc5;
    private static final int FNV1_32_PRIME = 0x01000193;

    /**
     * The hashed network id of a block state, as the client computes it: FNV-1a 32 bit over the little endian
     * (not network / varint) NBT of the compound {@code {name, states}}, with an empty root name, the state values
     * sorted by key, and no {@code version} tag. {@code minecraft:unknown} is always {@value #UNKNOWN_HASH}.
     * <p>
     * Sources: Alemiz112's reference implementation (gist 504d0f79feac7ef57eda174b668dd345, used by CloudburstMC /
     * WaterdogPE), and GeyserMC/Geyser#6253. Verified against the {@code network_id} BDS writes into its block palette
     * dumps: every state of 26.40, 26.50 and 26.60 matches, e.g. {@code minecraft:air} is {@code -604749536}.
     *
     * @param state a block state as {@code {name, states}}; other keys are ignored
     */
    public static int hash(@NonNull NbtMap state) {
        String name = BlockStates.name(state);
        if ("minecraft:unknown".equals(name)) {
            return UNKNOWN_HASH;
        }
        NbtMap canonical = NbtMap.builder()
            .putString("name", name)
            .putCompound("states", NbtMap.fromMap(new TreeMap<>(BlockStates.states(state))))
            .build();
        Fnv1a32 hash = new Fnv1a32();
        try (NBTOutputStream out = NbtUtils.createWriterLE(hash)) {
            out.writeTag(canonical);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return hash.hash;
    }

    private static final class Fnv1a32 extends OutputStream {
        private int hash = FNV1_32_OFFSET_BASIS;

        @Override
        public void write(int b) {
            hash ^= b & 0xff;
            hash *= FNV1_32_PRIME;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            for (int i = offset; i < offset + length; i++) {
                write(bytes[i]);
            }
        }
    }
}
