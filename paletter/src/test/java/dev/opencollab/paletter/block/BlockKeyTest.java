package dev.opencollab.paletter.block;

import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link BlockRegistry#key}: the newest state a block of a palette is sent for, from the registry's own tables. */
class BlockKeyTest {
    private static final Paletter PALETTER = Paletter.bundled();

    @Test
    void everyStateOfTheNewestVersionIsItsOwnKey() {
        BlockRegistry blocks = PALETTER.vanilla(PALETTER.latest()).blocks();
        for (RegisteredBlock block : blocks.blocks()) {
            for (RegisteredBlockState state : block.states()) {
                BedrockBlockState key = blocks.key(state);
                assertNotNull(key, state.toString());
                assertEquals(state.getState(), key.toNbt());
            }
        }
    }

    @Test
    void anOlderVersionKeysItsStatesToTheNewestOnes() {
        DataVersion oldest = PALETTER.versions().getFirst();
        BlockRegistry blocks = PALETTER.vanilla(oldest).blocks();
        int keyed = 0;
        for (RegisteredBlock block : blocks.blocks()) {
            for (RegisteredBlockState state : block.states()) {
                BedrockBlockState key = blocks.key(state);
                if (key != null) {
                    keyed++;
                    // the key is a newest state that this version sends as the state
                    assertSame(state, blocks.block(blocks.canonical().index(key.toNbt())), state.toString());
                }
            }
        }
        assertTrue(keyed > 0, oldest.toString());
    }

    @Test
    void aStateOfAnotherRegistryHasNoKey() {
        BlockRegistry newest = PALETTER.vanilla(PALETTER.latest()).blocks();
        RegisteredBlockState stranger = new RegisteredBlockState(1234567, newest.air().getState(), false);
        assertNull(newest.key(stranger));
    }
}
