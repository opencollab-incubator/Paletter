package org.geysermc.paletter.internal.component;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.PaletteReport;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.block.component.BlockEntity;
import org.geysermc.paletter.block.component.ConnectionRule;
import org.geysermc.paletter.block.component.LiquidDetection;
import org.geysermc.paletter.block.component.PlacementDirection;
import org.geysermc.paletter.block.component.PlacementFilter;
import org.geysermc.paletter.block.component.RedstoneProducer;
import org.geysermc.paletter.block.component.Transformation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Encoders the BDS captures now explain without a hand transform: what each rule kind writes, on the examples the
 * captures showed. The conformance suite checks every captured variant; these keep the shapes readable.
 */
class GeneratedRulesTest {
    private static final Paletter PALETTER = Paletter.bundled();

    private final PaletteReport report = new PaletteReport();
    private final WireContext ctx = at(ComponentIndex.NEWEST_CAPTURED_PROTOCOL);

    private WireContext at(int protocol) {
        return WireContext.of(PALETTER.vanillaData(PALETTER.version(protocol)), report, "test").subject("test:thing");
    }

    private NbtMap block(org.geysermc.paletter.block.component.BlockComponent component, WireContext context) {
        WireNode out = new WireNode();
        BlockWire.write(component, context, out);
        return out.build();
    }

    @Test
    void faceListsAndFaceBits() {
        // ordered-set: BDS sends the faces of the set in its own order
        NbtMap producer = block(RedstoneProducer.builder().power(15).connectedFaces(RedstoneProducer.ConnectedFaces.EAST,
            RedstoneProducer.ConnectedFaces.UP, RedstoneProducer.ConnectedFaces.NORTH).build(), ctx);
        assertEquals(List.of("up", "north", "east"),
            producer.getCompound("minecraft:redstone_producer").getList("connected_faces", NbtType.STRING));
        // list identity: the directions as given
        NbtMap rule = block(ConnectionRule.builder().enabledDirections(ConnectionRule.EnabledDirections.EAST,
            ConnectionRule.EnabledDirections.NORTH).build(), ctx);
        assertEquals(List.of("east", "north"), rule.getCompound("minecraft:connection_rule").getList("enabled_directions", NbtType.STRING));
        // bitsets: down 1, up 2, north 4, south 8, west 16, east 32
        NbtMap filter = block(PlacementFilter.builder().conditions(PlacementFilter.Conditions.builder().allowedFaces("up", "east").build()).build(), ctx);
        assertEquals((byte) 34, filter.getCompound("minecraft:placement_filter").getList("conditions", NbtType.COMPOUND).getFirst()
            .getByte("allowed_faces"));
        NbtMap liquid = block(LiquidDetection.builder().detectionRules(LiquidDetection.DetectionRules.builder()
            .stopsLiquidFlowingFromDirection("up", "north").build()).build(), ctx);
        assertEquals((byte) 6, liquid.getCompound("minecraft:liquid_detection").getList("detectionRules", NbtType.COMPOUND).getFirst()
            .getByte("stopsLiquidFromDirection"));
    }

    @Test
    void enabledStatesAreOneFlagPerState() {
        WireNode out = new WireNode();
        TraitWire.write(PlacementDirection.builder().enabledStates(PlacementDirection.EnabledStates.CORNER_AND_CARDINAL_DIRECTION).build(), ctx, out);
        NbtMap flags = out.build().getCompound("enabled_states");
        assertEquals((byte) 1, flags.getByte("corner_and_cardinal_direction"));
        assertEquals((byte) 0, flags.getByte("cardinal_direction"));
        assertEquals((byte) 0, flags.getByte("sixteen_way_rotation"));
    }

    @Test
    void theBlockEntityContainerHasATitleOnlyFrom26_60() {
        BlockEntity titled = BlockEntity.builder().container(BlockEntity.Container.builder().slotCount(9).title("Box").build()).build();
        NbtMap now = block(titled, ctx).getCompound("minecraft:container");
        assertEquals((byte) 9, now.getByte("slot_count"));
        assertEquals("Box", now.getString("title"));

        NbtMap before = block(titled, at(ComponentIndex.BDS_1_26_52_3)).getCompound("minecraft:container");
        assertEquals((byte) 9, before.getByte("slot_count"));
        assertFalse(before.containsKey("title"), "BDS 1.26.52.3 rejects the title");
    }

    @Test
    void rotationIsWrappedQuarterTurnsAndOnlyMultiplesOf90() {
        NbtMap wire = block(Transformation.builder().rotation(-90, 450, 720).build(), ctx).getCompound("minecraft:transformation");
        assertEquals(3, wire.getInt("RX"));
        assertEquals(1, wire.getInt("RY"));
        assertEquals(0, wire.getInt("RZ"));
        assertThrows(IllegalArgumentException.class, () -> Transformation.builder().rotation(45, 0, 0).build());
    }
}
