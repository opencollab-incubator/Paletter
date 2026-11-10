package org.geysermc.paletter.behaviour;

import org.geysermc.paletter.Palette;
import org.geysermc.paletter.PaletteLayer;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.block.component.CollisionBox;
import org.geysermc.paletter.block.component.DestructibleByMining;
import org.geysermc.paletter.block.component.Friction;
import org.geysermc.paletter.block.component.LightEmission;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.behaviour.BehaviourStore;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.item.component.MaxStackSize;
import org.geysermc.paletter.vanilla.BedrockBlocks;
import org.geysermc.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviourTest {
    private static final Paletter PALETTER = Paletter.builder().build();
    private static final DataVersion LATEST = PALETTER.latest();

    private static Behaviour latest() {
        return PALETTER.vanilla(LATEST).behaviour();
    }

    @Test
    void vanillaBlockValuesInEveryVersion() {
        for (DataVersion version : PALETTER.versions()) {
            Behaviour behaviour = PALETTER.vanilla(version).behaviour();
            assertEquals(0.98f, behaviour.get(BedrockBlocks.ICE.defaultState(), BlockBehaviour.FRICTION), version.name());
            assertEquals(0.989f, behaviour.get(BedrockBlocks.BLUE_ICE.defaultState(), BlockBehaviour.FRICTION), version.name());
            assertEquals(0.6f, behaviour.get(BedrockBlocks.STONE.defaultState(), BlockBehaviour.FRICTION), version.name());
            assertEquals(1.5f, behaviour.get(BedrockBlocks.STONE.defaultState(), BlockBehaviour.DESTROY_TIME), version.name());
            assertEquals(BlockShape.FULL_CUBE, behaviour.get(BedrockBlocks.STONE.defaultState(), BlockBehaviour.COLLISION_SHAPE));
            assertEquals(BlockShape.EMPTY, behaviour.get(BedrockBlocks.AIR.defaultState(), BlockBehaviour.COLLISION_SHAPE));
        }
    }

    @Test
    void stairsHaveSeveralBoxes() {
        BlockShape shape = latest().get(BedrockBlocks.OAK_STAIRS.defaultState(), BlockBehaviour.COLLISION_SHAPE);
        assertNotNull(shape);
        assertTrue(shape.boxes().size() >= 2, shape.toString());
    }

    @Test
    void provenanceSaysWhereAndWhetherInherited() {
        Palette palette = PALETTER.vanilla(LATEST);
        RegisteredBlockState stone = palette.block(BedrockBlocks.STONE.defaultState());
        Provenance block = palette.behaviour().provenance(stone, BlockBehaviour.FRICTION);
        assertEquals(Provenance.Source.CLOUDBURST_DATA, block.source());
        assertFalse(block.inherited());
        assertTrue(block.reference().startsWith("CloudburstMC/Data@"), block.reference());

        // No Allay capture of the newest version: its item values are inherited from an older one
        RegisteredItem sword = Objects.requireNonNull(palette.item(BedrockItems.DIAMOND_SWORD));
        Provenance item = palette.behaviour().provenance(sword, ItemBehaviour.ATTACK_DAMAGE);
        assertEquals(Provenance.Source.ALLAY_DATA, item.source());
        assertTrue(item.inherited());
        assertEquals(LATEST.name(), item.version());

        Provenance stack = palette.behaviour().provenance(sword, ItemBehaviour.MAX_STACK_SIZE);
        assertEquals(Provenance.Source.SCRIPT_API, stack.source());
        assertFalse(stack.inherited());
    }

    @Test
    void vanillaItemValues() {
        Behaviour behaviour = latest();
        assertEquals(16, behaviour.get(BedrockItems.ENDER_PEARL, ItemBehaviour.MAX_STACK_SIZE));
        assertEquals(1561, behaviour.get(BedrockItems.DIAMOND_SWORD, ItemBehaviour.MAX_DAMAGE));
        assertEquals(32, behaviour.get(BedrockItems.APPLE, ItemBehaviour.USE_DURATION), "from the component data");
        RegisteredItem apple = Objects.requireNonNull(PALETTER.vanilla(LATEST).item(BedrockItems.APPLE));
        assertEquals(Provenance.Source.WIRE, behaviour.provenance(apple, ItemBehaviour.USE_DURATION).source());
        assertNull(behaviour.get(BedrockItems.BOW, ItemBehaviour.USE_DURATION), "native item: needs the native capture");
    }

    @Test
    void theDenseTableEqualsSingleLookups() {
        Palette palette = PALETTER.vanilla(LATEST);
        BlockTable<Float> friction = palette.behaviour().blocks(BlockBehaviour.FRICTION);
        assertSame(friction, palette.behaviour().blocks(BlockBehaviour.FRICTION));
        assertEquals(palette.blocks().size(), friction.size());
        for (int i = 0; i < friction.size(); i++) {
            RegisteredBlockState state = palette.blocks().byIndex(i);
            assertEquals(palette.behaviour().get(state, BlockBehaviour.FRICTION), friction.get(i));
            assertEquals(friction.get(i), friction.byRuntimeId(state.getRuntimeId()));
        }
        assertNull(friction.byRuntimeId(12345));
    }

    @Test
    void customContentUsesItsComponents() {
        CustomBlock slab = CustomBlock.builder("test:slab")
            .component(Friction.of(0.1f))
            .component(DestructibleByMining.builder().secondsToDestroy(3).build())
            .component(LightEmission.of(10))
            .component(CollisionBox.of(CollisionBox.Entry.builder().origin(-8, 0, -8).size(16, 8, 16).build()))
            .build();
        CustomItem ruby = CustomItem.builder("test:ruby").component(MaxStackSize.of(16)).build();
        Palette palette = Paletter.builder().layer(PaletteLayer.builder("test").block(slab).item(ruby).build()).build()
            .palette(LATEST.protocolVersion());
        Behaviour behaviour = palette.behaviour();
        RegisteredBlockState state = palette.blocks().block("test:slab").defaultState();

        assertEquals(0.9f, behaviour.get(state, BlockBehaviour.FRICTION), 1e-6f);
        assertEquals(3f, behaviour.get(state, BlockBehaviour.DESTROY_TIME));
        assertEquals(10, behaviour.get(state, BlockBehaviour.LIGHT_EMISSION));
        assertEquals(BlockShape.of(0, 0, 0, 1, 0.5f, 1), behaviour.get(state, BlockBehaviour.COLLISION_SHAPE));
        assertEquals(0f, behaviour.get(state, BlockBehaviour.EXPLOSION_RESISTANCE), "not on the wire: the BDS default");
        assertTrue(behaviour.provenance(state, BlockBehaviour.EXPLOSION_RESISTANCE).bdsDefault());
        assertEquals(Provenance.Source.USER, behaviour.provenance(state, BlockBehaviour.FRICTION).source());
        assertEquals(behaviour.get(state, BlockBehaviour.FRICTION), behaviour.blocks(BlockBehaviour.FRICTION).get(state));

        RegisteredItem item = Objects.requireNonNull(palette.item(ruby.type()));
        assertEquals(16, behaviour.get(item, ItemBehaviour.MAX_STACK_SIZE));
        assertEquals(0.98f, behaviour.get(BedrockBlocks.ICE.defaultState(), BlockBehaviour.FRICTION), "vanilla next to it");
    }

    @Test
    void keysAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> latest().get(BedrockBlocks.STONE.defaultState(), ItemBehaviour.MAX_STACK_SIZE));
        assertThrows(IllegalArgumentException.class, () -> latest().blocks(ItemBehaviour.MAX_STACK_SIZE));
        assertSame(BlockBehaviour.FRICTION, BlockBehaviour.key("friction"));
        assertNull(BlockBehaviour.key("max_stack_size"));
        assertNull(latest().get(BedrockBlocks.LADDER.defaultState(), BlockBehaviour.CLIMBABLE), "no source yet");
    }

    @Test
    void thirdPartyValuesAreMarkedAndBdsOnesAreNot() {
        Palette palette = PALETTER.vanilla(LATEST);
        RegisteredBlockState stone = palette.block(BedrockBlocks.STONE.defaultState());
        assertTrue(palette.behaviour().provenance(stone, BlockBehaviour.FRICTION).thirdParty());
        assertFalse(palette.behaviour().provenance(stone, BlockBehaviour.FRICTION).userSet());
        RegisteredItem sword = Objects.requireNonNull(palette.item(BedrockItems.DIAMOND_SWORD));
        assertFalse(palette.behaviour().provenance(sword, ItemBehaviour.MAX_STACK_SIZE).thirdParty(), "own Script API capture");
        assertTrue(palette.behaviour().provenance(sword, ItemBehaviour.ATTACK_DAMAGE).thirdParty());
        assertFalse(Provenance.Source.WIRE.thirdParty());
        assertTrue(Provenance.Source.CLOUDBURST_DATA.ordinal() < Provenance.Source.ALLAY_DATA.ordinal(), "Data ranks above Allay");
        assertTrue(Provenance.Source.WIRE.ordinal() < Provenance.Source.CLOUDBURST_DATA.ordinal(), "BDS evidence ranks above third parties");
        assertFalse(Provenance.Source.BDS_PROBE.thirdParty());
        assertTrue(Provenance.Source.BDS_PROBE.ordinal() < Provenance.Source.CLOUDBURST_DATA.ordinal(), "the probe is direct BDS evidence");
    }

    @Test
    void doorsAreDisputedAndPostProcessedInEveryVersionTheProbeDidNotMeasure() {
        for (DataVersion version : PALETTER.versions()) {
            if (version.equals(LATEST)) {
                continue; // measured: doorsAreWhereTheProbeMeasuredThem
            }
            Palette palette = PALETTER.vanilla(version);
            boolean disputed = false;
            for (RegisteredBlockState state : palette.blocks().states("minecraft:iron_door")) {
                Provenance provenance = palette.behaviour().provenance(state, BlockBehaviour.COLLISION_SHAPE);
                assertEquals(Provenance.Source.CLOUDBURST_DATA, provenance.source(), version.name());
                assertTrue(provenance.postProcessed(), version.name());
                assertTrue(provenance.postProcessing().contains("door_state_correction"), provenance.postProcessing());
                if (provenance.disputed()) {
                    disputed = true;
                    Provenance.Alternative alternative = provenance.alternatives().getFirst();
                    assertEquals(Provenance.Source.ALLAY_DATA, alternative.source());
                    assertTrue(alternative.value() instanceof BlockShape);
                    assertTrue(!alternative.value().equals(palette.behaviour().get(state, BlockBehaviour.COLLISION_SHAPE)));
                }
            }
            assertTrue(disputed, "doors are disputed in " + version.name());
            Provenance stone = palette.behaviour().provenance(palette.block(BedrockBlocks.STONE.defaultState()), BlockBehaviour.COLLISION_SHAPE);
            assertFalse(stone.disputed());
            assertFalse(stone.postProcessed());
        }
    }

    /** Where a door's panel is, by what the probe measured on BDS 1.26.63.1: the 0.1825 thick panel in every state. */
    private static BlockShape probedPanel(String direction, boolean open, boolean hinge) {
        float high = 1 - 0.1825f;
        float low = 0.1825f;
        // closed: the panel is on the side the door faces; open: a quarter turn towards the hinge
        boolean xAxis = open ? direction.equals("east") || direction.equals("west") : direction.equals("north") || direction.equals("south");
        boolean highSide = !open ? direction.equals("north") || direction.equals("east")
            : hinge ? direction.equals("south") || direction.equals("east") : direction.equals("north") || direction.equals("west");
        float min = highSide ? high : 0;
        float max = highSide ? 1 : low;
        return xAxis ? BlockShape.of(min, 0, 0, max, 1, 1) : BlockShape.of(0, 0, min, 1, 1, max);
    }

    @Test
    void doorsAreWhereTheProbeMeasuredThem() {
        Palette palette = PALETTER.vanilla(LATEST);
        int checked = 0;
        for (String door : List.of("minecraft:wooden_door", "minecraft:iron_door", "minecraft:copper_door", "minecraft:crimson_door")) {
            for (RegisteredBlockState state : palette.blocks().states(door)) {
                String direction = state.states().getString("minecraft:cardinal_direction");
                BlockShape expected = probedPanel(direction, state.states().getByte("open_bit") != 0, state.states().getByte("door_hinge_bit") != 0);
                Provenance provenance = palette.behaviour().provenance(state, BlockBehaviour.COLLISION_SHAPE);
                assertEquals(expected, palette.behaviour().get(state, BlockBehaviour.COLLISION_SHAPE), state.toString());
                assertEquals(Provenance.Source.BDS_PROBE, provenance.source(), state.toString());
                assertFalse(provenance.thirdParty());
                assertFalse(provenance.disputed(), "BDS settled the dispute of Data (0.1875) and Allay (0.1825)");
                assertTrue(provenance.postProcessing().startsWith("composed:"), provenance.postProcessing());
                checked++;
            }
        }
        assertEquals(4 * 32, checked);
        // The probe measured the collision, not the outline: that stays what the dumps say, disputed
        RegisteredBlockState door = palette.blocks().states("minecraft:iron_door").getFirst();
        Provenance outline = palette.behaviour().provenance(door, BlockBehaviour.OUTLINE_SHAPE);
        assertEquals(Provenance.Source.CLOUDBURST_DATA, outline.source());
        assertTrue(outline.disputed());
    }

    @Test
    void customBlocksGetTheDefaultsTheProbeMeasured() {
        CustomBlock block = CustomBlock.builder("test:plain").build();
        Palette palette = PALETTER.vanilla(LATEST).extend(PaletteLayer.builder("test").block(block).build());
        Behaviour behaviour = palette.behaviour();
        RegisteredBlockState state = palette.blocks().block("test:plain").defaultState();

        assertEquals(0.6f, behaviour.get(state, BlockBehaviour.FRICTION));
        assertEquals(0f, behaviour.get(state, BlockBehaviour.EXPLOSION_RESISTANCE));
        assertEquals(15, behaviour.get(state, BlockBehaviour.LIGHT_DAMPENING));
        assertEquals(0, behaviour.get(state, BlockBehaviour.LIGHT_EMISSION));
        assertEquals(BlockShape.FULL_CUBE, behaviour.get(state, BlockBehaviour.COLLISION_SHAPE));
        assertEquals(0, behaviour.get(state, BlockBehaviour.BURN_ODDS));
        assertEquals(0, behaviour.get(state, BlockBehaviour.FLAME_ODDS));
        for (BehaviourKey<?> key : List.of(BlockBehaviour.FRICTION, BlockBehaviour.LIGHT_DAMPENING, BlockBehaviour.COLLISION_SHAPE)) {
            Provenance provenance = behaviour.provenance(state, key);
            assertTrue(provenance.bdsDefault(), key.toString());
            assertFalse(provenance.userSet());
            assertTrue(provenance.reference().contains("BDS_PROBE data/bds/1.26.63.1/behaviour-probe/capture.json (BDS 1.26.63.1, Build ID 53340801)"),
                provenance.reference());
        }
        // What a custom block that sets neither has is not known: no selection box and no destroy time were measured
        assertTrue(behaviour.provenance(state, BlockBehaviour.DESTROY_TIME).unknownDefault());
        assertTrue(behaviour.provenance(state, BlockBehaviour.OUTLINE_SHAPE).unknownDefault());
        assertNull(behaviour.get(state, BlockBehaviour.OUTLINE_SHAPE));
    }

    @Test
    void userSetValuesAreToldFromDefaultsAndUnknownOnes() {
        CustomBlock block = CustomBlock.builder("test:block").component(Friction.of(0.1f)).build();
        CustomItem item = CustomItem.builder("test:thing").component(MaxStackSize.of(16)).build();
        Palette palette = PALETTER.vanilla(LATEST).extend(PaletteLayer.builder("test").block(block).item(item).build());
        Behaviour behaviour = palette.behaviour();
        RegisteredBlockState state = palette.blocks().block("test:block").defaultState();

        Provenance friction = behaviour.provenance(state, BlockBehaviour.FRICTION);
        assertTrue(friction.userSet());
        assertTrue(behaviour.isUserSet(state, BlockBehaviour.FRICTION));
        assertFalse(friction.unverified(), "the 1 - value conversion is confirmed by the probe");
        assertEquals(0.9f, behaviour.get(state, BlockBehaviour.FRICTION), 1e-6f, "component 0.1: engine friction 1 - 0.1");
        assertFalse(friction.thirdParty());

        // No component and no known BDS default: no value, and the provenance says why
        assertNull(behaviour.get(state, BlockBehaviour.DESTROY_TIME));
        Provenance destroy = behaviour.provenance(state, BlockBehaviour.DESTROY_TIME);
        assertTrue(destroy.unknownDefault());
        assertEquals(Provenance.Source.UNKNOWN_DEFAULT, destroy.source());
        assertFalse(behaviour.isUserSet(state, BlockBehaviour.DESTROY_TIME));
        assertNull(behaviour.blocks(BlockBehaviour.DESTROY_TIME).get(state));

        RegisteredItem registered = Objects.requireNonNull(palette.item(item.type()));
        assertTrue(behaviour.isUserSet(registered, ItemBehaviour.MAX_STACK_SIZE));
        assertFalse(behaviour.provenance(registered, ItemBehaviour.MAX_STACK_SIZE).unverified());
        assertTrue(behaviour.provenance(registered, ItemBehaviour.ARMOR_VALUE).unknownDefault());

        // Vanilla values are never user-set
        RegisteredBlockState stone = palette.block(BedrockBlocks.STONE.defaultState());
        assertFalse(behaviour.isUserSet(stone, BlockBehaviour.FRICTION));
        assertFalse(behaviour.provenance(stone, BlockBehaviour.FRICTION).unverified());
    }

    @Test
    void aKnownBdsDefaultIsAMatterOfDataOnly() {
        CustomBlock block = CustomBlock.builder("test:block").component(Friction.of(0.1f)).build();
        Palette palette = PALETTER.vanilla(LATEST).extend(PaletteLayer.builder("test").block(block).build());
        BehaviourStore.Builder builder = new BehaviourStore.Builder(List.of(LATEST));
        builder.defaultOf(BlockBehaviour.DESTROY_TIME, 1f, Provenance.Source.NATIVE, "test capture");
        Behaviour behaviour = Behaviour.of(palette, builder.build(), null);
        RegisteredBlockState state = palette.blocks().block("test:block").defaultState();

        assertEquals(1f, behaviour.get(state, BlockBehaviour.DESTROY_TIME));
        Provenance provenance = behaviour.provenance(state, BlockBehaviour.DESTROY_TIME);
        assertEquals(Provenance.Source.BDS_DEFAULT, provenance.source());
        assertTrue(provenance.bdsDefault());
        assertFalse(provenance.userSet());
        assertTrue(provenance.reference().contains("NATIVE") && provenance.reference().contains("test capture"), provenance.reference());
        assertEquals(0.9f, behaviour.get(state, BlockBehaviour.FRICTION), 1e-6f, "what the user set wins");
        assertNull(behaviour.get(state, BlockBehaviour.EXPLOSION_RESISTANCE), "another key without a default stays unknown");
    }

    @Test
    void sessionPalettesShareTheVanillaTables() {
        Palette base = PALETTER.vanilla(LATEST);
        BlockTable<Float> baseTable = base.behaviour().blocks(BlockBehaviour.FRICTION);
        int baseSize = baseTable.size();
        CustomBlock block = CustomBlock.builder("test:block").component(Friction.of(0.1f)).build();
        Palette session = base.extend(PaletteLayer.builder("session").block(block).build());
        BlockTable<Float> sessionTable = session.behaviour().blocks(BlockBehaviour.FRICTION);

        assertSame(baseTable.shared(), sessionTable.shared(), "the vanilla values are the parent table array, not a copy");
        assertEquals(baseSize, baseTable.size(), "the parent table is untouched");
        assertEquals(session.blocks().size(), sessionTable.size());
        assertTrue(sessionTable.size() > baseSize);
        for (int i = 0; i < baseSize; i += 97) {
            assertEquals(baseTable.get(i), sessionTable.get(i));
        }
        RegisteredBlockState custom = session.blocks().block("test:block").defaultState();
        assertEquals(0.9f, sessionTable.get(custom), 1e-6f);
        assertEquals(session.behaviour().get(custom, BlockBehaviour.FRICTION), sessionTable.byRuntimeId(custom.getRuntimeId()));
        assertNull(baseTable.byRuntimeId(custom.getRuntimeId()));

        // Another layer on top of the session still shares the same vanilla array
        CustomBlock other = CustomBlock.builder("test:other").component(Friction.of(0.2f)).build();
        Palette second = session.extend(PaletteLayer.builder("second").block(other).build());
        BlockTable<Float> secondTable = second.behaviour().blocks(BlockBehaviour.FRICTION);
        assertSame(baseTable.shared(), secondTable.shared());
        assertEquals(0.9f, secondTable.byRuntimeId(custom.getRuntimeId()), 1e-6f, "the block of the first session is still there");
        assertEquals(0.8f, secondTable.get(second.blocks().block("test:other").defaultState()), 1e-6f);
        assertEquals(second.behaviour().get(second.blocks().block("test:block").defaultState(), BlockBehaviour.FRICTION),
            secondTable.get(second.blocks().block("test:block").defaultState()));

        // A layer without blocks adds no states: the same array and size
        Palette items = base.extend(PaletteLayer.builder("items").item(CustomItem.builder("test:i").build()).build());
        assertSame(baseTable.shared(), items.behaviour().blocks(BlockBehaviour.FRICTION).shared());
        assertEquals(baseSize, items.behaviour().blocks(BlockBehaviour.FRICTION).size());
    }
}
