package dev.opencollab.paletter;

import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockBlockType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Palette#blockState(String, Map, Lookup)} finds what the NBT overload finds, from boolean, int and string values.
 */
class BlockStateValuesTest {
    private static final Paletter PALETTER = Paletter.bundled();

    @Test
    void everyStateOfTheNewestPaletteRoundTrips() {
        Palette palette = PALETTER.vanilla(PALETTER.latest());
        int states = 0;
        for (BedrockBlockType type : BedrockBlockType.vanillaTypes()) {
            for (BedrockBlockState state : type.states()) {
                assertEquals(state, palette.blockState(state.type().identifier(), state.values(), Lookup.EXACT), state.toString());
                states++;
            }
        }
        assertTrue(states > 10_000);
    }

    @Test
    void valuesAreUnmodifiableBooleansIntsAndStringsInStateOrder() {
        BedrockBlockState state = PALETTER.vanilla(PALETTER.latest())
            .blockState(BlockStates.of("minecraft:wheat", Map.of("growth", 3)), Lookup.EXACT);
        assertEquals(Map.of("growth", 3), state.values());
        assertThrows(UnsupportedOperationException.class, () -> state.values().put("growth", 4));
        for (BedrockBlockType type : BedrockBlockType.vanillaTypes()) {
            for (BedrockBlockState each : type.states()) {
                each.values().values().forEach(value -> assertTrue(value instanceof Boolean || value instanceof Integer || value instanceof String));
                assertEquals(List.copyOf(each.toNbt().getCompound("states").keySet()), List.copyOf(each.values().keySet()), each.toString());
            }
        }
    }

    @Test
    void everyVersionAgreesWithTheNbtOverload() {
        for (DataVersion version : PALETTER.versions()) {
            Palette palette = PALETTER.vanilla(version);
            for (NbtMap state : PALETTER.vanillaData(version).blockStates()) {
                Map<String, Object> values = new LinkedHashMap<>();
                BlockStates.states(state).forEach((name, value) -> values.put(name, value instanceof Byte b ? (Object) (b != 0) : value));
                for (Lookup lookup : Lookup.values()) {
                    assertEquals(palette.blockState(state, lookup), palette.blockState(BlockStates.name(state), values, lookup), version + " " + state);
                }
            }
        }
    }

    @Test
    void renamedBlocksAndUnknownStatesFollowTheLookup() {
        Palette palette = PALETTER.vanilla(PALETTER.latest());
        Map<String, Object> wrong = Map.of("growth", 99);
        assertNull(palette.blockState("minecraft:wheat", wrong, Lookup.EXACT));
        // RENAMED adapts a value the block does not have, like the NBT overload
        assertEquals(palette.blockState(BlockStates.of("minecraft:wheat", Map.of("growth", 99)), Lookup.RENAMED),
            palette.blockState("minecraft:wheat", wrong, Lookup.RENAMED));
        assertNull(palette.blockState("example:unknown", Map.of(), Lookup.RENAMED));
    }

    @Test
    void aWrongValueTypeIsNoMatch() {
        Palette palette = PALETTER.vanilla(PALETTER.latest());
        assertNotNull(palette.blockState("minecraft:wheat", Map.of("growth", 3), Lookup.EXACT));
        assertNull(palette.blockState("minecraft:wheat", Map.of("growth", "3"), Lookup.EXACT));
        assertNull(palette.blockState("minecraft:wheat", Map.of("growth", (byte) 3), Lookup.EXACT));
        assertNull(palette.blockState("minecraft:wheat", Map.of("growth", 3L), Lookup.EXACT));
        assertNull(palette.blockState("minecraft:wheat", Map.of("growth", true), Lookup.EXACT));
        assertNull(palette.blockState("minecraft:wheat", Map.of(), Lookup.EXACT));
        assertNull(palette.blockState("minecraft:wheat", Map.of("growth", 3, "extra", 1), Lookup.EXACT));
        Map<String, Object> bytes = new LinkedHashMap<>(Access.vanilla().blockType("minecraft:hopper").defaultState().values());
        bytes.put("toggle_bit", (byte) 1);
        assertNull(palette.blockState("minecraft:hopper", bytes, Lookup.EXACT));
        bytes.put("toggle_bit", 1);
        assertNull(palette.blockState("minecraft:hopper", bytes, Lookup.EXACT));
    }

    @Test
    void customBlocksAreFoundByTheirOwnedStates() {
        CustomBlock lamp = CustomBlock.builder("example:lamp").property(BlockProperty.bool("example:lit"))
            .property(BlockProperty.strings("example:color", "red", "blue")).menuCategory(CreativeCategory.CONSTRUCTION).build();
        Paletter paletter = Palettes.paletter(PaletteLayer.builder("content").block(lamp).build());
        List<BedrockBlockState> states = new ArrayList<>(lamp.type().states());
        for (DataVersion version : paletter.versions()) {
            Palette palette = paletter.palette(version.protocolVersion());
            for (BedrockBlockState state : states) {
                assertSame(state, palette.blockState("example:lamp", state.values(), Lookup.EXACT));
            }
            assertNull(palette.blockState("example:lamp", Map.of("example:lit", true), Lookup.EXACT));
        }
    }
}
