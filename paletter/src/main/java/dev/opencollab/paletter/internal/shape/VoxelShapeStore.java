package dev.opencollab.paletter.internal.shape;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.SerializableVoxelShape;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.data.PaletteDataCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The bundled voxel shape registries of vanilla Bedrock ({@code voxel_shapes.nbt}): per data version the shapes BDS
 * sends a client of a world without add-ons, in its order (and, where it differs, of a world with the experiment
 * {@value #EXPERIMENT}). Generated from the captures in {@code data/bds} by the
 * tools ({@code voxel-shapes import}); registries that are equal are stored once with the versions they hold for.
 * <p>
 * File: gzipped NBT {@code {_generated, registries: [{versions: [name], experiment, shapes: [{x, y, z: [float], cells: byte[],
 * name?, block?, lastBlock?}]}]}}, the cells packed as on the wire ({@link VoxelGrid}).
 */
public final class VoxelShapeStore {
    /** The experiment toggle that changed the vanilla shapes of some versions. */
    public static final String EXPERIMENT = "voxel_shapes";
    private static final VoxelShapeStore EMPTY = new VoxelShapeStore(List.of());

    /**
     * One shape of a vanilla registry; its position in the list is its id.
     *
     * @param name the name BDS sends for it ({@code minecraft:anvil}), or null: a shape BDS makes for a block
     * @param block for a shape without a name, the vanilla block BDS makes it for: the first one, in BDS's order of
     *              blocks ({@code BlockRegistry#nameHash}), that needs it
     * @param lastBlock the same as {@code block}, unless the capture could not tell which block of a run of blocks with
     *                  neighbouring hashes it is: then the last block of that run
     */
    public record Entry(@NonNull VoxelGrid grid, @Nullable String name, @Nullable String block, @Nullable String lastBlock) {
    }

    /**
     * @param versions the names of the data versions the shapes hold for
     * @param experiment whether this is the registry of a world with the experiment {@value #EXPERIMENT} on; only stored
     *                   for versions where that differs from the one without
     */
    public record Registry(@NonNull List<String> versions, boolean experiment, @NonNull List<Entry> shapes) {
        public Registry {
            versions = List.copyOf(versions);
            shapes = List.copyOf(shapes);
        }
    }

    private final List<Registry> registries;

    public VoxelShapeStore(@NonNull List<Registry> registries) {
        this.registries = List.copyOf(registries);
    }

    public static @NonNull VoxelShapeStore empty() {
        return EMPTY;
    }

    public @NonNull List<Registry> registries() {
        return registries;
    }

    /**
     * @param experiment whether the world has the experiment {@value #EXPERIMENT} on
     * @return the vanilla shapes of a version in id order, or null if the store has none of the version
     */
    public @Nullable List<Entry> shapes(@NonNull DataVersion version, boolean experiment) {
        List<Entry> plain = null;
        for (Registry registry : registries) {
            if (registry.versions().contains(version.name())) {
                if (registry.experiment() == experiment) {
                    return registry.shapes();
                }
                plain = registry.experiment() ? plain : registry.shapes();
            }
        }
        return plain;
    }

    /** @return whether the experiment {@value #EXPERIMENT} changes the vanilla shapes of a version */
    public boolean experimentMatters(@NonNull DataVersion version) {
        for (Registry registry : registries) {
            if (registry.experiment() && registry.versions().contains(version.name())) {
                return true;
            }
        }
        return false;
    }

    public static @NonNull VoxelShapeStore read(@NonNull InputStream stream) throws IOException {
        NbtMap root = PaletteDataCodec.readGzip(stream);
        List<Registry> registries = new ArrayList<>();
        for (NbtMap registry : root.getList("registries", NbtType.COMPOUND)) {
            List<Entry> shapes = new ArrayList<>();
            for (NbtMap shape : registry.getList("shapes", NbtType.COMPOUND)) {
                List<Float> x = shape.getList("x", NbtType.FLOAT);
                List<Float> y = shape.getList("y", NbtType.FLOAT);
                List<Float> z = shape.getList("z", NbtType.FLOAT);
                List<Short> storage = new ArrayList<>();
                for (byte bits : shape.getByteArray("cells")) {
                    storage.add((short) (bits & 0xff));
                }
                VoxelGrid grid = VoxelGrid.ofWire(new SerializableVoxelShape(new SerializableVoxelShape.SerializableCells(
                    (short) (x.size() - 1), (short) (y.size() - 1), (short) (z.size() - 1), storage), x, y, z));
                shapes.add(new Entry(grid, (String) shape.get("name"), (String) shape.get("block"), (String) shape.get("lastBlock")));
            }
            registries.add(new Registry(registry.getList("versions", NbtType.STRING), registry.getBoolean("experiment", false), shapes));
        }
        return new VoxelShapeStore(registries);
    }

    /** @param generated the marker of the file: the command that writes it */
    public void write(@NonNull OutputStream stream, @NonNull String generated) throws IOException {
        List<NbtMap> list = new ArrayList<>();
        for (Registry registry : registries) {
            List<NbtMap> shapes = new ArrayList<>();
            for (Entry entry : registry.shapes()) {
                SerializableVoxelShape wire = entry.grid().toWire();
                byte[] cells = new byte[wire.getCells().getStorage().size()];
                for (int i = 0; i < cells.length; i++) {
                    cells[i] = wire.getCells().getStorage().get(i).byteValue();
                }
                NbtMapBuilder shape = NbtMap.builder()
                    .putList("x", NbtType.FLOAT, wire.getXCoordinates())
                    .putList("y", NbtType.FLOAT, wire.getYCoordinates())
                    .putList("z", NbtType.FLOAT, wire.getZCoordinates())
                    .putByteArray("cells", cells);
                if (entry.name() != null) {
                    shape.putString("name", entry.name());
                }
                if (entry.block() != null) {
                    shape.putString("block", entry.block());
                }
                if (entry.lastBlock() != null) {
                    shape.putString("lastBlock", entry.lastBlock());
                }
                shapes.add(shape.build());
            }
            list.add(NbtMap.builder()
                .putList("versions", NbtType.STRING, registry.versions())
                .putBoolean("experiment", registry.experiment())
                .putList("shapes", NbtType.COMPOUND, shapes).build());
        }
        NbtMap root = NbtMap.builder().putString("_generated", generated).putList("registries", NbtType.COMPOUND, list).build();
        try (NBTOutputStream output = NbtUtils.createGZIPWriter(stream)) {
            output.writeTag(root);
        }
    }
}
