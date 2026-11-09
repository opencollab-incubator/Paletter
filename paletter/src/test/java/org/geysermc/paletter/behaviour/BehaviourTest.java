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
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.item.component.MaxStackSize;
import org.geysermc.paletter.vanilla.BedrockBlocks;
import org.geysermc.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

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
        assertNull(behaviour.get(state, BlockBehaviour.EXPLOSION_RESISTANCE), "not on the wire");
        assertEquals(Provenance.Source.COMPONENT, behaviour.provenance(state, BlockBehaviour.FRICTION).source());
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
}
