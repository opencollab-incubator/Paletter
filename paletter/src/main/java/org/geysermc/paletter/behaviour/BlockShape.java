package org.geysermc.paletter.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Boxes in block space ({@code 0} to {@code 1} on each axis; fences and walls reach {@code 1.5} in y), relative to the
 * block's minimum corner. Immutable; the bundled shapes are shared instances, so comparing them is cheap.
 *
 * @param boxes the boxes, possibly none (no collision)
 * @since 0.1.0
 */
public record BlockShape(@NonNull List<Box> boxes) {
    public static final BlockShape EMPTY = new BlockShape(List.of());
    public static final BlockShape FULL_CUBE = new BlockShape(List.of(new Box(0, 0, 0, 1, 1, 1)));

    public BlockShape {
        boxes = List.copyOf(boxes);
    }

    /**
     * @param coordinates six floats per box: {@code minX, minY, minZ, maxX, maxY, maxZ}
     */
    public static @NonNull BlockShape of(float @NonNull ... coordinates) {
        if (coordinates.length % 6 != 0) {
            throw new IllegalArgumentException("Six coordinates per box, got " + coordinates.length);
        }
        Box[] boxes = new Box[coordinates.length / 6];
        for (int i = 0; i < boxes.length; i++) {
            int at = i * 6;
            boxes[i] = new Box(coordinates[at], coordinates[at + 1], coordinates[at + 2],
                coordinates[at + 3], coordinates[at + 4], coordinates[at + 5]);
        }
        return new BlockShape(List.of(boxes));
    }

    public boolean isEmpty() {
        return boxes.isEmpty();
    }

    /**
     * @return the coordinates, six per box, as {@link #of(float...)} takes them
     */
    public float @NonNull [] coordinates() {
        float[] coordinates = new float[boxes.size() * 6];
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            int at = i * 6;
            coordinates[at] = box.minX;
            coordinates[at + 1] = box.minY;
            coordinates[at + 2] = box.minZ;
            coordinates[at + 3] = box.maxX;
            coordinates[at + 4] = box.maxY;
            coordinates[at + 5] = box.maxZ;
        }
        return coordinates;
    }

    /**
     * One axis aligned box.
     *
     * @since 0.1.0
     */
    public record Box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    }
}
