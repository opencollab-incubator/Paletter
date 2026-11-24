package dev.opencollab.paletter.internal.access;

import dev.opencollab.paletter.ResolutionKind;
import dev.opencollab.paletter.behaviour.BlockValues;
import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.block.ResolvedBlock;
import dev.opencollab.paletter.block.VoxelShape;
import dev.opencollab.paletter.block.VoxelShapes;
import dev.opencollab.paletter.internal.shape.VoxelGrid;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;

import java.util.List;
import java.util.Map;
import org.cloudburstmc.protocol.bedrock.packet.VoxelShapesPacket;

/** The package-private members of {@code dev.opencollab.paletter.block} that other packages use. Not API. */
public interface BlockAccess {
    /** {@code CustomBlock#stateProperties(int)}. */
    @NonNull List<BlockProperty<?>> stateProperties(@NonNull CustomBlock block, int protocol);

    /** {@code CustomBlock#states(int)}. */
    @NonNull List<NbtMap> states(@NonNull CustomBlock block, int protocol);

    /** {@code CustomBlock#defaultState(int)}. */
    @NonNull NbtMap defaultState(@NonNull CustomBlock block, int protocol);

    /** {@code BlockProperty.of(String, Type, List)}: a property of values already in their stored types. */
    @NonNull BlockProperty<?> property(@NonNull String name, BlockProperty.@NonNull Type type, @NonNull List<?> values);

    /** The constructor of {@link RegisteredBlockState}. */
    @NonNull RegisteredBlockState registeredBlockState(int runtimeId, @NonNull NbtMap state, boolean custom);

    /** The constructor of {@link ResolvedBlock}. */
    @NonNull ResolvedBlock resolvedBlock(@NonNull RegisteredBlockState definition, @NonNull ResolutionKind kind, @NonNull BlockValues behaviour);

    /** The constructor of {@link VoxelShapes}. */
    @NonNull VoxelShapes voxelShapes(@NonNull List<VoxelGrid> shapes, @NonNull Map<String, Integer> names, int customShapeCount);

    /** {@code VoxelShapes#toPacket()}. */
    @NonNull VoxelShapesPacket toPacket(@NonNull VoxelShapes shapes);

    /** {@code VoxelShape#grid()}: the grid of a shape. */
    @NonNull VoxelGrid grid(@NonNull VoxelShape shape);
}
