package dev.opencollab.paletter.internal.block;

import dev.opencollab.paletter.block.BlockHashes;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** BDS's probe for a taken hashed id, with synthetic hashes. */
class HashedIdsTest {
    private static int assign(int hash, Integer... taken) {
        Set<Integer> occupied = Set.of(taken);
        return HashedIds.assign(hash, id -> !occupied.contains(id));
    }

    @Test
    void aFreeHashIsTheId() {
        assertEquals(1234, assign(1234));
        assertEquals(1234, assign(1234, 1233, 1235));
    }

    @Test
    void aTakenHashGetsTheNextFreeId() {
        assertEquals(1235, assign(1234, 1234));
        assertEquals(1237, assign(1234, 1234, 1235, 1236));
        assertEquals(Integer.MIN_VALUE, assign(Integer.MAX_VALUE, Integer.MAX_VALUE), "unsigned: 0x7FFFFFFF + 1 is not a top value");
    }

    @Test
    void theProbeRestartsFromZeroAtTheTopValues() {
        assertEquals(0, HashedIds.next(0xFFFFFFFC));
        assertEquals(0, HashedIds.next(0xFFFFFFFD));
        assertEquals(0, HashedIds.next(0xFFFFFFFF));
        assertEquals(0xFFFFFFFC, HashedIds.next(0xFFFFFFFB));
        assertEquals(0, assign(0xFFFFFFFC, 0xFFFFFFFC));
        assertEquals(2, assign(0xFFFFFFFC, 0xFFFFFFFC, 0, 1));
        assertEquals(0, assign(0xFFFFFFFF, 0xFFFFFFFF));
    }

    @Test
    void unknownsIdIsAlwaysTaken() {
        assertEquals(0, assign(BlockHashes.UNKNOWN_HASH));
        assertEquals(1, assign(BlockHashes.UNKNOWN_HASH, 0));
        // The predicate is never asked about it
        assertEquals(0, HashedIds.assign(BlockHashes.UNKNOWN_HASH, id -> {
            assertEquals(0, id);
            return true;
        }));
    }
}
