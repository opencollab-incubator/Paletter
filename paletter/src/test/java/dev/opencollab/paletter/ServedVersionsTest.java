package dev.opencollab.paletter;

import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.data.PaletteDataSource;
import dev.opencollab.paletter.internal.CanonicalBlocks;
import dev.opencollab.paletter.internal.CanonicalItems;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.internal.data.Renames;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.shape.VoxelShapeStore;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockBlocks;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import dev.opencollab.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Keys are the same whatever versions an instance serves, and canonical ids belong to the instance: a handle built from
 * one of them, and the id of a handle, go through that instance's tables.
 */
class ServedVersionsTest {
    private static final Paletter BUNDLED = Paletter.bundled();

    /** An instance of the bundled data that does not serve its newest version. */
    private static Paletter withoutNewest() {
        DataVersion newest = BUNDLED.latest();
        return Paletter.builder().versions(version -> !version.equals(newest)).build();
    }

    /** An instance over data whose newest version is older than the bundled one: its canonical ids are its own. */
    private static Paletter olderData() {
        PaletteDataSource bundled = PaletteDataSource.bundled();
        List<DataVersion> versions = bundled.versions().subList(0, bundled.versions().size() - 1);
        return Paletter.builder().data(new PaletteDataSource() {
            @Override
            public List<DataVersion> versions() {
                return versions;
            }

            @Override
            public VanillaData load(DataVersion version) throws IOException {
                return bundled.load(version);
            }

            @Override
            public Renames renames() {
                return bundled.renames();
            }

            @Override
            public VoxelShapeStore voxelShapes() {
                return bundled.voxelShapes();
            }
        }).build();
    }

    @Test
    void blockHandlesRoundTripTheirCanonicalIds() {
        for (Paletter paletter : List.of(BUNDLED, withoutNewest(), olderData())) {
            CanonicalBlocks canonical = paletter.canonicalBlocks();
            Palette palette = paletter.palette(paletter.latest().protocolVersion());
            for (int id = 0; id < canonical.size(); id++) {
                BedrockBlockState handle = canonical.handle(id);
                String where = paletter.latest() + " " + canonical.state(id);
                if (handle == null) {
                    // A state of this version that the bundled newest does not have has no constant
                    assertEquals(-1, canonical.vanillaIdsIn(BUNDLED.canonicalBlocks())[id], where);
                    continue;
                }
                assertEquals(canonical.state(id), BlockStates.normalize(handle.toNbt()), where);
                assertEquals(id, handle.canonicalIn(canonical), where);
                assertEquals(palette.blocks().runtimeId(id), palette.runtimeId(handle), where);
            }
        }
    }

    @Test
    void itemHandlesRoundTripTheirCanonicalIds() {
        for (Paletter paletter : List.of(BUNDLED, withoutNewest(), olderData())) {
            CanonicalItems canonical = paletter.canonicalItems();
            for (int id = 0; id < canonical.size(); id++) {
                BedrockItemVariant handle = canonical.handle(id);
                String where = paletter.latest() + " " + canonical.variant(id);
                assertNotNull(handle, where);
                assertEquals(canonical.variant(id), handle.toItemVariant(), where);
                assertEquals(id, handle.canonicalIn(canonical), where);
            }
        }
    }

    @Test
    void lookupsGiveTheKeysOfTheConstants() {
        for (Paletter paletter : List.of(withoutNewest(), olderData())) {
            String where = paletter.latest().toString();
            Palette palette = paletter.palette(paletter.latest().protocolVersion());
            assertSame(BedrockBlocks.STONE.defaultState(), palette.blockState(BlockStates.of("minecraft:stone"), Lookup.RENAMED), where);
            assertSame(BedrockItems.POTION.variant(5), palette.itemVariant("minecraft:potion", 5, Lookup.RENAMED), where);
        }
    }
}
