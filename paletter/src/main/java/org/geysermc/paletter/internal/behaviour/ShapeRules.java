package org.geysermc.paletter.internal.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.behaviour.BlockShape;
import org.geysermc.paletter.property.Properties;

import java.util.List;

/**
 * The shape keys read from the component data, by hand: no inferred rule of {@code data/model/mappings.json} explains the
 * boxes yet (the encoders write them with hand transforms too), so there is no rule to read backwards.
 * {@link WireRules} calls these for {@code collision_shape} and {@code outline_shape}.
 */
@Internal
public final class ShapeRules {
    private ShapeRules() {
    }

    /** {@code minecraft:collision_box}, then {@code minecraft:transformation}. */
    public static @Nullable BlockShape collisionShape(@NonNull Properties properties) {
        return transformed(collision(properties.component("minecraft:collision_box")), properties);
    }

    /** {@code minecraft:selection_box}, then {@code minecraft:transformation}. */
    public static @Nullable BlockShape outlineShape(@NonNull Properties properties) {
        return transformed(selection(properties.component("minecraft:selection_box")), properties);
    }

    /** {@code {enabled, boxes: [{minX..maxZ}]}} in pixels (0 to 16). */
    private static @Nullable BlockShape collision(@Nullable NbtMap box) {
        if (box == null) {
            return null;
        }
        if (box.getByte("enabled", (byte) 1) == 0) {
            return BlockShape.EMPTY;
        }
        if (!box.containsKey("boxes")) {
            return pixels(box);
        }
        List<NbtMap> boxes = box.getList("boxes", NbtType.COMPOUND);
        float[] coordinates = new float[boxes.size() * 6];
        for (int i = 0; i < boxes.size(); i++) {
            NbtMap b = boxes.get(i);
            float[] one = {b.getFloat("minX"), b.getFloat("minY"), b.getFloat("minZ"), b.getFloat("maxX"), b.getFloat("maxY"), b.getFloat("maxZ")};
            for (int j = 0; j < 6; j++) {
                coordinates[i * 6 + j] = one[j] / 16f;
            }
        }
        return BlockShape.of(coordinates);
    }

    /** {@code {enabled, origin: [x, y, z], size: [x, y, z]}}: origin -8 to 8 in x and z, 0 to 16 in y. */
    private static @Nullable BlockShape selection(@Nullable NbtMap box) {
        if (box == null) {
            return null;
        }
        return box.getByte("enabled", (byte) 1) == 0 ? BlockShape.EMPTY : pixels(box);
    }

    /**
     * Applies {@code minecraft:transformation} (the boxes are given before it): quarter turns around y about the block
     * centre ({@code RY}, checked against the dumps on shelf_mushroom). Other transformations (x or z rotation, scale,
     * translation) have no vanilla block to check a rule against, so the shape is unknown (null) then.
     */
    private static @Nullable BlockShape transformed(@Nullable BlockShape shape, Properties properties) {
        NbtMap transformation = properties.component("minecraft:transformation");
        if (shape == null || transformation == null) {
            return shape;
        }
        for (String identity : List.of("RX", "RZ", "TX", "TY", "TZ", "RXP", "RYP", "RZP", "SXP", "SYP", "SZP")) {
            if (transformation.get(identity) instanceof Number number && number.floatValue() != 0) {
                return null;
            }
        }
        for (String scale : List.of("SX", "SY", "SZ")) {
            if (transformation.get(scale) instanceof Number number && number.floatValue() != 1) {
                return null;
            }
        }
        int turns = Math.floorMod(transformation.getInt("RY", 0), 4);
        float[] coordinates = shape.coordinates();
        for (int turn = 0; turn < turns; turn++) {
            for (int at = 0; at < coordinates.length; at += 6) {
                // (x, z) -> (z, 1 - x), as a box: min z from max x
                float minZ = 1 - coordinates[at + 3];
                float maxZ = 1 - coordinates[at];
                coordinates[at] = coordinates[at + 2];
                coordinates[at + 3] = coordinates[at + 5];
                coordinates[at + 2] = minZ;
                coordinates[at + 5] = maxZ;
            }
        }
        return BlockShape.of(coordinates);
    }

    private static BlockShape pixels(NbtMap box) {
        List<Float> origin = box.getList("origin", NbtType.FLOAT);
        List<Float> size = box.getList("size", NbtType.FLOAT);
        float x = origin.get(0) + 8;
        float y = origin.get(1);
        float z = origin.get(2) + 8;
        return BlockShape.of(x / 16f, y / 16f, z / 16f, (x + size.get(0)) / 16f, (y + size.get(1)) / 16f, (z + size.get(2)) / 16f);
    }
}
