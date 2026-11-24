package dev.opencollab.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.internal.block.BlockRegistry;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.block.component.Geometry;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.item.BedrockItemStack;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        BedrockBlockState state = PALETTER.vanilla(PALETTER.latest()).blockState(BlockStates.of(identifier, states), Lookup.RENAMED);
        assertNotNull(state, identifier);
        return state;
    }

    private static void assertSameAsResolve(Palette palette, String item, BedrockBlockState state) {
        BedrockItemStack stack = BedrockItemStack.builder(BedrockItemVariant.fromIdentifier(item, 0)).count(3).blockState(state).build();
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
        BedrockItemStack stack = BedrockItemStack.builder(BedrockItemVariant.fromIdentifier("minecraft:oak_log", 0)).blockState(sideways).build();
        assertEquals(palette.runtimeId(sideways), palette.encode(stack).getBlockDefinition().getRuntimeId());
    }

    @Test
    void anOlderVersionEncodesLikeItsResolvedNbtToo() {
        Palette palette = PALETTER.palette(OLDEST);
        BlockRegistry blocks = palette.blocks();
        assertSameAsResolve(palette, "minecraft:oak_planks", state("minecraft:oak_planks", Map.of()));
        assertSameAsResolve(palette, "minecraft:oak_log", state("minecraft:oak_log", Map.of("pillar_axis", "x")));

        // A canonical state this version has no block for: the table would say the default fallback, the NBT says none
        NbtMap missing = blocks.missingCanonical().getFirst();
        BedrockBlockState missingState = PALETTER.vanilla(PALETTER.latest()).blockState(missing, Lookup.RENAMED);
        assertNotNull(missingState);
        assertTrue(blocks.isMissing(missingState.canonicalIn(blocks.canonical())));
        assertSameAsResolve(palette, "minecraft:oak_planks", missingState);

        // A canonical state this version only has by resolving it: it is not an exact state, and not missing
        NbtMap adapted = null;
        for (int id = 0; id < blocks.canonical().size() && adapted == null; id++) {
            NbtMap state = blocks.canonical().state(id);
            adapted = !blocks.isMissing(id) && blocks.exact(state) == null ? state : null;
        }
        assertNotNull(adapted, "the oldest version resolves some states of the newest");
        assertSameAsResolve(palette, "minecraft:oak_planks", PALETTER.vanilla(PALETTER.latest()).blockState(adapted, Lookup.RENAMED));
    }

    @Test
    void customBlockStatesEncodeLikeTheirResolvedNbt() {
        PaletteLayer layer = PaletteLayer.builder("session").block(LAMP).build();
        Palette base = PALETTER.palette(PALETTER.latest().protocolVersion());
        Palette session = base.extend(layer);
        BedrockBlockState lit = LAMP.type().states().getLast();
        BedrockItemVariant lamp = BedrockItemVariant.fromIdentifier(LAMP.identifier(), 0);
        BedrockItemStack stack = BedrockItemStack.builder(lamp).count(2).blockState(lit).build();

        ItemData data = session.encode(stack);
        assertEquals(viaResolve(session, stack), data);
        assertEquals(session.runtimeId(lit), data.getBlockDefinition().getRuntimeId());
        assertNotEquals(session.runtimeId(LAMP.type().defaultState()), data.getBlockDefinition().getRuntimeId());

        // Not in the palette: no canonical id, so the fallbacks decide, as before
        assertEquals(viaResolve(base, stack), base.encode(stack));
        assertEquals(base.encode(stack.withBlockState(null)), base.encode(stack));
    }
}
