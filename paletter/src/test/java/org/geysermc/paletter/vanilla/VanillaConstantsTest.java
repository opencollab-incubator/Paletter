package org.geysermc.paletter.vanilla;

import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.internal.item.ResolvedItem;
import org.geysermc.paletter.Ids;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.Fallbacks;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.Palettes;
import org.geysermc.paletter.PaletteLayer;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.internal.CanonicalBlocks;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.creative.ItemMatcher;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.internal.item.ItemVariant;
import org.geysermc.paletter.vanilla.state.CardinalDirection;
import org.geysermc.paletter.vanilla.state.PillarAxis;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaConstantsTest {
    private static final Paletter PALETTER = Paletter.bundled();

    @Test
    void everyStateRoundTrips() {
        CanonicalBlocks canonical = Ids.canonicalBlocks(PALETTER);
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion());
        for (int id = 0; id < canonical.vanillaSize(); id++) {
            NbtMap nbt = canonical.state(id);
            BedrockBlockType type = BedrockBlockType.vanilla(BlockStates.name(nbt));
            assertNotNull(type, nbt.toString());
            // Build it from the default state with typed values
            BedrockBlockType.StateBuilder builder = type.state();
            for (BedrockState<?> property : type.properties()) {
                set(builder, property, BlockStates.states(nbt).get(property.name()));
            }
            BedrockBlockState state = builder.build();
            assertEquals(id, Ids.block(PALETTER, state), nbt.toString());
            assertEquals(nbt, state.toNbt());
            assertSame(state, BedrockBlockState.vanilla(id));
            assertSame(state, BedrockBlockState.vanilla(nbt));
            assertEquals(id, Ids.block(palette, state));
            assertEquals(Ids.runtimeId(palette, id), palette.runtimeId(state));
            assertEquals(nbt, BlockStates.normalize(palette.block(state).getState()));
        }
    }

    private static <T> void set(BedrockBlockType.StateBuilder builder, BedrockState<T> property, Object nbt) {
        builder.with(property, property.fromNbt(nbt));
    }

    @Test
    void randomWithEqualsPaletteLookup() {
        CanonicalBlocks canonical = Ids.canonicalBlocks(PALETTER);
        Random random = new Random(42);
        List<BedrockBlockType> types = BedrockBlockType.vanillaTypes().stream().filter(t -> !t.properties().isEmpty()).toList();
        for (int i = 0; i < 200_000; i++) {
            BedrockBlockType type = types.get(random.nextInt(types.size()));
            List<BedrockBlockState> states = type.states();
            BedrockBlockState state = states.get(random.nextInt(states.size()));
            for (int changes = random.nextInt(3) + 1; changes > 0; changes--) {
                BedrockState<?> property = type.properties().get(random.nextInt(type.properties().size()));
                Object nbt = randomValue(type, property, random);
                BedrockBlockState changed = with(state, property, nbt);
                NbtMap expected = BlockStates.with(state.toNbt(), property.name(), nbt);
                assertEquals(canonical.index(expected), Ids.block(PALETTER, changed), expected.toString());
                assertSame(changed, BedrockBlockState.vanilla(Ids.block(PALETTER, changed)));
                state = changed;
            }
        }
    }

    private static <T> Object randomValue(BedrockBlockType type, BedrockState<T> property, Random random) {
        List<T> values = type.values(property);
        return property.toNbt(values.get(random.nextInt(values.size())));
    }

    private static <T> BedrockBlockState with(BedrockBlockState state, BedrockState<T> property, Object nbt) {
        return state.with(property, property.fromNbt(nbt));
    }

    @Test
    void typedConstants() {
        BedrockBlockState door = BedrockBlocks.WOODEN_DOOR.state()
            .with(BedrockStates.CARDINAL_DIRECTION, CardinalDirection.NORTH)
            .with(BedrockStates.OPEN_BIT, true)
            .build();
        NbtMap states = BlockStates.states(door.toNbt());
        assertEquals("north", states.getString("minecraft:cardinal_direction"));
        assertEquals((byte) 1, states.getByte("open_bit"));
        assertEquals(CardinalDirection.NORTH, door.get(BedrockStates.CARDINAL_DIRECTION));
        assertTrue(door.get(BedrockStates.OPEN_BIT));
        assertSame(door, door.with(BedrockStates.OPEN_BIT, false).with(BedrockStates.OPEN_BIT, true));
        assertEquals(Ids.block(PALETTER, door.toNbt()), Ids.block(PALETTER, door));

        BedrockBlockState log = BedrockBlocks.OAK_LOG.defaultState().with(BedrockStates.PILLAR_AXIS, PillarAxis.X);
        assertEquals(PillarAxis.X, log.get(BedrockStates.PILLAR_AXIS));
        assertEquals(List.of(0, 1, 2, 3, 4, 5), BedrockStates.FACING_DIRECTION.values());
        assertSame(BedrockStates.AGE, BedrockStates.byName("age"));
        assertTrue(BedrockBlocks.STONE.isAvailable());
        assertSame(BedrockBlocks.STONE, BedrockBlockType.vanilla("minecraft:stone"));
        assertSame(BedrockBlocks.STONE, BedrockBlocks.STONE.defaultState().type());
    }

    @Test
    void invalidValuesAreRejected() {
        BedrockBlockState stone = BedrockBlocks.STONE.defaultState();
        assertThrows(IllegalArgumentException.class, () -> stone.with(BedrockStates.OPEN_BIT, true));
        assertThrows(IllegalArgumentException.class, () -> stone.get(BedrockStates.OPEN_BIT));
        // A block accepts only its own values of a shared state
        BedrockBlockType wheat = BedrockBlocks.WHEAT;
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), wheat.values(BedrockStates.GROWTH));
        assertThrows(IllegalArgumentException.class, () -> wheat.defaultState().with(BedrockStates.GROWTH, 8));
        assertThrows(IllegalArgumentException.class, () -> wheat.defaultState().with(BedrockStates.AGE, 1));
    }

    @Test
    void withDoesNotAllocate() {
        var bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean threads) || !threads.isThreadAllocatedMemorySupported()) {
            return;
        }
        BedrockBlockState door = BedrockBlocks.WOODEN_DOOR.defaultState();
        BedrockBlockState state = door;
        for (int i = 0; i < 100_000; i++) {
            state = state.with(BedrockStates.OPEN_BIT, (i & 1) == 0).with(BedrockStates.CARDINAL_DIRECTION, CardinalDirection.EAST)
                .with(BedrockStates.UPPER_BLOCK_BIT, true);
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        int sum = 0;
        for (int i = 0; i < 1_000_000; i++) {
            state = state.with(BedrockStates.OPEN_BIT, (i & 1) == 0).with(BedrockStates.CARDINAL_DIRECTION, CardinalDirection.EAST)
                .with(BedrockStates.UPPER_BLOCK_BIT, (i & 2) == 0);
            sum += Ids.block(PALETTER, state);
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertTrue(sum != 0);
        assertTrue(allocated < 100_000, "3M with() calls allocated " + allocated + " bytes");
    }

    @Test
    void items() {
        assertEquals(Ids.item(PALETTER, ItemVariant.of("minecraft:apple")), Ids.item(PALETTER, BedrockItems.APPLE));
        assertEquals(Ids.item(PALETTER, ItemVariant.of("minecraft:potion", 5)), Ids.item(PALETTER, BedrockItems.POTION.variant(5)));
        assertSame(BedrockItems.POTION.variant(5), BedrockItems.POTION.variant(5));
        assertNull(BedrockItems.POTION.variant(9999));
        assertTrue(BedrockItems.POTION.hasVariant(5));
        assertFalse(BedrockItems.POTION.hasVariant(9999));
        assertEquals(-1, Ids.item(PALETTER, BedrockItems.POTION.variantOf(9999)));
        assertTrue(BedrockItems.POTION.variants().size() > 40);
        assertSame(BedrockItems.APPLE, BedrockItemType.vanilla("minecraft:apple"));

        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion());
        assertEquals("minecraft:apple", palette.item(BedrockItems.APPLE).getIdentifier());
        assertEquals(5, palette.encode(BedrockItemStack.of(BedrockItems.POTION.variant(5))).getDamage());
        // Any aux value encodes; only variants with an id are recognized when decoding
        assertEquals(9999, palette.encode(BedrockItemStack.of(BedrockItems.POTION.variantOf(9999))).getDamage());
        Palette withVariant = palette.extend(PaletteLayer.builder("variant").variant(BedrockItems.POTION.variantOf(9999)).build());
        assertEquals(9999, withVariant.decode(withVariant.encode(BedrockItemStack.of(BedrockItems.POTION.variantOf(9999)))).variant().aux());
        assertTrue(ItemMatcher.of(BedrockItems.POTION.variant(5)).matches(BedrockItemStack.of(BedrockItems.POTION.variantOf(5))));
        assertFalse(ItemMatcher.of(BedrockItems.POTION.variant(5)).matches(BedrockItemStack.of(BedrockItems.POTION.variantOf(6))));
    }

    @Test
    void everyBlockAndItemHasOneConstant() throws IllegalAccessException {
        Map<String, String> blocks = constants(BedrockBlocks.class);
        for (BedrockBlockType type : BedrockBlockType.vanillaTypes()) {
            assertTrue(blocks.containsKey(type.identifier()), type.identifier());
        }
        assertEquals(BedrockBlockType.vanillaTypes().size(), blocks.size());
        Map<String, String> items = constants(BedrockItems.class);
        for (BedrockItemType type : BedrockItemType.vanillaTypes()) {
            assertTrue(items.containsKey(type.identifier()), type.identifier());
        }
        assertEquals(BedrockItemType.vanillaTypes().size(), items.size());
        // Every state name of the data has a constant, and every block uses the constants
        for (BedrockBlockType type : BedrockBlockType.vanillaTypes()) {
            for (BedrockState<?> state : type.properties()) {
                assertSame(BedrockStates.byName(state.name()), state, type + " " + state);
            }
        }
        assertTrue(VanillaIndex.get().tableBlocks.isEmpty(), "blocks needing a lookup table: " + VanillaIndex.get().tableBlocks);
    }

    /** Identifier to constant name of the non deprecated constants; fails on two constants for one identifier. */
    private static Map<String, String> constants(Class<?> type) throws IllegalAccessException {
        Map<String, String> constants = new HashMap<>();
        for (Field field : type.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(Deprecated.class)) {
                continue;
            }
            Object value = field.get(null);
            String identifier = value instanceof BedrockBlockType block ? block.identifier() : ((BedrockItemType) value).identifier();
            String existing = constants.put(identifier, field.getName());
            assertNull(existing, identifier + " has two constants: " + existing + " and " + field.getName());
        }
        return constants;
    }

    @Test
    void absentConstantsResolveViaFallback() {
        // What a deprecated constant of a removed block or item is: registered by identifier, not in the data
        BedrockBlockType removed = VanillaTypes.block("minecraft:paletter_test_removed_block");
        assertFalse(removed.isAvailable());
        assertEquals("minecraft:paletter_test_removed_block", removed.identifier());
        assertSame(BedrockBlocks.UNKNOWN, removed.resolved());
        assertSame(BedrockBlocks.UNKNOWN.defaultState(), removed.defaultState());

        BedrockBlockType toStone = VanillaTypes.block("minecraft:paletter_test_removed_stone", "minecraft:stone");
        assertSame(BedrockBlocks.STONE.defaultState(), toStone.defaultState());

        BedrockItemType removedItem = VanillaTypes.item("minecraft:paletter_test_removed_item", "minecraft:stick");
        assertFalse(removedItem.isAvailable());
        assertEquals(Ids.item(PALETTER, BedrockItems.STICK), Ids.item(PALETTER, removedItem));
    }

    @Test
    void customBlocksAndItemsWorkLikeVanilla() {
        CustomBlock lamp = CustomBlock.builder("example:lamp")
            .property(BlockProperty.bool("example:lit"))
            .property(BlockProperty.range("example:level", 0, 3))
            .property(BlockProperty.strings("minecraft:cardinal_direction", "north", "south", "east", "west"))
            .defaultState("example:level", 2)
            .build();
        CustomItem ruby = CustomItem.builder("example:ruby").creative(CreativePlacement.loose(CreativeCategory.ITEMS)
            .after(BedrockItems.DIAMOND)).build();
        PaletteLayer layer = PaletteLayer.builder("custom").block(lamp).item(ruby)
            .overrideItem(BedrockItems.CAKE, item -> { })
            .build();
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), layer);

        BedrockBlockType type = lamp.type();
        assertTrue(type.isCustom());
        assertEquals(lamp.states().size(), type.stateCount());
        assertEquals(2, type.defaultState().get(IntState.range("example:level", 0, 3)));
        for (BedrockBlockState state : type.states()) {
            assertEquals(-1, Ids.block(PALETTER, state));
            int canonical = Ids.block(palette, state.toNbt());
            assertTrue(canonical >= Ids.canonicalBlocks(palette).vanillaSize());
            assertEquals(canonical, Ids.block(palette, state));
            assertEquals(Ids.runtimeId(palette, canonical), palette.runtimeId(state));
        }
        // The vanilla enum state works on a custom property of the same name
        BedrockBlockState east = type.defaultState().with(BedrockStates.CARDINAL_DIRECTION, CardinalDirection.EAST)
            .with(BoolState.of("example:lit"), true);
        assertEquals("east", BlockStates.states(east.toNbt()).getString("minecraft:cardinal_direction"));
        assertEquals(CardinalDirection.EAST, east.get(BedrockStates.CARDINAL_DIRECTION));
        assertEquals(lamp.type(), type);

        // Not in a palette without the layer
        Palette vanilla = PALETTER.palette(PALETTER.latest().protocolVersion());
        assertEquals(-1, Ids.block(vanilla, east));
        assertThrows(IllegalArgumentException.class, () -> vanilla.runtimeId(east));

        assertEquals(Ids.item(palette, ItemVariant.of("example:ruby")), Ids.item(palette, ruby.type()));
        assertEquals("example:ruby", palette.item(ruby.type()).getIdentifier());
        assertEquals(Ids.item(palette, ItemVariant.of("example:lamp")), Ids.item(palette, lamp.itemType()));
    }

    /** The README example. */
    @Test
    void constantsWhereIdentifiersAreAccepted() {
        PaletteLayer layer = PaletteLayer.builder("server")
            .overrideItem(BedrockItems.CAKE, item -> item.components(org.geysermc.paletter.item.component.MaxStackSize.of(1)))
            .item(CustomItem.builder("example:ruby")
                .component(org.geysermc.paletter.item.component.Repairable.builder()
                    .repairItems(org.geysermc.paletter.item.component.Repairable.RepairItems.builder().items(BedrockItems.DIAMOND).build())
                    .build())
                .component(org.geysermc.paletter.item.component.Digger.builder()
                    .destroySpeeds(org.geysermc.paletter.item.component.Digger.DestroySpeeds.builder()
                        .block(org.geysermc.paletter.item.component.Digger.Block.of(BedrockBlocks.STONE)).speed(4).build())
                    .build())
                .creative(CreativePlacement.loose(CreativeCategory.ITEMS).after(BedrockItems.DIAMOND))
                .build())
            .creative(editor -> editor.remove(ItemMatcher.of(BedrockItems.EMPTY_MAP.variant(2))))
            .build();
        Paletter server = Paletter.builder().fallbacks(Fallbacks.builder().block(BedrockBlocks.ICICLE, BedrockBlocks.STONE).build())
            .layer(layer).build();
        Palette palette = server.palette(PALETTER.versions().getFirst().protocolVersion());
        assertNotNull(palette.item(BedrockItems.CAKE));
        boolean[] emptyMap2 = new boolean[1];
        palette.creativeLayout().forEachStack(stack -> emptyMap2[0] |= ItemMatcher.of(BedrockItems.EMPTY_MAP.variant(2)).matches(stack));
        assertFalse(emptyMap2[0]);
        boolean[] ruby = new boolean[1];
        palette.creativeLayout().forEachStack(stack -> ruby[0] |= stack.type().identifier().equals("example:ruby"));
        assertTrue(ruby[0]);
        assertEquals(Ids.block(PALETTER, BedrockBlockState.vanilla(BlockStates.of("minecraft:stone"))), Ids.block(PALETTER, BlockStates.of("minecraft:stone")));
    }

    @Test
    void initialization() {
        // Class init time and data linking, for the record; the first use loads the newest bundled data
        long start = System.nanoTime();
        BoolState open = BedrockStates.OPEN_BIT;
        long states = System.nanoTime() - start;
        start = System.nanoTime();
        BedrockBlockType stone = BedrockBlocks.STONE;
        long blocks = System.nanoTime() - start;
        start = System.nanoTime();
        BedrockItemType apple = BedrockItems.APPLE;
        long items = System.nanoTime() - start;
        start = System.nanoTime();
        stone.defaultState();
        long linked = System.nanoTime() - start;
        start = System.nanoTime();
        BedrockBlocks.WOODEN_DOOR.state().with(open, true).build();
        long door = System.nanoTime() - start;
        System.out.printf("class init: BedrockStates %.1f ms, BedrockBlocks %.1f ms, BedrockItems %.1f ms (%s, %s, %s); "
            + "first state (loads and links the newest data) %.1f ms; next type's states %.2f ms%n",
            states / 1e6, blocks / 1e6, items / 1e6, stone, apple, open, linked / 1e6, door / 1e6);
    }
}
