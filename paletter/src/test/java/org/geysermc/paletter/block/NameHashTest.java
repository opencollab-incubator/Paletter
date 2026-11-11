package org.geysermc.paletter.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link BlockRegistry#nameHash}. Non-ASCII names are covered by {@code TransformsTest#uvLockBonesAreInBdsOrder}, which
 * sorts by it.
 */
class NameHashTest {
    @Test
    void matchesTheNameHashBdsSends() {
        // name_hash of BDS's 26.60 block palette (CloudburstMC/Data 0021592)
        assertEquals(0xbd584baf00003448L, BlockRegistry.nameHash("minecraft:air"));
        assertEquals(0x2727739c84aa7913L, BlockRegistry.nameHash("minecraft:stone"));
    }

    @Test
    void theEmptyStringHashesToZero() {
        assertEquals(0, BlockRegistry.nameHash(""));
    }
}
