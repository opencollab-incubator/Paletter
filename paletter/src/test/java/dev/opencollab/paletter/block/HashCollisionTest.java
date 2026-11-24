package dev.opencollab.paletter.block;

import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.PaletteException;
import dev.opencollab.paletter.PaletteLayer;
import dev.opencollab.paletter.Palettes;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.internal.BlockIdOrder;
import dev.opencollab.paletter.internal.block.BlockRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Custom blocks whose states hash alike get ids the way BDS gives them (internal HashedIds): the block BDS numbers first keeps
 * the hash, the other one gets the next id.
 */
class HashCollisionTest {
    /**
     * Two blocks without properties whose only states hash alike: the first colliding pair of {@code paletter:hash_<n>}, the pair
     * the spec add-on carries (data/model/fixtures.json, {@code hashCollision}).
     */
    private static final String FIRST = "paletter:hash_368468";
    private static final String SECOND = "paletter:hash_2193012";
    private static final int HASH = 578938111;

    private static final CustomBlock A = CustomBlock.builder(FIRST).build();
    private static final CustomBlock B = CustomBlock.builder(SECOND).build();
    private static final Paletter PALETTER = Paletter.bundled();

    @Test
    void thePairCollides() {
        assertEquals(HASH, BlockHashes.hash(A.states().getFirst()));
        assertEquals(HASH, BlockHashes.hash(B.states().getFirst()));
        assertEquals(List.of(FIRST, SECOND), List.of(FIRST, SECOND).stream().sorted(BlockIdOrder.DESCENDING).toList(),
            "BDS numbers the first block first");
    }

    @Test
    void theBlockBdsNumbersFirstKeepsTheHashTheOtherGetsTheNextId() {
        int protocol = PALETTER.latest().protocolVersion();
        // Whatever order they are registered in
        for (PaletteLayer layer : List.of(PaletteLayer.builder("ab").block(A).block(B).build(), PaletteLayer.builder("ba").block(B).block(A).build())) {
            BlockRegistry blocks = Palettes.palette(protocol, layer).blocks();
            RegisteredBlockState first = blocks.defaultState(FIRST);
            RegisteredBlockState second = blocks.defaultState(SECOND);
            assertEquals(HASH, first.getRuntimeId(), layer.id());
            assertEquals(HASH + 1, second.getRuntimeId(), layer.id());
            assertSame(first, blocks.byRuntimeId(HASH));
            assertSame(second, blocks.byRuntimeId(HASH + 1));
            assertSame(second, blocks.resolve(second.getState()).block());
            assertEquals(BlockHashes.UNKNOWN_HASH, blocks.defaultState("minecraft:unknown").getRuntimeId(), "unknown keeps its id");
        }
    }

    @Test
    void layersInBdsOrderGiveTheSameIds() {
        int protocol = PALETTER.latest().protocolVersion();
        Palette stacked = Palettes.palette(protocol).extend(PaletteLayer.builder("first").block(A).build())
            .extend(PaletteLayer.builder("second").block(B).build());
        assertEquals(HASH, stacked.blocks().defaultState(FIRST).getRuntimeId());
        assertEquals(HASH + 1, stacked.blocks().defaultState(SECOND).getRuntimeId());
    }

    @Test
    void aLaterLayerThatBdsNumbersFirstThrows() {
        // BDS would give the hash to the later layer's block, which changes the id of the earlier one: Paletter does not
        int protocol = PALETTER.latest().protocolVersion();
        Palette first = Palettes.palette(protocol).extend(PaletteLayer.builder("second").block(B).build());
        assertEquals(HASH, first.blocks().defaultState(SECOND).getRuntimeId());
        PaletteException thrown = assertThrows(PaletteException.class, () -> first.extend(PaletteLayer.builder("first").block(A).build()));
        assertEquals(FIRST, thrown.identifier());
    }
}
