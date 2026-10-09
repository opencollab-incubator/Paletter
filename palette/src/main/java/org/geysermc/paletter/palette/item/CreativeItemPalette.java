package org.geysermc.paletter.palette.item;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

// Source: creative_items.json - {groups: [{name, category, icon: {id, block_state_b64?}}, ...],
// items: [{id, groupId, damage?, block_state_b64?}, ...]}. Ordered creative-menu layout; identifiers
// reference items/blocks already defined elsewhere, this doesn't mint new ones.
public record CreativeItemPalette(List<CreativeItemGroup> groups, List<CreativeItemEntry> items) {
    public static CreativeItemPalette fromJson(JsonNode root) {
        List<CreativeItemGroup> groups = new ArrayList<>();
        for (JsonNode groupNode : root.get("groups")) {
            JsonNode icon = groupNode.get("icon");
            groups.add(new CreativeItemGroup(
                    groupNode.get("name").asString(),
                    groupNode.get("category").asString(),
                    icon != null ? icon.get("id").asString() : null
            ));
        }

        List<CreativeItemEntry> items = new ArrayList<>();
        for (JsonNode itemNode : root.get("items")) {
            JsonNode damage = itemNode.get("damage");
            JsonNode blockState = itemNode.get("block_state_b64");
            items.add(new CreativeItemEntry(
                    itemNode.get("id").asString(),
                    itemNode.get("groupId").asInt(),
                    damage != null ? damage.asInt() : null,
                    blockState != null ? blockState.asString() : null
            ));
        }

        return new CreativeItemPalette(List.copyOf(groups), List.copyOf(items));
    }
}
