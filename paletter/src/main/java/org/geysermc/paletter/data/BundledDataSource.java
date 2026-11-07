package org.geysermc.paletter.data;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads data from {@code /paletter/} on the classpath of the library: {@code versions.txt} lists one
 * {@code <name> <protocol>} pair per line, and each version is stored in {@code <name>.nbt}
 * (written by the generator, see {@link PaletteDataCodec}).
 */
final class BundledDataSource implements PaletteDataSource {
    static final BundledDataSource INSTANCE = new BundledDataSource();
    private static final String ROOT = "/paletter/";

    private volatile List<DataVersion> versions;

    private BundledDataSource() {
    }

    @Override
    public @NonNull List<DataVersion> versions() {
        List<DataVersion> versions = this.versions;
        if (versions == null) {
            this.versions = versions = readIndex();
        }
        return versions;
    }

    private static List<DataVersion> readIndex() {
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
                versions.add(new DataVersion(parts[0], Integer.parseInt(parts[1])));
            }
            Collections.sort(versions);
            return List.copyOf(versions);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read the bundled Paletter version index", e);
        }
    }

    @Override
    public @NonNull VanillaData load(@NonNull DataVersion version) throws IOException {
        try (InputStream stream = open(version.name() + ".nbt")) {
            return PaletteDataCodec.read(stream);
        }
    }

    private static InputStream open(String file) throws IOException {
        InputStream stream = BundledDataSource.class.getResourceAsStream(ROOT + file);
        if (stream == null) {
            throw new IOException("Missing bundled Paletter resource " + ROOT + file + " - is the library jar complete?");
        }
        return stream;
    }
}
