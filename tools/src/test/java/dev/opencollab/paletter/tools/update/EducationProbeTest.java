package dev.opencollab.paletter.tools.update;

import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.internal.creative.RawLayout;
import dev.opencollab.paletter.internal.creative.RawNode;
import dev.opencollab.paletter.internal.creative.RawStack;
import dev.opencollab.paletter.tools.client.CapturedData;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the Education Edition world has that the other lacks, on synthetic captures. */
class EducationProbeTest {

    private static CapturedData capture(List<String> registry, List<String> blockProperties, List<RawStack> creative) {
        Map<String, NbtMap> items = new LinkedHashMap<>();
        registry.forEach(item -> items.put(item, NbtMap.EMPTY));
        Map<String, NbtMap> blocks = new LinkedHashMap<>();
        blockProperties.forEach(block -> blocks.put(block, NbtMap.EMPTY));
        Map<CreativeCategory, List<RawNode>> layout = new EnumMap<>(CreativeCategory.class);
        layout.put(CreativeCategory.ITEMS, List.copyOf(creative));
        return new CapturedData(items, blocks, RawLayout.of(layout));
    }

    @Test
    void keepsWhatOnlyTheEducationWorldHas() {
        CapturedData off = capture(List.of("minecraft:apple", "minecraft:balloon"), List.of("custom:a"),
            List.of(RawStack.of("minecraft:apple"), RawStack.of("minecraft:stone").withBlockState(BlockStates.of("minecraft:stone", Map.of()))));
        CapturedData on = capture(List.of("minecraft:apple", "minecraft:balloon", "minecraft:medicine"), List.of("custom:a", "custom:b"),
            List.of(RawStack.of("minecraft:apple"), RawStack.of("minecraft:stone").withBlockState(BlockStates.of("minecraft:stone", Map.of())),
                RawStack.of("minecraft:balloon"),
                RawStack.of("minecraft:element_1").withBlockState(BlockStates.of("minecraft:element_1", Map.of())),
                RawStack.of("minecraft:element_1").withBlockState(BlockStates.of("minecraft:element_1", Map.of()))));
        EducationProbe.Result result = EducationProbe.diff(off, on);
        // The registry has the balloon either way, but only the education creative inventory shows it
        assertEquals(List.of("minecraft:balloon", "minecraft:element_1", "minecraft:medicine"), result.items());
        assertEquals(List.of("custom:b", "minecraft:element_1"), result.blocks());
        assertEquals("""
            {
              "bdsVersion": "1.0.0",
              "protocol": 100,
              "items": [
                "minecraft:balloon",
                "minecraft:element_1",
                "minecraft:medicine"
              ],
              "blocks": [
                "custom:b",
                "minecraft:element_1"
              ]
            }""", new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result.toJson("1.0.0", 100)));
    }

    @Test
    void identicalCapturesGiveNothing() {
        CapturedData same = capture(List.of("minecraft:apple"), List.of(), List.of(RawStack.of("minecraft:apple")));
        assertTrue(EducationProbe.diff(same, same).isEmpty());
    }
}
