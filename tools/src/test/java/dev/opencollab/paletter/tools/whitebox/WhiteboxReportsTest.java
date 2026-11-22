package dev.opencollab.paletter.tools.whitebox;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.tools.whitebox.WhiteboxCoverage.Captured;
import dev.opencollab.paletter.tools.whitebox.WhiteboxCoverage.Gap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The diff of two builds' facts, the coverage of the facts by captures, and the names read from type information. */
class WhiteboxReportsTest {
    private static final String BEFORE = """
        {"platform": "linux",
         "layout": {"item": {"networkSlot": 4}, "block": {"nameSlot": 2, "networkSlot": 8}},
         "itemComponents": {
           "minecraft:durability": {"network": true, "kind": "reflection", "writes": [], "schema": ["max_durability"], "code": "aaaa0001"},
           "minecraft:block_placer": {"network": true, "kind": "mixed", "writes": ["replaceBlockItem"], "code": "aaaa0002", "hooks": [7]},
           "minecraft:custom_components": {"network": false, "kind": "not-sent"}},
         "itemClassesWithoutName": {"sent": [{"network": true, "kind": "hand-written", "writes": ["autoSucceedOnClient"], "code": "aaaa0003"}], "notSent": 2},
         "blockComponents": {
           "minecraft:friction": {"network": true, "kind": "hand-written", "writes": ["value"], "types": {"value": "float"}, "code": "aaaa0004"},
           "minecraft:geometry": {"network": true, "kind": "mixed", "writes": [], "writesExtra": ["isV1Fullblock", "needsLegacyTopRotation"], "code": "aaaa0005"},
           "minecraft:custom_components": {"network": true, "kind": "hand-written", "writes": ["hasPlayerInteract"], "code": "aaaa0006"},
           "minecraft:tick": {"network": false, "kind": "not-sent"}},
         "traits": {"minecraft:placement_position": {"kind": "hand-written", "writes": ["enabled_states", "block_face"], "code": "aaaa0007"}},
         "envelopes": {"item": {"writes": ["item_properties", "item_tags", "max_stack_size", "block"], "writesNested": []},
                       "block": {"writes": ["molangVersion", "vanilla_block_data", "block_id", "condition", "permutations"], "writesNested": ["slab_block"]}},
         "notes": []}""";

    @Test
    void diffNamesWhatChanged() {
        JsonObject before = JsonParser.parseString(BEFORE).getAsJsonObject();
        assertEquals(List.of(), WhiteboxDiff.diff(before, JsonParser.parseString(BEFORE).getAsJsonObject()));

        JsonObject after = JsonParser.parseString(BEFORE
            .replace("\"minecraft:tick\": {\"network\": false, \"kind\": \"not-sent\"}",
                "\"minecraft:tick\": {\"network\": true, \"kind\": \"hand-written\", \"writes\": [\"interval\"], \"code\": \"bbbb0001\"},"
                    + "\"minecraft:climbable\": {\"network\": true, \"kind\": \"hand-written\", \"writes\": [\"can_climb\"], \"code\": \"bbbb0002\"}")
            .replace("\"writes\": [\"value\"], \"types\": {\"value\": \"float\"}, \"code\": \"aaaa0004\"",
                "\"writes\": [\"value\", \"scale\"], \"types\": {\"value\": \"double\"}, \"code\": \"bbbb0003\"")
            .replace("aaaa0005", "bbbb0004")
            .replace("\"molangVersion\", ", "")
            .replace("\"networkSlot\": 4", "\"networkSlot\": 5")).getAsJsonObject();
        after.getAsJsonObject("itemComponents").remove("minecraft:custom_components");
        assertEquals(List.of(
            "! item component classes: the virtual function slots moved from {\"networkSlot\":4} to {\"networkSlot\":5}",
            "! removed item component minecraft:custom_components",
            "! new block component minecraft:climbable: {\"network\":true,\"kind\":\"hand-written\",\"writes\":[\"can_climb\"],\"code\":\"bbbb0002\"}",
            "! block component minecraft:friction: writes + scale",
            "! block component minecraft:friction: type of value float -> double",
            "~ block component minecraft:geometry: the writer's code changed, its keys did not",
            "! block component minecraft:tick: network false -> true",
            "! block component minecraft:tick: kind not-sent -> hand-written",
            "! block component minecraft:tick: writes + interval",
            "! block envelope: writes - molangVersion"), WhiteboxDiff.diff(before, after));
    }

    @Test
    void coverageListsWhatNoCaptureShows() {
        Captured captured = new Captured();
        for (float friction : new float[]{0.4f, 0.6f}) {
            captured.addBlock(NbtMap.builder()
                .putCompound("components", NbtMap.builder()
                    .putCompound("minecraft:friction", NbtMap.builder().putFloat("value", friction).build())
                    .putCompound("minecraft:geometry", NbtMap.builder().putString("identifier", "a").putByte("isV1Fullblock", (byte) 0).build())
                    .build())
                .putInt("molangVersion", 13)
                .putCompound("vanilla_block_data", NbtMap.builder().putInt("block_id", 10000 + (int) (friction * 10)).build())
                .putList("traits", org.cloudburstmc.nbt.NbtType.COMPOUND, List.of(NbtMap.builder().putString("name", "minecraft:placement_position")
                    .putCompound("enabled_states", NbtMap.builder().putByte("block_face", (byte) (friction > 0.5 ? 1 : 0)).build()).build()))
                .putList("permutations", org.cloudburstmc.nbt.NbtType.COMPOUND, List.of(NbtMap.builder().putString("condition", "q.block_state('a') == " + friction)
                    .putCompound("components", NbtMap.EMPTY).build()))
                .build());
        }
        for (int stack : new int[]{16, 64}) {
            captured.addItem(NbtMap.builder().putCompound("components", NbtMap.builder()
                .putCompound("item_properties", NbtMap.builder().putInt("max_stack_size", stack).build())
                .putList("item_tags", org.cloudburstmc.nbt.NbtType.STRING, List.of())
                .putCompound("minecraft:durability", NbtMap.builder().putInt("max_durability", stack).build())
                .putCompound("minecraft:publisher_on_use_on", NbtMap.builder().putByte("autoSucceedOnClient", (byte) 1).build())
                .build()).build());
        }
        JsonObject mappings = JsonParser.parseString("{\"components\": {\"item:minecraft:block_placer\": {\"windows\": [{\"protocols\": [1], \"status\": \"sent\"}]}}}").getAsJsonObject();

        List<Gap> gaps = WhiteboxCoverage.gaps(JsonParser.parseString(BEFORE).getAsJsonObject(), captured, mappings);
        assertEquals(List.of(
            new Gap("item minecraft:block_placer", "never captured (mixed, writes [replaceBlockItem]; component model: sent)"),
            new Gap("block minecraft:geometry", "`isV1Fullblock` only ever captured as `0`"),
            new Gap("block minecraft:geometry", "`needsLegacyTopRotation` never captured"),
            new Gap("block minecraft:custom_components", "never captured (hand-written, writes [hasPlayerInteract]; not in the component model)"),
            new Gap("item class without a name (captured as item:minecraft:publisher_on_use_on)", "`autoSucceedOnClient` only ever captured as `1`"),
            new Gap("item envelope", "`block` never captured"),
            new Gap("block envelope", "`molangVersion` only ever captured as `13`")), gaps);

        String report = WhiteboxCoverage.report("1.0.0.1", gaps);
        assertTrue(report.startsWith("<!-- Generated by `whitebox-coverage`"), report);
        assertTrue(report.contains("7 gaps"), report);
        assertTrue(report.contains("## block minecraft:geometry\n\n- `isV1Fullblock` only ever captured as `0`\n- `needsLegacyTopRotation` never captured\n"), report);
    }

    @Test
    void classNames() {
        assertEquals("BlockFrictionDescription", Names.itanium("24BlockFrictionDescription"));
        assertEquals("BlockTrait::Connection", Names.itanium("N10BlockTrait10ConnectionE"));
        // Templates stay as the compiler wrote them
        assertEquals("23BlockTriggerDescriptionI15OnPlacedTriggerE", Names.itanium("23BlockTriggerDescriptionI15OnPlacedTriggerE"));
        assertEquals("BlockFrictionDescription", Names.msvc(".?AVBlockFrictionDescription@@"));
        assertEquals("BlockTrait::Connection", Names.msvc(".?AVConnection@BlockTrait@@"));
        assertEquals(".?AV?$TypeSchema@H@cereal@@", Names.msvc(".?AV?$TypeSchema@H@cereal@@"));
    }
}
