package dev.opencollab.paletter.internal.shape;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * One vanilla voxel shape registry as the generated code ({@code internal.vanilla.shapes}) lists it: the shapes in id
 * order, each with the name BDS sends or the block BDS makes it for. Not API.
 */
public final class VoxelShapeList {
    private final List<VoxelShapeStore.Entry> entries = new ArrayList<>();

    /** The next shape, which BDS sends with a name. */
    public void named(@NonNull String name, @NonNull VoxelGrid grid) {
        entries.add(new VoxelShapeStore.Entry(grid, name, null, null));
    }

    /** The next shape, which BDS makes for {@code block}. */
    public void block(@NonNull VoxelGrid grid, @NonNull String block) {
        entries.add(new VoxelShapeStore.Entry(grid, null, block, block));
    }

    /**
     * The next shape, which BDS makes for one of the blocks from {@code block} to {@code lastBlock} in its order of
     * blocks: the capture could not tell which.
     */
    public void block(@NonNull VoxelGrid grid, @NonNull String block, @NonNull String lastBlock) {
        entries.add(new VoxelShapeStore.Entry(grid, null, block, lastBlock));
    }

    public @NonNull List<VoxelShapeStore.Entry> entries() {
        return List.copyOf(entries);
    }
}
