package org.geysermc.paletter.palette.block;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

// One entry of block_properties.json - a partial overlay (currently ~98 of ~1379 block types) of
// behavior-pack components, block tags, and menu placement. Unlike blocks.json this is keyed by
// block identifier, not per-state.
public record BlockProperties(String name, List<String> blockTags, Map<String, JsonNode> components) {
}
