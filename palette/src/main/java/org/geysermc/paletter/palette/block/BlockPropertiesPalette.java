package org.geysermc.paletter.palette.block;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record BlockPropertiesPalette(List<BlockProperties> blocks) {
    public static BlockPropertiesPalette fromJson(JsonNode array) {
        List<BlockProperties> blocks = new ArrayList<>();
        for (JsonNode node : array) {
            JsonNode properties = node.get("properties");

            List<String> blockTags = new ArrayList<>();
            JsonNode blockTagsNode = properties.get("blockTags");
            if (blockTagsNode != null) {
                for (JsonNode tag : blockTagsNode) {
                    blockTags.add(tag.asString());
                }
            }

            Map<String, JsonNode> components = new LinkedHashMap<>();
            JsonNode componentsNode = properties.get("components");
            if (componentsNode != null) {
                for (Map.Entry<String, JsonNode> entry : componentsNode.properties()) {
                    components.put(entry.getKey(), entry.getValue());
                }
            }

            blocks.add(new BlockProperties(node.get("name").asString(), List.copyOf(blockTags), Map.copyOf(components)));
        }
        return new BlockPropertiesPalette(blocks);
    }
}
