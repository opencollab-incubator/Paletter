package dev.opencollab.paletter.tools.behaviour;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.behaviour.BlockBehaviour;
import dev.opencollab.paletter.behaviour.ItemBehaviour;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScriptApiValuesTest {
    /** Two states of a block with a legacy alias state, a tinted block, and two items; columns as the behaviour dump writes them. */
    private static final JsonObject DUMP = JsonParser.parseString("""
        {"states": [["minecraft:door", {"open_bit": false, "direction": 0}], ["minecraft:door", {"open_bit": true, "direction": 0}],
                    ["minecraft:leaves", {}]],
         "permutation": {"BlockPermutation.isLiquidBlocking(Water)": [[true, false], [0, 0, 1]]},
         "placed": {
           "Block.isSolid": [[false, null], [0, 1, 1]],
           "Block.getMapColor": [[{"red": 1, "green": 0, "blue": 0, "alpha": 1}, {"red": 0, "green": 0.2, "blue": 0, "alpha": 1}], [0, 0, 1]],
           "BlockMapColorComponent.color": [[null, {"red": 0, "green": 1, "blue": 0, "alpha": 1}], [0, 0, 1]],
           "Block.liquidCanFlowFromDirection(Water,Down)": [[true], [0, 0, 0]],
           "Block.liquidCanFlowFromDirection(Water,Up)": [[false], [0, 0, 0]],
           "Block.liquidCanFlowFromDirection(Water,North)": [[true], [0, 0, 0]],
           "Block.liquidCanFlowFromDirection(Water,South)": [[false], [0, 0, 0]],
           "Block.liquidCanFlowFromDirection(Water,West)": [[false], [0, 0, 0]],
           "Block.liquidCanFlowFromDirection(Water,East)": [[false], [0, 0, 0]]},
         "items": {"minecraft:sword": {"ItemStack.maxAmount": 1, "ItemDurabilityComponent.maxDurability": 250},
                   "minecraft:stick": {"ItemStack.maxAmount": 64}},
         "world": {"dependent": {"Block.getMapColor": {"blocks": ["minecraft:leaves"]}}}}
        """).getAsJsonObject();

    private static NbtMap state(String name, NbtMap states) {
        return NbtMap.builder().putString("name", name).putCompound("states", states).build();
    }

    @Test
    void readsTheKeysOfScriptApiSources() {
        Map<Integer, NbtMap> palette = Map.of(
            1, state("minecraft:door", NbtMap.builder().putByte("open_bit", (byte) 0).build()),
            2, state("minecraft:door", NbtMap.builder().putByte("open_bit", (byte) 1).build()),
            3, state("minecraft:leaves", NbtMap.EMPTY));
        List<String> log = new ArrayList<>();
        List<Values<Integer>> groups = new ScriptApiValues(DUMP, log).blocks(palette, "test");

        Map<Object, Object> merged = new java.util.HashMap<>();
        groups.forEach(group -> group.byKey.forEach((key, values) -> merged.put(key, values)));
        assertEquals(Map.of(1, false), merged.get(BlockBehaviour.SOLID), "null is no value: the open door was not measured");
        assertEquals(Map.of(1, true, 2, true, 3, false), merged.get(BlockBehaviour.LIQUID_BLOCKING));
        // the component's base colour where the block has it; getMapColor only for blocks the world check did not find tinted
        assertEquals(Map.of(1, 0xff0000ff, 2, 0xff0000ff, 3, 0x00ff00ff), merged.get(BlockBehaviour.MAP_COLOR));
        assertEquals(Map.of(1, 0b101, 2, 0b101, 3, 0b101), merged.get(BlockBehaviour.LIQUID_FLOW_DIRECTIONS), "down and north");
        // keys covering different states are different captures: a capture never confirms a value it does not have
        assertEquals(Set.of(Set.of(1), Set.of(1, 2, 3)), Set.copyOf(groups.stream().map(group -> group.ids).map(Set::copyOf).toList()));
        assertEquals(List.of(), log);
    }

    /**
     * Two items as the committed dump of BDS 1.26.63.1 (data/bds/1.26.63.1/behaviour-dump.json.gz) has them: the apple is
     * the only one of its 1623 items whose ItemStack.getComponents() lists ItemFoodComponent, the bread (food too, in the
     * game) has no such entry. The importer reads every item that has the properties; the bread's are not in the dump.
     */
    @Test
    void readsFoodOfTheItemsTheDumpHasItFor() {
        JsonObject dump = JsonParser.parseString("""
            {"items": {
              "minecraft:apple": {"ItemStack.maxAmount": 64, "ItemStack.isStackable": true, "ItemStack.weight": 1,
                "ItemFoodComponent.nutrition": 4, "ItemFoodComponent.saturationModifier": 0.30000001192092896,
                "ItemFoodComponent.canAlwaysEat": false, "ItemFoodComponent.usingConvertsTo": "",
                "ItemCompostableComponent.compostingChance": 65},
              "minecraft:bread": {"ItemStack.maxAmount": 64, "ItemStack.isStackable": true, "ItemStack.weight": 1,
                "ItemCompostableComponent.compostingChance": 85}}}
            """).getAsJsonObject();
        List<String> log = new ArrayList<>();
        List<Values<String>> groups = new ScriptApiValues(dump, log).items(Set.of("minecraft:apple", "minecraft:bread"), "test");
        Map<Object, Object> merged = new java.util.HashMap<>();
        groups.forEach(group -> group.byKey.forEach((key, values) -> merged.put(key, values)));
        assertEquals(Map.of("minecraft:apple", 4), merged.get(ItemBehaviour.NUTRITION));
        assertEquals(Map.of("minecraft:apple", 0.3f), merged.get(ItemBehaviour.SATURATION_MODIFIER));
        assertEquals(Map.of("minecraft:apple", false), merged.get(ItemBehaviour.CAN_ALWAYS_EAT));
        assertEquals(Map.of("minecraft:apple", 65, "minecraft:bread", 85), merged.get(ItemBehaviour.COMPOST_CHANCE));
        assertEquals(List.of(), log);
    }

    @Test
    void readsItemsWithAbsentDurabilityAsZero() {
        List<Values<String>> groups = new ScriptApiValues(DUMP, new ArrayList<>()).items(Set.of("minecraft:sword", "minecraft:stick"), "test");
        Map<Object, Object> merged = new java.util.HashMap<>();
        groups.forEach(group -> group.byKey.forEach((key, values) -> merged.put(key, values)));
        assertEquals(Map.of("minecraft:sword", 250, "minecraft:stick", 0), merged.get(ItemBehaviour.MAX_DAMAGE));
        assertEquals(Map.of("minecraft:sword", 1, "minecraft:stick", 64), merged.get(ItemBehaviour.MAX_STACK_SIZE));
        assertNull(merged.get(ItemBehaviour.WEIGHT));
    }
}
