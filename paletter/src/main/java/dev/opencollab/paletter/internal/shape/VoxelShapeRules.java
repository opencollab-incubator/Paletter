package dev.opencollab.paletter.internal.shape;

/**
 * What BDS refuses in a voxel shape file, with its own words (content log of the probe add-on of
 * {@code voxel-shapes capture}, data/bds/&lt;build&gt;/voxel-shapes/content-log.txt; the limit on the extent is a text
 * of the BDS binary that no capture reached). BDS takes pixels; a pixel is {@code 1 / 16f} of a block here.
 */
public final class VoxelShapeRules {
    /** Boxes may reach 14 pixels beyond the block: "outside the allowed bounds of (-14, -14, -14) to (30, 30, 30)". */
    private static final float MIN = -14 / 16f;
    private static final float MAX = 30 / 16f;
    /** "Total extent of voxel shape ... exceeds the maximum of 30 pixels (1 block + 14 pixels)". */
    private static final float MAX_EXTENT = 30 / 16f;

    private VoxelShapeRules() {
    }

    /** @throws IllegalArgumentException if BDS would not accept the name of a custom shape */
    public static void checkIdentifier(String identifier) {
        if (identifier.startsWith("minecraft:")) {
            throw new IllegalArgumentException("The `minecraft` namespace is not valid for custom voxel shape: `" + identifier + "`");
        }
    }

    /**
     * @param boxes six floats per box, in blocks
     * @throws IllegalArgumentException if BDS would not load the shape
     */
    public static void check(String identifier, float[] boxes) {
        checkIdentifier(identifier);
        if (boxes.length == 0) {
            throw new IllegalArgumentException("Voxel shape " + identifier + ": shape -> boxes: expected array with at least 1 elements");
        }
        boolean inBlock = false;
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < boxes.length / 6; i++) {
            float[] from = {boxes[6 * i], boxes[6 * i + 1], boxes[6 * i + 2]};
            float[] to = {boxes[6 * i + 3], boxes[6 * i + 4], boxes[6 * i + 5]};
            boolean overlaps = true;
            for (int axis = 0; axis < 3; axis++) {
                if (!(from[axis] < to[axis])) {
                    throw new IllegalArgumentException("Box at index " + i + " is invalid for voxel shape `" + identifier + "`: it has no volume");
                }
                if (from[axis] < MIN || to[axis] > MAX) {
                    throw new IllegalArgumentException("Box at index " + i + " in voxel shape '" + identifier
                        + "' is outside the allowed bounds of (-14, -14, -14) to (30, 30, 30) pixels");
                }
                overlaps &= from[axis] < 1 && to[axis] > 0;
                min[axis] = Math.min(min[axis], from[axis]);
                max[axis] = Math.max(max[axis], to[axis]);
            }
            inBlock |= overlaps;
        }
        if (!inBlock) {
            throw new IllegalArgumentException("Voxel shape '" + identifier + "' has no boxes that overlap the base block (0-16 pixels on all axes)");
        }
        for (int axis = 0; axis < 3; axis++) {
            if (max[axis] - min[axis] > MAX_EXTENT) {
                throw new IllegalArgumentException("Total extent of voxel shape '" + identifier + "' on axis " + axis + " is "
                    + (max[axis] - min[axis]) * 16 + " pixels, which exceeds the maximum of 30 pixels (1 block + 14 pixels)");
            }
        }
    }
}
