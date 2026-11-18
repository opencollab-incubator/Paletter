package dev.opencollab.paletter.block;

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

    /** FNV-1 over the UTF-8 bytes, written out: ASCII names skip the encoding, other names must not. */
    @Test
    void hashesTheUtf8BytesOfEveryName() {
        for (String name : new String[] {"minecraft:stone", "test:caf\u00e9", "test:\u30d6\u30ed\u30c3\u30af", "\u00e9"}) {
            long hash = 0xcbf29ce484222325L;
            for (byte b : name.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                hash = hash * 1099511628211L ^ (b & 0xff);
            }
            assertEquals(hash, BlockRegistry.nameHash(name), name);
        }
    }
}
