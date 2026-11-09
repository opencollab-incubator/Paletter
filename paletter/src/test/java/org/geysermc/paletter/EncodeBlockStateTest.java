package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.block.component.Geometry;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Encoding a stack with a block state looks canonical states up by id. It has to give what resolving the state's NBT
 * gives, which is what it does for every state that is not canonical (a custom block of another layer) and what the
 * lookup replaces for those that are.
 */
class EncodeBlockStateTest {
    private static final Paletter PALETTER = Paletter.bundled();
    private static final int OLDEST = 2168;
    private static final CustomBlock LAMP = CustomBlock.builder("encode:lamp")
        .property(BlockProperty.bool("encode:lit"))
        .component(Geometry.of("minecraft:geometry.full_block"))
        .build();

    /** The stack encoded without its block state, with the block of its state resolved from the NBT. */
    private static ItemData viaResolve(Palette palette, BedrockItemStack stack) {
        ItemData plain = palette.encode(stack.withBlockState(null));
        RegisteredBlockState own = palette.blocks().resolve(stack.blockState().toNbt()).block();
        return own == null || plain.getBlockDefinition() == null ? plain : plain.toBuilder().blockDefinition(own).build();
    }

    private static BedrockBlockState state(String identifier, Map<String, Object> states) {
        BedrockBlockState state = PALETTER.block(BlockStates.of(identifier, states));
        assertNotNull(state, identifier);
        return state;
    }

    private static void assertSameAsResolve(Palette palette, String item, BedrockBlockState state) {
        BedrockItemStack stack = new BedrockItemStack(BedrockItemVariant.of(item, 0), 3, null, state);
        assertEquals(viaResolve(palette, stack), palette.encode(stack), item + " " + state);
    }

    @Test
    void vanillaBlockStatesEncodeLikeTheirResolvedNbt() {
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion());
        assertSameAsResolve(palette, "minecraft:oak_planks", state("minecraft:oak_planks", Map.of()));
        assertSameAsResolve(palette, "minecraft:oak_log", state("minecraft:oak_log", Map.of("pillar_axis", "x")));
        assertSameAsResolve(palette, "minecraft:oak_log", state("minecraft:oak_log", Map.of("pillar_axis", "z")));
        // A state of another block than the item's: the stack's state wins, as it always did
        assertSameAsResolve(palette, "minecraft:oak_log", state("minecraft:stone", Map.of()));
    }

    @Test
    void theBlockOfTheStateIsSent() {
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion());
        BedrockBlockState sideways = state("minecraft:oak_log", Map.of("pillar_axis", "x"));
        BedrockItemStack stack = new BedrockItemStack(BedrockItemVariant.of("minecraft:oak_log", 0), 1, null, sideways);
        assertEquals(palette.runtimeId(sideways), palette.encode(stack).getBlockDefinition().getRuntimeId());
    }

    @Test
    void anOlderVersionEncodesLikeItsResolvedNbtToo() {
        // It has canonical states without a block of their own, so it does not use the id lookup
        Palette palette = PALETTER.palette(OLDEST);
        assertSameAsResolve(palette, "minecraft:oak_planks", state("minecraft:oak_planks", Map.of()));
        assertSameAsResolve(palette, "minecraft:oak_log", state("minecraft:oak_log", Map.of("pillar_axis", "x")));
    }

    @Test
    void customBlockStatesEncodeLikeTheirResolvedNbt() {
        PaletteLayer layer = PaletteLayer.builder("session").blockItem(LAMP.identifier(), LAMP.item(), null).block(LAMP).build();
        Palette base = PALETTER.palette(PALETTER.latest().protocolVersion());
        Palette session = base.extend(layer);
        BedrockBlockState lit = LAMP.type().states().getLast();
        BedrockItemVariant lamp = BedrockItemVariant.of(LAMP.identifier(), 0);
        BedrockItemStack stack = new BedrockItemStack(lamp, 2, null, lit);

        ItemData data = session.encode(stack);
        assertEquals(viaResolve(session, stack), data);
        assertEquals(session.runtimeId(lit), data.getBlockDefinition().getRuntimeId());
        assertNotEquals(session.runtimeId(LAMP.type().defaultState()), data.getBlockDefinition().getRuntimeId());

        // Not in the palette: no canonical id, so the fallbacks decide, as before
        assertEquals(viaResolve(base, stack), base.encode(stack));
        assertEquals(base.encode(stack.withBlockState(null)), base.encode(stack));
    }
}
