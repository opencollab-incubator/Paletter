package org.geysermc.paletter.palette.block;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

// Source: blocks.json - one entry per block state (joined to a Block via blockStateHash(), not
// position - the file's entry count doesn't match block_palette.nbt's).
public record BlockAttributes(
        String name,
        int blockStateHash,
        String translationKey,
        boolean isSolid,
        double hardness,
        double explosionResistance,
        double friction,
        double thickness,
        boolean requiresCorrectToolForDrops,
        double translucency,
        String mapColor,
        String tintMethod,
        int lightEmission,
        int lightDampening,
        int burnOdds,
        int flameOdds,
        boolean canContainLiquidSource,
        String liquidReactionOnTouch,
        List<List<Double>> collisionShape,
        List<Double> outlineShape,
        List<Double> visualShape,
        List<Double> uiShape,
        List<Double> liquidClipShape
) {
    public static List<BlockAttributes> listFromJson(JsonNode array) {
        List<BlockAttributes> list = new ArrayList<>();
        for (JsonNode node : array) {
            list.add(fromJson(node));
        }
        return list;
    }

    private static BlockAttributes fromJson(JsonNode node) {
        return new BlockAttributes(
                node.get("name").asString(),
                // Stored as an unsigned 32-bit value in JSON, some exceed Integer.MAX_VALUE - read as
                // long and narrow, which reinterprets the same bit pattern asInt() would reject.
                (int) node.get("blockStateHash").asLong(),
                node.get("translationKey").asString(),
                node.get("isSolid").asBoolean(),
                node.get("hardness").asDouble(),
                node.get("explosionResistance").asDouble(),
                node.get("friction").asDouble(),
                node.get("thickness").asDouble(),
                node.get("requiresCorrectToolForDrops").asBoolean(),
                node.get("translucency").asDouble(),
                node.get("mapColor").asString(),
                node.get("tintMethod").asString(),
                node.get("lightEmission").asInt(),
                node.get("lightDampening").asInt(),
                node.get("burnOdds").asInt(),
                node.get("flameOdds").asInt(),
                node.get("canContainLiquidSource").asBoolean(),
                node.get("liquidReactionOnTouch").asString(),
                readShapeList(node.get("collisionShape")),
                readShape(node.get("outlineShape")),
                readShape(node.get("visualShape")),
                readShape(node.get("uiShape")),
                readShape(node.get("liquidClipShape"))
        );
    }

    private static List<Double> readShape(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        List<Double> values = new ArrayList<>();
        for (JsonNode value : node) {
            values.add(value.asDouble());
        }
        return values;
    }

    private static List<List<Double>> readShapeList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        List<List<Double>> shapes = new ArrayList<>();
        for (JsonNode shape : node) {
            shapes.add(readShape(shape));
        }
        return shapes;
    }
}
