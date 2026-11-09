package org.geysermc.paletter.tools.behaviour;

import org.geysermc.paletter.behaviour.BlockBehaviour;
import org.geysermc.paletter.behaviour.BlockShape;
import org.geysermc.paletter.behaviour.ItemBehaviour;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndstoneDumpTest {
    /** The schema before October 2026 (CloudburstMC/Data block_properties.json, Allay block_states_raw.json). */
    private static final String OLD = """
        [{"name": "minecraft:ice", "blockStateHash": 4294967295, "hardness": 0.5, "friction": 0.98, "isSolid": true,
          "lightDampening": 2, "mapColor": "#a0a0ffff", "liquidReactionOnTouch": "BLOCKING",
          "collisionShape": [[0.0, 0.0, 0.0, 1.0, 1.0, 1.0]], "outlineShape": [0.0, 0.0, 0.0, 1.0, 1.0, 1.0],
          "liquidClipShape": [-1.2e36, 0, 0, 0, 0, 0], "newField": 1}]
        """;
    /** CloudburstMC/Data since October 2026: shapes under geometry, six decimal truncated numbers. */
    private static final String NEW = """
        [{"name": "minecraft:oak_stairs", "blockStateHash": 7, "hardness": 1.799999,
          "geometry": {"collision": {"space": "sampled", "boxes": [[0.0, 0.0, 0.0, 1.0, 0.5, 1.0], [0.0, 0.5, 0.0, 0.5, 1.0, 1.0]]},
                       "outline": {"space": "model_base", "box": [0.15, 0.0, 0.15, 0.699999, 0.6, 0.699999], "translation": "native_result"},
                       "visual": [0, 0, 0, 1, 1, 1], "modelPosition": {"samples": []}}}]
        """;

    @Test
    void readsTheOldSchema() throws IOException {
        Values<Integer> values = EndstoneDump.blocks(new StringReader(OLD));
        assertEquals(Map.of(-1, 0.98f), values.byKey.get(BlockBehaviour.FRICTION), "unsigned hashes wrap to int");
        assertEquals(Map.of(-1, true), values.byKey.get(BlockBehaviour.SOLID));
        assertEquals(Map.of(-1, 0xa0a0ffff), values.byKey.get(BlockBehaviour.MAP_COLOR));
        assertEquals(Map.of(-1, BlockShape.FULL_CUBE), values.byKey.get(BlockBehaviour.COLLISION_SHAPE));
        assertEquals(Map.of("newField", 1), values.unmappedFields, "liquidClipShape is ignored, unknown fields reported");
    }

    @Test
    void readsTheGeometrySchemaAndUndoesTheTruncation() throws IOException {
        Values<Integer> values = EndstoneDump.blocks(new StringReader(NEW));
        assertEquals(1.8f, values.byKey.get(BlockBehaviour.DESTROY_TIME).get(7));
        assertEquals(BlockShape.of(0, 0, 0, 1, 0.5f, 1, 0, 0.5f, 0, 0.5f, 1, 1), values.byKey.get(BlockBehaviour.COLLISION_SHAPE).get(7));
        assertEquals(BlockShape.of(0.15f, 0, 0.15f, 0.7f, 0.6f, 0.7f), values.byKey.get(BlockBehaviour.OUTLINE_SHAPE).get(7));
        assertTrue(values.unmappedFields.isEmpty(), values.unmappedFields.toString());
    }

    @Test
    void decodesOnlyWhatATruncationExplains() {
        assertEquals(1.8f, EndstoneDump.decode(1.799999));
        assertEquals(0.9f, EndstoneDump.decode(0.899999), "0.899999f and 0.9f both print so: the shorter");
        assertEquals(0.6f, EndstoneDump.decode(0.6));
        assertEquals(0.375f, EndstoneDump.decode(0.375));
        assertEquals(0.8f, EndstoneDump.decode(0.800000011920929));
        assertEquals(-1f, EndstoneDump.decode(-1));
        assertEquals(0.123456f, EndstoneDump.decode(0.123456));
    }

    @Test
    void readsItems() {
        Values<String> values = EndstoneDump.items(new StringReader("""
            {"minecraft:coal": {"maxStackSize": 64, "furnaceBurnDuration": 1600.0, "id": 1, "tags": []}}
            """));
        assertEquals(Map.of("minecraft:coal", 1600), values.byKey.get(ItemBehaviour.FURNACE_BURN_DURATION));
        assertEquals(Map.of("minecraft:coal", 64), values.byKey.get(ItemBehaviour.MAX_STACK_SIZE));
        assertThrows(IllegalArgumentException.class,
            () -> EndstoneDump.items(new StringReader("{\"minecraft:odd\": {\"furnaceBurnDuration\": 0.5}}")));
    }

    @Test
    void recognisesBlockDumps() throws IOException {
        assertTrue(EndstoneDump.isBlockDump(new StringReader(OLD)));
        assertFalse(EndstoneDump.isBlockDump(new StringReader("[{\"name\": \"minecraft:x\", \"properties\": {}}]")));
        assertFalse(EndstoneDump.isBlockDump(new StringReader("{}")));
    }
}
