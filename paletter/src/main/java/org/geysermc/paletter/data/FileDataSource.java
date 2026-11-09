package org.geysermc.paletter.data;

import org.geysermc.paletter.internal.data.Renames;

import org.geysermc.paletter.internal.data.VanillaData;

import org.geysermc.paletter.internal.data.PaletteDataCodec;
import org.geysermc.paletter.internal.data.PropertyStore;

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
import java.util.List;

/**
 * Reads data in the bundled format from a set of files: {@code versions.txt} lists one
 * {@code <name> <protocol> [preview]} line per version (preview versions are skipped unless the system property
 * {@code paletter.previews} is {@code true}), and each version is stored in {@code <name>.nbt}
 * (written by the generator, see {@link PaletteDataCodec}). Item components and data driven blocks are stored once with
 * their version ranges in {@code properties.nbt} (see {@link PropertyStore}); {@code renames.txt} is optional.
 * The files are either {@code /paletter/} on the classpath of the library ({@link PaletteDataSource#bundled()}) or a
 * directory ({@link PaletteDataSource#directory(Path)}).
 */
final class FileDataSource implements PaletteDataSource {
    static final FileDataSource BUNDLED = new FileDataSource(
        file -> FileDataSource.class.getResourceAsStream("/paletter/" + file), "the library jar");
    private final Opener opener;
    private final String origin;
    /** Set to {@code true} to also load versions marked {@code preview} in {@code versions.txt}. */
    static final String PREVIEWS_PROPERTY = "paletter.previews";

    private volatile List<DataVersion> versions;
    private volatile PropertyStore properties;

    private FileDataSource(Opener opener, String origin) {
        this.opener = opener;
        this.origin = origin;
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
        List<DataVersion> versions = this.versions;
        if (versions == null) {
            this.versions = versions = readIndex();
        }
        return versions;
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
                String[] parts = line.split("\\s+");
                // "<name> <protocol> preview": data of a preview build, only loaded on request
                if (parts.length > 2 && parts[2].equals("preview") && !Boolean.getBoolean(PREVIEWS_PROPERTY)) {
                    continue;
                }
                versions.add(new DataVersion(parts[0], Integer.parseInt(parts[1])));
            }
            Collections.sort(versions);
            return List.copyOf(versions);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read the Paletter version index in " + origin, e);
        }
    }

    @Override
    public @NonNull VanillaData load(@NonNull DataVersion version) throws IOException {
        try (InputStream stream = open(version.name() + ".nbt")) {
            return properties().attach(PaletteDataCodec.read(stream));
        }
    }

    @Override
    public @NonNull PropertyStore properties() {
        PropertyStore properties = this.properties;
        if (properties == null) {
            try (InputStream stream = opener.open("properties.nbt")) {
                this.properties = properties = stream == null ? PropertyStore.empty() : PropertyStore.read(stream);
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to read the Paletter properties in " + origin, e);
            }
        }
        return properties;
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
