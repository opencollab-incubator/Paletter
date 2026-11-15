package org.geysermc.paletter.internal.shape;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.PaletteReport;
import org.geysermc.paletter.block.BlockRegistry;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.RegisteredBlock;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.block.VoxelShape;
import org.geysermc.paletter.block.VoxelShapes;
import org.geysermc.paletter.property.Properties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the voxel shape registry of a palette the way BDS builds its own (docs/wire-notes.md, "Voxel shapes"):
 * <ol>
 *     <li>the named vanilla shapes, in the order of the bundled capture;</li>
 *     <li>the custom shapes in the order they were registered (BDS: the order of their file names). A shape equal to one
 *     that is there shares its id;</li>
 *     <li>the shapes BDS makes for blocks, without a name, walking all blocks in the order of their name hashes
 *     ({@link BlockRegistry#nameHash}): for a vanilla block what the capture has for it, for every state of a custom block
 *     its culling shape with the block's transformation applied and then the shapes of its six faces
 *     ({@link VoxelGrid#faces}).</li>
 * </ol>
 * The names are sent in the order BDS's hash map gives them ({@link #hashMapOrder}).
 */
public final class VoxelShapeCompiler {
    private static final String SOURCE = "voxel shapes";
    private static final String FULL_BLOCK = "minecraft:geometry.full_block";
    public static final String EMPTY = "minecraft:empty";
    public static final String UNIT_CUBE = "minecraft:unit_cube";

    private final List<VoxelGrid> shapes = new ArrayList<>();
    private final Map<VoxelGrid, Integer> ids = new HashMap<>();
    private final Map<String, Integer> names = new LinkedHashMap<>();

    private VoxelShapeCompiler() {
    }

    /**
     * @param vanilla the vanilla registry of the palette's version ({@link VoxelShapeStore#shapes})
     * @param custom the custom shapes by identifier, in the order they were registered
     * @param blocks the blocks of the palette; its custom blocks name shapes in {@code minecraft:geometry}
     */
    public static VoxelShapes compile(List<VoxelShapeStore.Entry> vanilla, Map<String, VoxelShape> custom, BlockRegistry blocks,
                                      PaletteReport report) {
        VoxelShapeCompiler compiler = new VoxelShapeCompiler();
        List<VoxelShapeStore.Entry> made = new ArrayList<>();
        for (VoxelShapeStore.Entry entry : vanilla) {
            if (entry.name() != null) {
                compiler.names.put(entry.name(), compiler.add(entry.grid()));
            } else {
                made.add(entry);
            }
        }
        custom.forEach((identifier, shape) -> {
            if (compiler.names.containsKey(identifier)) {
                report.error(SOURCE, "The voxel shape " + identifier + " is defined twice; the first definition is used");
            } else {
                compiler.names.put(identifier, compiler.add(shape.grid()));
            }
        });

        List<RegisteredBlock> customBlocks = new ArrayList<>();
        for (CustomBlock block : blocks.customBlocks()) {
            customBlocks.add(blocks.block(block.identifier()));
        }
        customBlocks.sort(Comparator.comparing(block -> BlockRegistry.nameHash(block.identifier()), Long::compareUnsigned));
        int next = 0;
        for (VoxelShapeStore.Entry entry : made) {
            long first = BlockRegistry.nameHash(entry.block());
            while (next < customBlocks.size() && Long.compareUnsigned(BlockRegistry.nameHash(customBlocks.get(next).identifier()), first) < 0) {
                compiler.addBlock(customBlocks.get(next++), report);
            }
            if (next < customBlocks.size() && !entry.block().equals(entry.lastBlock())
                && Long.compareUnsigned(BlockRegistry.nameHash(customBlocks.get(next).identifier()), BlockRegistry.nameHash(entry.lastBlock())) < 0) {
                report.warn(SOURCE, "The shapes of the block " + customBlocks.get(next).identifier() + " may be numbered differently than BDS "
                    + "numbers them: its name hash lies between those of " + entry.block() + " and " + entry.lastBlock()
                    + ", and no capture tells for which of the blocks from one to the other BDS makes a shape");
            }
            compiler.add(entry.grid());
        }
        while (next < customBlocks.size()) {
            compiler.addBlock(customBlocks.get(next++), report);
        }

        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (String name : hashMapOrder(new ArrayList<>(compiler.names.keySet()))) {
            ordered.put(name, compiler.names.get(name));
        }
        return new VoxelShapes(compiler.shapes, ordered, compiler.shapes.size() - vanilla.size());
    }

    private int add(VoxelGrid grid) {
        Integer id = ids.get(grid);
        if (id == null) {
            id = shapes.size();
            shapes.add(grid);
            ids.put(grid, id);
        }
        return id;
    }

    /** The shapes BDS makes for a custom block: per state its transformed culling shape, then the shapes of its faces. */
    private void addBlock(RegisteredBlock block, PaletteReport report) {
        for (RegisteredBlockState state : block.states()) {
            Properties properties = block.properties(state);
            NbtMap geometry = properties.component("minecraft:geometry");
            if (geometry == null) {
                continue;
            }
            String name = geometry.getString("culling_shape", "");
            NbtMap transformation = properties.component("minecraft:transformation");
            if (name.isEmpty()) {
                // A block that names no culling shape gets none made, transformed or not
                continue;
            }
            VoxelGrid grid;
            if (geometry.getString("identifier", "").equals(FULL_BLOCK)) {
                // BDS: "A full block geometry cannot have a custom culling shape ... the unit cube shape will be used instead"
                if (!ShapeTransformation.of(transformation).isIdentity()) {
                    report.info(SOURCE, "The block " + block.identifier() + " is a full block with a culling shape and a transformation: "
                        + "no capture of BDS shows whether it transforms the unit cube it uses instead; Paletter sends no shape for it");
                }
                continue;
            } else if (names.containsKey(name)) {
                grid = shapes.get(names.get(name));
            } else {
                // BDS: "Voxel shape with name ... doesn't exist in the registry. Defaulting to minecraft:empty"
                report.warn(SOURCE, "The block " + block.identifier() + " names the culling shape " + name
                    + ", which no layer defines; Bedrock uses " + EMPTY + " for it");
                grid = VoxelGrid.EMPTY;
            }
            if (ShapeTransformation.of(transformation).hasRotationPivot()) {
                report.info(SOURCE, "The block " + block.identifier() + " turns around a rotation pivot: its culling shape follows the one "
                    + "capture of BDS with such a pivot (a quarter turn around y)");
            }
            grid = grid.transformed(ShapeTransformation.of(transformation));
            add(grid);
            for (VoxelGrid face : grid.faces()) {
                add(face);
            }
        }
    }

    /**
     * The order in which BDS sends the names: the iteration order of the {@code std::unordered_map<HashedString, ...>}
     * of the Windows build (MSVC), after inserting the names in the order BDS registers them. Checked against the
     * name order of every capture.
     * <p>
     * That map is a linked list of all elements plus buckets, and the elements of a bucket are neighbours in the list. It
     * starts with 8 buckets and never holds more elements than buckets: before that it has 8 times the buckets, from 512
     * on twice. A new element goes in front of the elements of its bucket, or to the end of the list if the bucket is
     * empty. Growing walks the list and moves every element whose new bucket has elements already in front of them. The
     * bucket of a name is its hash ({@link BlockRegistry#nameHash}, the hash of a {@code HashedString}) modulo the
     * number of buckets.
     *
     * @param inserted the names in insertion order, each once
     */
    public static List<String> hashMapOrder(List<String> inserted) {
        Map<String, Long> hashes = new HashMap<>();
        inserted.forEach(name -> hashes.put(name, BlockRegistry.nameHash(name)));
        List<String> order = new ArrayList<>();
        int buckets = 8;
        for (String name : inserted) {
            if (order.size() + 1 > buckets) {
                while (buckets < order.size() + 1) {
                    buckets *= buckets < 512 ? 8 : 2;
                }
                List<String> grown = new ArrayList<>(order);
                Map<Long, String> firstOfBucket = new HashMap<>();
                for (String element : order) {
                    long bucket = hashes.get(element) & (buckets - 1);
                    String first = firstOfBucket.put(bucket, element);
                    if (first != null) {
                        grown.remove(element);
                        grown.add(grown.indexOf(first), element);
                    }
                }
                order = grown;
            }
            long bucket = hashes.get(name) & (buckets - 1);
            int at = order.size();
            for (int i = 0; i < order.size(); i++) {
                if ((hashes.get(order.get(i)) & (buckets - 1)) == bucket) {
                    at = i;
                    break;
                }
            }
            order.add(at, name);
        }
        return order;
    }
}
