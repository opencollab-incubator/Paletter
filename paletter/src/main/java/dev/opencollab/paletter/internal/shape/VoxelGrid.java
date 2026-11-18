package dev.opencollab.paletter.internal.shape;

import org.cloudburstmc.protocol.bedrock.data.SerializableVoxelShape;
import dev.opencollab.paletter.behaviour.BlockShape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * A voxel shape as Bedrock keeps and sends it: per axis the sorted coordinates of a grid (in blocks), and which of the
 * cells between them are filled. Immutable, with value equality: BDS keeps one shape per distinct grid.
 * <p>
 * Every rule here is what BDS does, read from captures (docs/wire-notes.md, "Voxel shapes") and checked by the
 * conformance test of the tools against every captured build:
 * <ul>
 *     <li>a grid is minimal: no coordinate with the same cells on both sides, none outside the filled cells;</li>
 *     <li>the cell at {@code (x, y, z)} is bit {@code (x * ySize + y) * zSize + z} of the storage, the least significant
 *     bit of a byte first;</li>
 *     <li>the empty shape has no cells and the coordinate {@code 0} on every axis.</li>
 * </ul>
 */
public final class VoxelGrid {
    public static final VoxelGrid EMPTY = new VoxelGrid(new float[][] {{0}, {0}, {0}}, new boolean[0]);
    /** The faces in the order BDS derives their shapes in: down, up, north, south, west, east, as {axis, high}. */
    private static final int[][] FACES = {{1, 0}, {1, 1}, {2, 0}, {2, 1}, {0, 0}, {0, 1}};

    private final float[][] coordinates;
    private final boolean[] cells;
    /** The hash, made on first use (0: not yet; a grid that hashes to 0 is hashed again): the vanilla grids are hashed by every palette. */
    private int hash;

    private VoxelGrid(float[][] coordinates, boolean[] cells) {
        this.coordinates = coordinates;
        this.cells = cells;
    }

    /**
     * The grid of a union of boxes.
     *
     * @param boxes six floats per box: {@code minX, minY, minZ, maxX, maxY, maxZ}, in blocks. A box without volume adds
     *              nothing
     */
    public static VoxelGrid ofBoxes(float... boxes) {
        float[][] coordinates = new float[3][];
        for (int axis = 0; axis < 3; axis++) {
            TreeSet<Float> sorted = new TreeSet<>();
            for (int at = 0; at < boxes.length; at += 6) {
                // -0 and 0 are one coordinate
                sorted.add(boxes[at + axis] + 0f);
                sorted.add(boxes[at + 3 + axis] + 0f);
            }
            coordinates[axis] = new float[sorted.size()];
            int i = 0;
            for (float coordinate : sorted) {
                coordinates[axis][i++] = coordinate;
            }
        }
        if (boxes.length == 0) {
            return EMPTY;
        }
        int ySize = coordinates[1].length - 1;
        int zSize = coordinates[2].length - 1;
        boolean[] cells = new boolean[(coordinates[0].length - 1) * ySize * zSize];
        for (int at = 0; at < boxes.length; at += 6) {
            int[] from = new int[3];
            int[] to = new int[3];
            for (int axis = 0; axis < 3; axis++) {
                from[axis] = Arrays.binarySearch(coordinates[axis], boxes[at + axis] + 0f);
                to[axis] = Arrays.binarySearch(coordinates[axis], boxes[at + 3 + axis] + 0f);
            }
            for (int x = from[0]; x < to[0]; x++) {
                for (int y = from[1]; y < to[1]; y++) {
                    for (int z = from[2]; z < to[2]; z++) {
                        cells[(x * ySize + y) * zSize + z] = true;
                    }
                }
            }
        }
        return minimal(coordinates, cells);
    }

    public static VoxelGrid of(BlockShape shape) {
        return ofBoxes(shape.coordinates());
    }

    /** Drops the coordinates that separate nothing: outside the filled cells, or between two equal layers of cells. */
    private static VoxelGrid minimal(float[][] coordinates, boolean[] cells) {
        for (int axis = 0; axis < 3; axis++) {
            int[] sizes = {coordinates[0].length - 1, coordinates[1].length - 1, coordinates[2].length - 1};
            int layers = sizes[axis];
            // The layers of cells across this axis
            boolean[][] layer = new boolean[layers][];
            int first = -1;
            int last = -1;
            for (int i = 0; i < layers; i++) {
                layer[i] = layer(cells, sizes, axis, i);
                if (contains(layer[i])) {
                    first = first == -1 ? i : first;
                    last = i;
                }
            }
            if (first == -1) {
                return EMPTY;
            }
            List<Integer> kept = new ArrayList<>();
            for (int i = first; i <= last; i++) {
                if (i == first || !Arrays.equals(layer[i], layer[i - 1])) {
                    kept.add(i);
                }
            }
            if (kept.size() == layers) {
                continue;
            }
            float[] merged = new float[kept.size() + 1];
            int[] newSizes = sizes.clone();
            newSizes[axis] = kept.size();
            boolean[] mergedCells = new boolean[newSizes[0] * newSizes[1] * newSizes[2]];
            for (int i = 0; i < kept.size(); i++) {
                merged[i] = coordinates[axis][kept.get(i)];
                setLayer(mergedCells, newSizes, axis, i, layer[kept.get(i)]);
            }
            merged[kept.size()] = coordinates[axis][last + 1];
            coordinates[axis] = merged;
            cells = mergedCells;
        }
        return new VoxelGrid(coordinates, cells);
    }

    private static boolean contains(boolean[] layer) {
        for (boolean cell : layer) {
            if (cell) {
                return true;
            }
        }
        return false;
    }

    /** The cells with index {@code index} on {@code axis}, in the order of the two other axes. */
    private static boolean[] layer(boolean[] cells, int[] sizes, int axis, int index) {
        int a = axis == 0 ? 1 : 0;
        int b = axis == 2 ? 1 : 2;
        boolean[] layer = new boolean[sizes[a] * sizes[b]];
        int[] at = new int[3];
        at[axis] = index;
        for (at[a] = 0; at[a] < sizes[a]; at[a]++) {
            for (at[b] = 0; at[b] < sizes[b]; at[b]++) {
                layer[at[a] * sizes[b] + at[b]] = cells[(at[0] * sizes[1] + at[1]) * sizes[2] + at[2]];
            }
        }
        return layer;
    }

    private static void setLayer(boolean[] cells, int[] sizes, int axis, int index, boolean[] layer) {
        int a = axis == 0 ? 1 : 0;
        int b = axis == 2 ? 1 : 2;
        int[] at = new int[3];
        at[axis] = index;
        for (at[a] = 0; at[a] < sizes[a]; at[a]++) {
            for (at[b] = 0; at[b] < sizes[b]; at[b]++) {
                cells[(at[0] * sizes[1] + at[1]) * sizes[2] + at[2]] = layer[at[a] * sizes[b] + at[b]];
            }
        }
    }

    public boolean isEmpty() {
        return cells.length == 0;
    }

    /**
     * @return one box per filled cell, six floats each ({@code minX, minY, minZ, maxX, maxY, maxZ}), in cell order
     */
    public float[] cellBoxes() {
        int ySize = coordinates[1].length - 1;
        int zSize = coordinates[2].length - 1;
        float[] boxes = new float[6 * cells.length];
        int count = 0;
        for (int i = 0; i < cells.length; i++) {
            if (cells[i]) {
                int x = i / (ySize * zSize);
                int y = i / zSize % ySize;
                int z = i % zSize;
                boxes[count++] = coordinates[0][x];
                boxes[count++] = coordinates[1][y];
                boxes[count++] = coordinates[2][z];
                boxes[count++] = coordinates[0][x + 1];
                boxes[count++] = coordinates[1][y + 1];
                boxes[count++] = coordinates[2][z + 1];
            }
        }
        return Arrays.copyOf(boxes, count);
    }

    /** The shape as boxes, one per filled cell. */
    public BlockShape toShape() {
        return BlockShape.of(cellBoxes());
    }

    /**
     * The shapes of the six faces, in BDS's order (down, up, north, south, west, east): the cells that reach the face of
     * the block from inside it (a cell that ends at the face from outside does not count), drawn through the whole block.
     */
    public VoxelGrid[] faces() {
        VoxelGrid[] faces = new VoxelGrid[FACES.length];
        float[] boxes = cellBoxes();
        for (int face = 0; face < FACES.length; face++) {
            int axis = FACES[face][0];
            boolean high = FACES[face][1] == 1;
            float[] touching = new float[boxes.length];
            int count = 0;
            for (int at = 0; at < boxes.length; at += 6) {
                float min = boxes[at + axis];
                float max = boxes[at + 3 + axis];
                if (high ? min < 1 && max >= 1 : min <= 0 && max > 0) {
                    System.arraycopy(boxes, at, touching, count, 6);
                    touching[count + axis] = 0;
                    touching[count + 3 + axis] = 1;
                    count += 6;
                }
            }
            faces[face] = ofBoxes(Arrays.copyOf(touching, count));
        }
        return faces;
    }

    /** This shape with a block's {@code minecraft:transformation} applied. */
    public VoxelGrid transformed(ShapeTransformation transformation) {
        if (transformation.isIdentity()) {
            return this;
        }
        float[] boxes = cellBoxes();
        for (int at = 0; at < boxes.length; at += 6) {
            transformation.apply(boxes, at);
        }
        return ofBoxes(boxes);
    }

    public SerializableVoxelShape toWire() {
        List<Short> storage = new ArrayList<>();
        for (int at = 0; at < cells.length; at += 8) {
            int bits = 0;
            for (int bit = 0; bit < 8 && at + bit < cells.length; bit++) {
                if (cells[at + bit]) {
                    bits |= 1 << bit;
                }
            }
            storage.add((short) bits);
        }
        // The empty shape has the coordinate 0 on every axis and no cells
        short xSize = (short) (coordinates[0].length - 1);
        short ySize = (short) (coordinates[1].length - 1);
        short zSize = (short) (coordinates[2].length - 1);
        return new SerializableVoxelShape(new SerializableVoxelShape.SerializableCells(xSize, ySize, zSize, storage),
            floats(coordinates[0]), floats(coordinates[1]), floats(coordinates[2]));
    }

    private static List<Float> floats(float[] values) {
        List<Float> list = new ArrayList<>(values.length);
        for (float value : values) {
            list.add(value);
        }
        return list;
    }

    /** A shape as it was sent: kept as it is, minimal or not. */
    public static VoxelGrid ofWire(SerializableVoxelShape shape) {
        float[][] coordinates = {array(shape.getXCoordinates()), array(shape.getYCoordinates()), array(shape.getZCoordinates())};
        SerializableVoxelShape.SerializableCells sent = shape.getCells();
        boolean[] cells = new boolean[sent.getXSize() * sent.getYSize() * sent.getZSize()];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = (sent.getStorage().get(i / 8) >> (i % 8) & 1) != 0;
        }
        return new VoxelGrid(coordinates, cells);
    }

    private static float[] array(List<Float> values) {
        float[] array = new float[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    /** The coordinates of one axis (0 x, 1 y, 2 z); not to be changed. */
    public float[] coordinates(int axis) {
        return coordinates[axis];
    }

    /** The cells, {@code (x * ySize + y) * zSize + z}; not to be changed. */
    public boolean[] cells() {
        return cells;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VoxelGrid grid && Arrays.equals(cells, grid.cells) && Arrays.deepEquals(coordinates, grid.coordinates);
    }

    @Override
    public int hashCode() {
        int hash = this.hash;
        if (hash == 0) {
            this.hash = hash = 31 * Arrays.deepHashCode(coordinates) + Arrays.hashCode(cells);
        }
        return hash;
    }

    @Override
    public String toString() {
        return "VoxelGrid" + Arrays.toString(cellBoxes());
    }
}
