package dev.opencollab.paletter.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Boxes in block space ({@code 0} to {@code 1} on each axis; fences and walls reach {@code 1.5} in y), relative to the
 * block's minimum corner. Immutable, with value equality: compare shapes with {@code equals}. Behaviour data shares one
 * instance per distinct bundled shape, but that is an optimisation and not something to rely on with {@code ==}.
 *
 * @param boxes the boxes, possibly none (no collision)
 * @since 1.0.0
 */
public record BlockShape(@NonNull List<Box> boxes) {
    /**
     * No boxes: a shape without collision.
     *
     * @since 1.0.0
     */
    public static final BlockShape EMPTY = new BlockShape(List.of());
    /**
     * One box that fills the whole block.
     *
     * @since 1.0.0
     */
    public static final BlockShape FULL_CUBE = new BlockShape(List.of(new Box(0, 0, 0, 1, 1, 1)));

    /**
     * Creates a shape.
     *
     * @throws NullPointerException if {@code boxes} or one of its elements is null
     * @since 1.0.0
     */
    public BlockShape {
        boxes = List.copyOf(boxes);
    }

    /**
     * Creates a shape from raw coordinates.
     *
     * @param coordinates six floats per box: {@code minX, minY, minZ, maxX, maxY, maxZ}
     * @return the shape; empty if there are no coordinates
     * @throws IllegalArgumentException if the number of coordinates is not a multiple of six
     * @since 1.0.0
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

    /**
     * Whether the shape has no boxes.
     *
     * @return true if there are no boxes
     * @since 1.0.0
     */
    public boolean isEmpty() {
        return boxes.isEmpty();
    }

    /**
     * Returns the boxes as raw coordinates.
     *
     * @return the coordinates, six per box, as {@link #of(float...)} takes them
     * @since 1.0.0
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
     * @param minX the lower x bound
     * @param minY the lower y bound
     * @param minZ the lower z bound
     * @param maxX the upper x bound
     * @param maxY the upper y bound
     * @param maxZ the upper z bound
     * @since 1.0.0
     */
    public record Box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    }
}
