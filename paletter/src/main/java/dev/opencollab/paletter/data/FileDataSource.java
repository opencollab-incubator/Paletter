package dev.opencollab.paletter.data;

import dev.opencollab.paletter.internal.Lazy;
import dev.opencollab.paletter.internal.data.Renames;

import dev.opencollab.paletter.internal.data.VanillaData;

import dev.opencollab.paletter.internal.data.PaletteDataCodec;
import dev.opencollab.paletter.internal.behaviour.BehaviourStore;
import dev.opencollab.paletter.internal.data.PropertyStore;
import dev.opencollab.paletter.internal.shape.VoxelShapeStore;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads data in the bundled format from a set of files: {@code versions.txt} lists one
 * {@code <name> <protocol> [preview] [codec=<protocol>]} line per version (a {@link DataVersion#isPreview() preview}; the
 * {@link DataVersion#codecProtocol() codec} that decoded its captures where that was another protocol's), and each version is stored in {@code <name>.nbt}
 * (written by the generator, see {@link PaletteDataCodec}). Item components and data driven blocks are stored once with
 * their version ranges in {@code properties.nbt} (see {@link PropertyStore}), behaviour values in
 * {@code behaviour.nbt} (see {@link BehaviourStore}), the vanilla voxel shapes in {@code voxel_shapes.nbt} (see
 * {@link VoxelShapeStore}); {@code renames.txt} is optional, as is {@code builds.txt}: one {@code <name> <BDS build> <protocol>}
 * line per release build verified to send a version's data ({@link #verifiedBuilds}).
 * The files are either {@code /paletter/} on the classpath of the library ({@link PaletteDataSource#bundled()}) or a
 * directory ({@link PaletteDataSource#directory(Path)}).
 */
final class FileDataSource implements PaletteDataSource {
    static final FileDataSource BUNDLED = new FileDataSource(
        file -> FileDataSource.class.getResourceAsStream("/paletter/" + file), "the library jar");
    private final Opener opener;
    private final String origin;

    private final Lazy<List<DataVersion>> versions = new Lazy<>(this::readIndex);
    private final Lazy<Map<String, List<VerifiedBuild>>> verifiedBuilds = new Lazy<>(this::readBuilds);
    // Read once: versions may load on several threads at the same time
    private final Lazy<PropertyStore> properties = new Lazy<>(
        () -> readOrEmpty("properties.nbt", "properties", PropertyStore::read, PropertyStore.empty()));
    // behaviour.nbt is large, and Paletters of several threads may ask at the same time
    private final Lazy<BehaviourStore> behaviour = new Lazy<>(
        () -> readOrEmpty("behaviour.nbt", "behaviour data", BehaviourStore::read, BehaviourStore.empty()));
    private final Lazy<VoxelShapeStore> voxelShapes = new Lazy<>(
        () -> readOrEmpty("voxel_shapes.nbt", "voxel shapes", VoxelShapeStore::read, VoxelShapeStore.empty()));

    private FileDataSource(Opener opener, String origin) {
        this.opener = opener;
        this.origin = origin;
    }

    /** Reads a file of the set with {@code reader}, or returns {@code empty} if there is none. */
    private <T> T readOrEmpty(String file, String what, Reader<T> reader, T empty) {
        try (InputStream stream = opener.open(file)) {
            return stream == null ? empty : reader.read(stream);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read the Paletter " + what + " in " + origin, e);
        }
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(InputStream stream) throws IOException;
    }

    static FileDataSource directory(Path directory) {
        return new FileDataSource(file -> {
            Path path = directory.resolve(file);
            return Files.isRegularFile(path) ? Files.newInputStream(path) : null;
        }, directory.toString());
    }

    /** Opens a file of the set, or returns null if there is none. */
    @FunctionalInterface
    private interface Opener {
        @Nullable InputStream open(String file) throws IOException;
    }

    @Override
    public @NonNull List<DataVersion> versions() {
        return versions.get();
    }

    private List<DataVersion> readIndex() {
        try (InputStream stream = open("versions.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            List<DataVersion> versions = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                try {
                    versions.add(indexLine(line));
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("Invalid line in the Paletter version index in " + origin + ": " + e.getMessage(), e);
                }
            }
            Collections.sort(versions);
            return List.copyOf(versions);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read the Paletter version index in " + origin, e);
        }
    }

    /**
     * Reads a line of {@code versions.txt}: {@code <name> <protocol> [preview] [codec=<protocol>]}. {@code preview} marks
     * the data of a preview build, {@code codec=} the protocol of the codec that decoded its captures where that was not
     * its own.
     *
     * @throws IllegalArgumentException for a line with fewer fields, a number that is none, or a field it does not know
     */
    static DataVersion indexLine(String line) {
        String[] parts = line.strip().split("\\s+");
        if (parts.length < 2) {
            throw new IllegalArgumentException("expected `<name> <protocol> [preview] [codec=<protocol>]`, got `" + line + "`");
        }
        boolean preview = false;
        Integer codec = null;
        for (int i = 2; i < parts.length; i++) {
            if (parts[i].equals("preview") && !preview) {
                preview = true;
            } else if (parts[i].startsWith("codec=") && codec == null) {
                codec = Integer.parseInt(parts[i].substring("codec=".length()));
            } else {
                throw new IllegalArgumentException("unknown or repeated field `" + parts[i] + "` in `" + line + "`");
            }
        }
        return new DataVersion(parts[0], Integer.parseInt(parts[1]), preview, codec);
    }

    @Override
    public @NonNull List<VerifiedBuild> verifiedBuilds(@NonNull DataVersion version) {
        return verifiedBuilds.get().getOrDefault(version.name(), List.of());
    }

    private Map<String, List<VerifiedBuild>> readBuilds() {
        Map<String, List<VerifiedBuild>> builds = new HashMap<>();
        try (InputStream stream = opener.open("builds.txt")) {
            if (stream == null) {
                return Map.of();
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                builds.computeIfAbsent(parts[0], name -> new ArrayList<>()).add(new VerifiedBuild(parts[1], Integer.parseInt(parts[2])));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read the Paletter build list in " + origin, e);
        }
        builds.replaceAll((name, list) -> List.copyOf(list));
        return Map.copyOf(builds);
    }

    @Override
    public @NonNull VanillaData load(@NonNull DataVersion version) throws IOException {
        try (InputStream stream = open(version.name() + ".nbt")) {
            return properties().attach(PaletteDataCodec.read(stream));
        }
    }

    @Override
    public @NonNull PropertyStore properties() {
        return properties.get();
    }

    /**
     * The properties (about 1.1 MB for the bundled data) are put into the data of each version as it loads; once the
     * versions in use are loaded only {@code Paletter#properties} reads them, and reads the file again.
     */
    @Override
    public void releaseProperties() {
        properties.release();
    }

    @Override
    public @NonNull VoxelShapeStore voxelShapes() {
        return voxelShapes.get();
    }

    @Override
    public @NonNull BehaviourStore behaviour() {
        return behaviour.get();
    }

    @Override
    public @NonNull Renames renames() {
        try (InputStream stream = opener.open("renames.txt")) {
            if (stream == null) {
                return Renames.NONE;
            }
            return Renames.parse(new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).lines().toList());
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read the Paletter renames in " + origin, e);
        }
    }

    private InputStream open(String file) throws IOException {
        InputStream stream = opener.open(file);
        if (stream == null) {
            throw new IOException("Missing Paletter data file " + file + " in " + origin);
        }
        return stream;
    }
}
