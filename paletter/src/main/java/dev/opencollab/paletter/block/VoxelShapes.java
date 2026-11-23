package dev.opencollab.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.SerializableVoxelShape;
import org.cloudburstmc.protocol.bedrock.packet.VoxelShapesPacket;
import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.internal.shape.VoxelGrid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The voxel shapes of a palette: the shapes Bedrock culls the faces of neighbouring blocks with, which a server sends
 * every client before start game ({@code VoxelShapesPacket}, protocol 924 and later). Vanilla shapes are the ones BDS
 * of the palette's version sends; custom ones are those the layers define ({@code PaletteLayer.Builder#voxelShape})
 * and what Bedrock derives from them for the custom blocks that name them as the {@code culling_shape} of their
 * {@code minecraft:geometry}.
 * <p>
 * These are culling shapes, not collision or selection shapes ({@code BlockBehaviour#COLLISION_SHAPE} is a
 * {@code BlockShape}); the Script API does not expose them.
 *
 * @since 1.0.0
 */
public final class VoxelShapes {
    private final List<VoxelGrid> shapes;
    private final Map<String, Integer> names;
    private final int customShapeCount;
    /** The shapes as sent, made once: each login's packet shares them (a shape on the wire is boxed shorts and floats). */
    private final List<SerializableVoxelShape> wire;

    /**
     * Internal: creates the shapes of a palette, done by the palette compiler.
     *
     * @param shapes the shapes; the position of a shape is its id
     * @param names name to id, in the order Bedrock sends the names
     * @param customShapeCount how many shapes the palette has more than vanilla
     */
    @Internal
    public VoxelShapes(@NonNull List<VoxelGrid> shapes, @NonNull Map<String, Integer> names, int customShapeCount) {
        this.shapes = List.copyOf(shapes);
        this.names = Collections.unmodifiableMap(new LinkedHashMap<>(names));
        this.customShapeCount = customShapeCount;
        List<SerializableVoxelShape> wire = new ArrayList<>(this.shapes.size());
        for (VoxelGrid shape : this.shapes) {
            wire.add(shape.toWire());
        }
        this.wire = Collections.unmodifiableList(wire);
    }

    /**
     * The names of the shapes.
     *
     * @return the names of the shapes that have one ({@code minecraft:unit_cube}, {@code minecraft:anvil}, the custom
     * identifiers), in the order Bedrock sends them
     */
    public @NonNull Set<String> names() {
        return names.keySet();
    }

    /**
     * Looks a shape up by name.
     *
     * @param name the shape name, e.g. {@code minecraft:unit_cube}
     * @return the shape with this name, or null if there is none
     */
    public @Nullable VoxelShape get(@NonNull String name) {
        Integer id = names.get(name);
        return id == null ? null : new VoxelShape(shapes.get(id));
    }

    /**
     * The number of shapes.
     *
     * @return the number of shapes, named or not
     */
    public int size() {
        return shapes.size();
    }

    /**
     * Internal: builds the packet of these shapes.
     *
     * @return the packet to send a client before start game, identical to the one BDS sends for the same content
     */
    @Internal
    public @NonNull VoxelShapesPacket toPacket() {
        VoxelShapesPacket packet = new VoxelShapesPacket();
        packet.setShapes(new ArrayList<>(wire));
        packet.setNameMap(new LinkedHashMap<>(names));
        packet.setCustomShapeCount(customShapeCount);
        return packet;
    }

    @Override
    public String toString() {
        return "VoxelShapes{" + shapes.size() + " shapes, " + names.size() + " names}";
    }
}
