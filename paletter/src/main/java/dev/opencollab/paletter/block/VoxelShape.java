package dev.opencollab.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.behaviour.BlockShape;
import dev.opencollab.paletter.internal.shape.VoxelGrid;
import dev.opencollab.paletter.internal.shape.VoxelShapeRules;

import java.util.List;

/**
 * A voxel shape as Bedrock keeps and sends it ({@code VoxelShapesPacket}): a grid with, per axis, the sorted coordinates
 * of its planes in blocks ({@code 0} to {@code 1} is the block), and which of the cells between the planes are filled.
 * Immutable, with value equality: BDS keeps one shape per distinct grid, so equal shapes are one shape.
 * <p>
 * A voxel shape is a <b>culling</b> shape: it says which faces of neighbouring blocks a block hides. It is not a
 * collision or selection shape (those are {@link BlockShape}s, {@code BlockBehaviour#COLLISION_SHAPE} and
 * {@code OUTLINE_SHAPE}), and the Script API does not expose voxel shapes.
 * <p>
 * The grid is minimal, as BDS makes it: no plane that separates two equal layers of cells, none outside the filled cells.
 * Cells are numbered {@code (x * ySize + y) * zSize + z}. The empty shape has no cells and the single coordinate
 * {@code 0} on every axis.
 *
 * @since 1.0.0
 */
public final class VoxelShape {
    /** The shape without cells, {@code minecraft:empty}. */
    public static final VoxelShape EMPTY = new VoxelShape(VoxelGrid.EMPTY);

    private final VoxelGrid grid;

    /**
     * Internal: wraps a grid, which is the shape's content.
     *
     * @param grid the grid
     */
    @Internal
    public VoxelShape(@NonNull VoxelGrid grid) {
        this.grid = grid;
    }

    /**
     * The shape of a union of boxes, checked the way BDS checks a voxel shape file. What BDS refuses fails here with its
     * message: no boxes, a box without volume or beyond 14 pixels around the block, a shape wider than 30 pixels or
     * without a box that overlaps the block, the {@code minecraft} namespace.
     *
     * @param identifier the name the shape will have, e.g. {@code my:half_pipe}; used in the messages
     * @param boxes six floats per box: {@code minX, minY, minZ, maxX, maxY, maxZ}, in blocks (a pixel is {@code 1 / 16f})
     * @return the shape
     * @throws IllegalArgumentException if BDS would not load the shape
     */
    public static @NonNull VoxelShape ofBoxes(@NonNull String identifier, float @NonNull ... boxes) {
        VoxelShapeRules.check(identifier, boxes);
        return new VoxelShape(VoxelGrid.ofBoxes(boxes));
    }

    /**
     * Same as {@link #ofBoxes(String, float...)}, for the boxes of a {@link BlockShape}.
     *
     * @param identifier the name the shape will have, e.g. {@code my:half_pipe}; used in the messages
     * @param boxes the boxes of the shape
     * @return the shape
     * @throws IllegalArgumentException if BDS would not load the shape
     */
    public static @NonNull VoxelShape ofBoxes(@NonNull String identifier, @NonNull BlockShape boxes) {
        return ofBoxes(identifier, boxes.coordinates());
    }

    /**
     * The x plane coordinates.
     *
     * @return the coordinates of the planes across the x axis, ascending, in blocks
     */
    public float @NonNull [] xCoordinates() {
        return grid.coordinates(0).clone();
    }

    /**
     * The y plane coordinates.
     *
     * @return the coordinates of the planes across the y axis, ascending, in blocks
     */
    public float @NonNull [] yCoordinates() {
        return grid.coordinates(1).clone();
    }

    /**
     * The z plane coordinates.
     *
     * @return the coordinates of the planes across the z axis, ascending, in blocks
     */
    public float @NonNull [] zCoordinates() {
        return grid.coordinates(2).clone();
    }

    /**
     * The number of cells along x.
     *
     * @return the number of cells along x ({@code xCoordinates().length - 1}, none for the empty shape)
     */
    public int xSize() {
        return grid.coordinates(0).length - 1;
    }

    /**
     * The number of cells along y.
     *
     * @return the number of cells along y
     */
    public int ySize() {
        return grid.coordinates(1).length - 1;
    }

    /**
     * The number of cells along z.
     *
     * @return the number of cells along z
     */
    public int zSize() {
        return grid.coordinates(2).length - 1;
    }

    /**
     * Whether one cell of the grid is filled.
     *
     * @param x the cell index along x
     * @param y the cell index along y
     * @param z the cell index along z
     * @return whether the cell between the planes {@code x} and {@code x + 1} (and likewise for y and z) is filled
     * @throws IndexOutOfBoundsException if the cell is not in the grid
     */
    public boolean cell(int x, int y, int z) {
        if (x < 0 || x >= xSize() || y < 0 || y >= ySize() || z < 0 || z >= zSize()) {
            throw new IndexOutOfBoundsException("Cell (" + x + ", " + y + ", " + z + ") is not in a grid of "
                + xSize() + " x " + ySize() + " x " + zSize());
        }
        return grid.cells()[(x * ySize() + y) * zSize() + z];
    }

    /**
     * Whether the shape is empty.
     *
     * @return whether the shape has no cells
     */
    public boolean isEmpty() {
        return grid.isEmpty();
    }

    /**
     * The filled cells as boxes.
     *
     * @return the filled cells as boxes in blocks, one per cell, in cell order. Not the boxes the shape was made of
     */
    public @NonNull List<BlockShape.Box> boxes() {
        return grid.toShape().boxes();
    }

    /**
     * Internal: the grid this shape wraps.
     *
     * @return the grid
     */
    @Internal
    public @NonNull VoxelGrid grid() {
        return grid;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VoxelShape shape && grid.equals(shape.grid);
    }

    @Override
    public int hashCode() {
        return grid.hashCode();
    }

    @Override
    public String toString() {
        return "VoxelShape" + boxes();
    }
}
