package org.geysermc.paletter.internal.shape;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;

/**
 * What a block's {@code minecraft:transformation} does to its culling shape, as BDS computes it (in single precision,
 * in this order; captured with the shapes of the probe add-on of {@code voxel-shapes capture}, on edges that are no
 * binary fractions so that every rounding shows):
 * <ol>
 *     <li><b>scale</b>, if any factor is not 1: {@code (v - c) * s + c} on every axis, {@code c = 0.5 + scale pivot};</li>
 *     <li><b>rotation</b>: the quarter turns around x, then y, then z, as one change of axes. Without a rotation
 *     pivot a coordinate is taken as it is or as {@code 1 - v}; with one, a coordinate of a turned axis is
 *     {@code ±(v - c) + c'}, {@code c = 0.5 + rotation pivot} of the axis it comes from and {@code c'} of the axis it
 *     goes to;</li>
 *     <li><b>translation</b>: {@code v + t}.</li>
 * </ol>
 * One quarter turn maps: around x {@code (y, z) -> (1 - z, y)}, around y {@code (x, z) -> (z, 1 - x)}, around z
 * {@code (x, y) -> (1 - y, x)}.
 *
 * @param turns quarter turns around x, y and z (the wire's {@code RX}, {@code RY}, {@code RZ})
 * @param rotationPivot {@code RXP}, {@code RYP}, {@code RZP}
 * @param scale {@code SX}, {@code SY}, {@code SZ}
 * @param scalePivot {@code SXP}, {@code SYP}, {@code SZP}
 * @param translation {@code TX}, {@code TY}, {@code TZ}, in blocks
 */
public record ShapeTransformation(int[] turns, float[] rotationPivot, float[] scale, float[] scalePivot, float[] translation) {
    public static final ShapeTransformation IDENTITY = new ShapeTransformation(new int[3], new float[3], new float[] {1, 1, 1},
        new float[3], new float[3]);
    private static final String[] AXES = {"X", "Y", "Z"};

    /** @param transformation the wire data of {@code minecraft:transformation}, or null for a block without one */
    public static ShapeTransformation of(@Nullable NbtMap transformation) {
        if (transformation == null) {
            return IDENTITY;
        }
        int[] turns = new int[3];
        float[] rotationPivot = new float[3];
        float[] scale = new float[3];
        float[] scalePivot = new float[3];
        float[] translation = new float[3];
        for (int axis = 0; axis < 3; axis++) {
            turns[axis] = Math.floorMod(transformation.getInt("R" + AXES[axis], 0), 4);
            rotationPivot[axis] = transformation.getFloat("R" + AXES[axis] + "P", 0);
            scale[axis] = transformation.getFloat("S" + AXES[axis], 1);
            scalePivot[axis] = transformation.getFloat("S" + AXES[axis] + "P", 0);
            translation[axis] = transformation.getFloat("T" + AXES[axis], 0);
        }
        return new ShapeTransformation(turns, rotationPivot, scale, scalePivot, translation);
    }

    public boolean isIdentity() {
        return !turned() && !scaled() && !any(translation);
    }

    /** Whether the shape turns around another point than the centre of the block. */
    public boolean hasRotationPivot() {
        return turned() && any(rotationPivot);
    }

    private boolean turned() {
        return turns[0] != 0 || turns[1] != 0 || turns[2] != 0;
    }

    private boolean scaled() {
        return scale[0] != 1 || scale[1] != 1 || scale[2] != 1;
    }

    private static boolean any(float[] values) {
        return values[0] != 0 || values[1] != 0 || values[2] != 0;
    }

    /** Transforms the box at {@code boxes[at .. at + 5]} ({@code minX, minY, minZ, maxX, maxY, maxZ}) in place. */
    public void apply(float[] boxes, int at) {
        float[] first = point(boxes[at], boxes[at + 1], boxes[at + 2]);
        float[] second = point(boxes[at + 3], boxes[at + 4], boxes[at + 5]);
        for (int axis = 0; axis < 3; axis++) {
            boxes[at + axis] = Math.min(first[axis], second[axis]);
            boxes[at + 3 + axis] = Math.max(first[axis], second[axis]);
        }
    }

    private float[] point(float x, float y, float z) {
        float[] point = {x, y, z};
        if (scaled()) {
            for (int axis = 0; axis < 3; axis++) {
                float centre = 0.5f + scalePivot[axis];
                point[axis] = (point[axis] - centre) * scale[axis] + centre;
            }
        }
        if (turned()) {
            // The turns as one change of axes: axis i takes the coordinate of axis source[i], mirrored if negative[i]
            int[] source = {0, 1, 2};
            boolean[] negative = new boolean[3];
            for (int axis = 0; axis < 3; axis++) {
                int a = axis == 0 ? 1 : 0;
                int b = axis == 2 ? 1 : 2;
                // One quarter turn: around x (y, z) -> (-z, y), around y (x, z) -> (z, -x), around z (x, y) -> (-y, x)
                boolean firstMirrored = axis != 1;
                for (int turn = 0; turn < turns[axis]; turn++) {
                    int sourceA = source[a];
                    boolean negativeA = negative[a];
                    source[a] = source[b];
                    negative[a] = negative[b] ^ firstMirrored;
                    source[b] = sourceA;
                    negative[b] = negativeA ^ !firstMirrored;
                }
            }
            float[] turned = new float[3];
            boolean pivot = any(rotationPivot);
            for (int axis = 0; axis < 3; axis++) {
                float value = point[source[axis]];
                if (source[axis] == axis && !negative[axis]) {
                    turned[axis] = value;
                } else if (pivot) {
                    float relative = value - (0.5f + rotationPivot[source[axis]]);
                    turned[axis] = (negative[axis] ? -relative : relative) + (0.5f + rotationPivot[axis]);
                } else {
                    turned[axis] = negative[axis] ? 1 - value : value;
                }
            }
            point = turned;
        }
        for (int axis = 0; axis < 3; axis++) {
            point[axis] += translation[axis];
        }
        return point;
    }
}
