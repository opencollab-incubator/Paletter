package dev.opencollab.paletter.internal.component;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import dev.opencollab.paletter.internal.PaletteReportBuilder;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.block.component.BlockEntity;
import dev.opencollab.paletter.block.component.ConnectionRule;
import dev.opencollab.paletter.block.component.LiquidDetection;
import dev.opencollab.paletter.block.component.PlacementDirection;
import dev.opencollab.paletter.block.component.PlacementFilter;
import dev.opencollab.paletter.block.component.RedstoneProducer;
import dev.opencollab.paletter.block.component.Transformation;
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

    private final PaletteReportBuilder report = new PaletteReportBuilder();
    private final WireContext ctx = at(ComponentIndex.NEWEST_CAPTURED_PROTOCOL);

    private WireContext at(int protocol) {
        return WireContext.of(PALETTER.vanillaData(PALETTER.version(protocol)), report, "test").subject("test:thing");
    }

    private NbtMap block(dev.opencollab.paletter.block.component.BlockComponent component, WireContext context) {
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

    private NbtMap item(List<dev.opencollab.paletter.item.component.ItemComponent> components) {
        WireNode out = new WireNode();
        ItemWire.envelope(ctx, out);
        components.forEach(component -> ItemWire.write(component, ctx, out));
        return out.build().getCompound("item_properties");
    }

    @Test
    void aLeafSeveralComponentsWriteKeepsItsOwnersValueInEitherOrder() {
        // Unverified against BDS (no capture has both): the component the leaf carries the value of owns it
        var stack = dev.opencollab.paletter.item.component.MaxStackSize.of(16);
        var weapon = dev.opencollab.paletter.item.component.Wearable.builder()
            .slot(dev.opencollab.paletter.item.component.Wearable.Slot.SLOT_WEAPON_OFFHAND).build();
        assertEquals(16, item(List.of(stack, weapon)).getInt("max_stack_size"));
        assertEquals(16, item(List.of(weapon, stack)).getInt("max_stack_size"));
        var offHand = dev.opencollab.paletter.item.component.AllowOffHand.of(true);
        var head = dev.opencollab.paletter.item.component.Wearable.builder()
            .slot(dev.opencollab.paletter.item.component.Wearable.Slot.SLOT_ARMOR_HEAD).build();
        assertEquals((byte) 1, item(List.of(offHand, head)).getByte("allow_off_hand"));
        assertEquals((byte) 1, item(List.of(head, offHand)).getByte("allow_off_hand"));
        // Alone, the wearable's slot table still writes the leaf
        assertEquals(1, item(List.of(head)).getInt("max_stack_size"));
    }

    @Test
    void anIntegralLeafRefusesAValueItsBitsCannotHold() {
        assertEquals((byte) 100, Wire.toByte(100.9));
        assertEquals((byte) -1, Wire.toByte(255)); // an unsigned bit pattern fits
        assertThrows(IllegalArgumentException.class, () -> Wire.toByte(300)); // would wrap to 44
        assertThrows(IllegalArgumentException.class, () -> Wire.toShort(70000));
        assertThrows(IllegalArgumentException.class, () -> Wire.toInt(1e12));
        assertThrows(IllegalArgumentException.class, () -> Wire.toInt(Double.NaN));
        assertEquals(13, Wire.toInt(20.0 * (double) 0.7f)); // truncated: unverified against BDS (docs/data-pipeline.md)
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

        NbtMap before = block(titled, at(ComponentIndex.V26_50)).getCompound("minecraft:container");
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
