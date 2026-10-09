package org.geysermc.paletter.palette.item;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

public record ItemPalette(List<Item> items) {
    public static ItemPalette fromJson(JsonNode array) {
        List<Item> items = new ArrayList<>();
        for (JsonNode node : array) {
            items.add(new Item(
                    node.get("name").asString(),
                    node.get("id").asInt(),
                    node.get("version").asInt(),
                    node.get("componentBased").asBoolean()
            ));
        }
        return new ItemPalette(items);
    }
}
