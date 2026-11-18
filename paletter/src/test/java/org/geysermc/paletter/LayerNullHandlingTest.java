package org.geysermc.paletter;

import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.VoxelShape;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** What the Javadoc of the layer builder and of the custom block builder says about null and about default states. */
class LayerNullHandlingTest {
    @Test
    void overridesRejectNull() {
        assertThrows(NullPointerException.class, () -> new PaletteLayer.Override(null, modifier -> { }));
        assertThrows(NullPointerException.class, () -> new PaletteLayer.Override("minecraft:apple", null));
        PaletteLayer.Builder builder = PaletteLayer.builder("test");
        assertThrows(NullPointerException.class, () -> builder.overrideItem((String) null, modifier -> { }));
        assertThrows(NullPointerException.class, () -> builder.overrideItem("minecraft:apple", null));
        assertThrows(NullPointerException.class, () -> builder.overrideItem(BedrockItems.APPLE, null));
    }

    @Test
    void tagsRejectNullAndKeepNothingOfAFailedCall() {
        PaletteLayer.Builder builder = PaletteLayer.builder("test");
        assertThrows(NullPointerException.class, () -> builder.tag(null, "a:b"));
        assertThrows(NullPointerException.class, () -> builder.tag("my:tag", (String[]) null));
        assertThrows(NullPointerException.class, () -> builder.tag("my:tag", "a:b", null));
        assertEquals(Map.of(), builder.build().tags(), "a failed call adds nothing");
        builder.tag("my:tag", "a:b");
        assertEquals(Map.of("my:tag", List.of("a:b")), builder.build().tags());
    }

    @Test
    void voxelShapesRejectNull() {
        PaletteLayer.Builder builder = PaletteLayer.builder("test");
        VoxelShape shape = VoxelShape.ofBoxes("my:shape", 0, 0, 0, 1, 1, 1);
        assertThrows(NullPointerException.class, () -> builder.voxelShape(null, shape));
        assertThrows(NullPointerException.class, () -> builder.voxelShape("my:shape", null));
    }

    @Test
    void defaultStateOverloadsConvertToThePropertyType() {
        CustomBlock block = CustomBlock.builder("test:states")
            .property(BlockProperty.bool("test:lit"))
            .property(BlockProperty.range("test:level", 0, 3))
            .property(BlockProperty.bool("test:open"))
            .defaultState("test:lit", true)
            .defaultState("test:level", 2)
            .defaultState("test:open", 1)
            .build();
        assertEquals((byte) 1, block.defaultStates().get("test:lit"));
        assertEquals(2, block.defaultStates().get("test:level"));
        assertEquals((byte) 1, block.defaultStates().get("test:open"));
        BedrockBlockState state = block.type().defaultState();
        assertEquals(true, state.values().get("test:lit"));
        assertEquals(2, state.values().get("test:level"));
    }

    @Test
    void defaultStateOverloadsStillRejectWrongValues() {
        assertThrows(IllegalArgumentException.class, () -> CustomBlock.builder("test:states")
            .property(BlockProperty.range("test:level", 0, 3)).defaultState("test:level", true).build());
        assertThrows(IllegalArgumentException.class, () -> CustomBlock.builder("test:states")
            .property(BlockProperty.bool("test:lit")).defaultState("test:lit", 2).build());
        assertThrows(IllegalArgumentException.class, () -> CustomBlock.builder("test:states").defaultState("test:missing", 1).build());
        assertThrows(NullPointerException.class, () -> CustomBlock.builder("test:states").defaultState(null, 1));
    }
}
