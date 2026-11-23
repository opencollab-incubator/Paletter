package dev.opencollab.paletter.block;

import dev.opencollab.paletter.internal.block.BlockStates;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.TreeMap;

/**
 * Block network ids. Palettes always use hashed ids: the runtime id of a block state is the hash of the state
 * ({@link #hash(NbtMap)}), what the client expects when {@code StartGamePacket#blockNetworkIdsHashed} is set (which
 * {@link dev.opencollab.paletter.Palette#applyTo(org.cloudburstmc.protocol.bedrock.packet.StartGamePacket)} does). A state
 * has the same id in every version and in every palette, custom blocks included, so tables and encoded chunks can be
 * shared between client versions.
 * <p>
 * The exception is a custom state whose hash a state of another block has: like BDS, the palette gives it the next free
 * id (see docs/wire-notes.md), so its id depends on the other blocks of the palette.
 *
 * @since 1.0.0
 */
public final class BlockHashes {
    private BlockHashes() {
    }

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
     * @return the hash, which is the runtime id of the state
     */
    public static int hash(@NonNull NbtMap state) {
        String name = BlockStates.name(state);
        if ("minecraft:unknown".equals(name)) {
            return UNKNOWN_HASH;
        }
        // The bytes the NBT writer would produce, fed to the hash directly: no sorted copy, compound or writer per state
        NbtMap values = BlockStates.states(state);
        String[] keys = values.keySet().toArray(String[]::new);
        Arrays.sort(keys);
        Fnv1a32 hash = new Fnv1a32();
        hash.tag(TAG_COMPOUND, "");
        hash.tag(TAG_STRING, "name");
        hash.string(name);
        hash.tag(TAG_COMPOUND, "states");
        for (String key : keys) {
            switch (values.get(key)) {
                case Byte value -> {
                    hash.tag(TAG_BYTE, key);
                    hash.write(value);
                }
                case Integer value -> {
                    hash.tag(TAG_INT, key);
                    hash.intLE(value);
                }
                case String value -> {
                    hash.tag(TAG_STRING, key);
                    hash.string(value);
                }
                // Block states only have these three; anything else goes through the NBT writer
                default -> {
                    return hashWithWriter(name, state);
                }
            }
        }
        hash.write(TAG_END);
        hash.write(TAG_END);
        return hash.hash;
    }

    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_INT = 3;
    private static final int TAG_STRING = 8;
    private static final int TAG_COMPOUND = 10;

    /** {@link #hash(NbtMap)} through the little endian NBT writer, for values other than byte, int and string. */
    static int hashWithWriter(String name, NbtMap state) {
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

        /** A tag's type and name, as the writer starts an entry. */
        void tag(int type, String name) {
            write(type);
            string(name);
        }

        /** As {@code LittleEndianDataOutputStream#writeUTF}: the UTF-8 length as a little endian short, then the bytes. */
        void string(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            write(bytes.length);
            write(bytes.length >>> 8);
            write(bytes, 0, bytes.length);
        }

        void intLE(int value) {
            write(value);
            write(value >>> 8);
            write(value >>> 16);
            write(value >>> 24);
        }
    }
}
