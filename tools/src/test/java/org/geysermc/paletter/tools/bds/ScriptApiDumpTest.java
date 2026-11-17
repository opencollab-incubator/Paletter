package org.geysermc.paletter.tools.bds;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScriptApiDumpTest {
    @Test
    void everyPotionEffectGetsAnArrowLootTable() {
        Map<String, String> tables = ScriptApiDump.lootTables(List.of("minecraft:water", "minecraft:long_nightvision"));
        assertEquals(List.of("loot_tables/paletter/arrow_water.json", "loot_tables/paletter/arrow_long_nightvision.json"), List.copyOf(tables.keySet()));

        // The table is the one the add-on loots: one arrow with set_potion of the effect's path (the add-on names the file by it)
        JsonObject entry = JsonParser.parseString(tables.get("loot_tables/paletter/arrow_long_nightvision.json")).getAsJsonObject()
            .getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals("minecraft:arrow", entry.get("name").getAsString());
        JsonObject function = entry.getAsJsonArray("functions").get(0).getAsJsonObject();
        assertEquals("set_potion", function.get("function").getAsString());
        assertEquals("long_nightvision", function.get("id").getAsString());
    }

    @Test
    void noEffectsGiveNoTables() {
        assertEquals(Map.of(), ScriptApiDump.lootTables(List.of()));
    }
}
