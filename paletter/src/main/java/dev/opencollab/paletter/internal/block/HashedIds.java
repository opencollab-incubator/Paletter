package dev.opencollab.paletter.internal.block;

import dev.opencollab.paletter.block.BlockHashes;

import java.util.function.IntPredicate;

/**
 * How BDS gives a block state a hashed network id when its hash is taken, as observed in the public BDS 1.26.63.1 binary:
 * the id is the state's hash ({@link BlockHashes#hash}); while a block of another type has it, BDS tries the id + 1, and
 * once that reaches the top values (unsigned {@code >= 0xFFFFFFFD}) it goes on from 0. {@code minecraft:unknown} always has
 * {@link BlockHashes#UNKNOWN_HASH}. Whether the client probes the same way is unverified: it gets the ids from the server
 * only where a packet carries them.
 */
public final class HashedIds {
    /** The first id the probe never reaches: from here on it restarts at 0. */
    public static final int FIRST_TOP_VALUE = 0xFFFFFFFD;

    private HashedIds() {
    }

    /**
     * The id BDS tries after a taken one.
     *
     * @param id the taken id
     * @return {@code id + 1}, or 0 at the top values
     */
    public static int next(int id) {
        int next = id + 1;
        return Integer.compareUnsigned(next, FIRST_TOP_VALUE) >= 0 ? 0 : next;
    }

    /**
     * The id of a state: its hash, or the first id after it that is free.
     *
     * @param hash the hash of the state
     * @param free whether an id is free; it decides what a taken id means (and throws where Paletter does not follow BDS),
     *             and is never asked about {@link BlockHashes#UNKNOWN_HASH}, which is always taken
     * @return the id
     */
    public static int assign(int hash, IntPredicate free) {
        int id = hash;
        while (id == BlockHashes.UNKNOWN_HASH || !free.test(id)) {
            id = next(id);
        }
        return id;
    }
}
