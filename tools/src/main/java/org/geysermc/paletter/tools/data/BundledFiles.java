package org.geysermc.paletter.tools.data;

import org.geysermc.paletter.internal.data.PaletteDataCodec;
import org.geysermc.paletter.internal.data.PropertyStore;
import org.geysermc.paletter.internal.data.VanillaData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The bundled data directory ({@code paletter/src/main/resources/paletter}): one {@code <version>.nbt} per version
 * and {@code properties.nbt}, the item components and data driven blocks of all of them with version ranges. Reads
 * put the two together, writes take them apart again.
 */
public final class BundledFiles {
    public static final String STORE = "properties.nbt";

    private BundledFiles() {
    }

    /** The properties of a directory; empty if it has none yet. */
    public static PropertyStore readStore(Path directory) throws IOException {
        Path file = directory.resolve(STORE);
        if (!Files.exists(file)) {
            return PropertyStore.empty();
        }
        try (InputStream stream = Files.newInputStream(file)) {
            return PropertyStore.read(stream);
        }
    }

    /** A version file with its properties from {@code properties.nbt} next to it. */
    public static VanillaData read(Path file) throws IOException {
        return read(file, readStore(file.toAbsolutePath().getParent()));
    }

    static VanillaData read(Path file, PropertyStore store) throws IOException {
        try (InputStream stream = Files.newInputStream(file)) {
            return store.attach(PaletteDataCodec.read(stream));
        }
    }

    /** Every version file of a directory, by name. */
    public static Map<String, VanillaData> readAll(Path directory) throws IOException {
        PropertyStore store = readStore(directory);
        Map<String, VanillaData> result = new TreeMap<>();
        for (Path file : versionFiles(directory)) {
            VanillaData data = read(file, store);
            result.put(data.version().name(), data);
        }
        return result;
    }

    private static List<Path> versionFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".nbt") && !f.getFileName().toString().equals(STORE))
                .sorted().toList();
        }
    }

    /**
     * Writes a version, and {@code properties.nbt} with the properties of it and of every other version file of the
     * directory.
     */
    public static void write(Path directory, VanillaData data) throws IOException {
        write(directory, List.of(data));
    }

    /** As {@link #write(Path, VanillaData)} for several versions at once. */
    public static void write(Path directory, List<VanillaData> changed) throws IOException {
        Files.createDirectories(directory);
        Map<String, VanillaData> all = readAll(directory);
        changed.forEach(data -> all.put(data.version().name(), data));
        for (VanillaData data : changed) {
            Files.write(directory.resolve(data.version().name() + ".nbt"), bytes(data));
        }
        writeStore(directory, new ArrayList<>(all.values()));
    }

    /**
     * Writes every version file of the directory in the current format and rebuilds {@code properties.nbt}. Nothing is
     * written unless the properties put together again from the new files equal the ones read.
     */
    public static void rewrite(Path directory) throws IOException {
        Map<String, VanillaData> all = readAll(directory);
        List<VanillaData> versions = new ArrayList<>(all.values());
        versions.sort(Comparator.comparing(VanillaData::version));
        ByteArrayOutputStream storeBytes = new ByteArrayOutputStream();
        PropertyStore.build(versions).write(storeBytes);
        PropertyStore store = PropertyStore.read(new ByteArrayInputStream(storeBytes.toByteArray()));
        for (VanillaData data : versions) {
            VanillaData again = store.attach(PaletteDataCodec.read(new ByteArrayInputStream(bytes(data))));
            if (!sameProperties(data, again)) {
                throw new IOException("The properties of " + data.version() + " do not survive the property store; nothing written");
            }
        }
        for (VanillaData data : versions) {
            Files.write(directory.resolve(data.version().name() + ".nbt"), bytes(data));
            System.out.println("Rewrote " + data.version().name() + ".nbt");
        }
        Files.write(directory.resolve(STORE), storeBytes.toByteArray());
        System.out.println("Wrote " + STORE + " with " + store.rangeCount() + " ranges");
    }

    private static void writeStore(Path directory, List<VanillaData> versions) throws IOException {
        versions.sort(Comparator.comparing(VanillaData::version));
        PropertyStore store = PropertyStore.build(versions);
        try (OutputStream stream = Files.newOutputStream(directory.resolve(STORE))) {
            store.write(stream);
        }
    }

    /** A version file's bytes: the data without the properties. */
    public static byte[] bytes(VanillaData data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PaletteDataCodec.write(data, bytes);
        return bytes.toByteArray();
    }

    /**
     * Whether two versions have the same network properties: the component data of every item (in order) and the
     * data driven blocks, in order (the order BDS sends them in decides the order of the block properties and of the
     * states in a hashed block palette).
     */
    public static boolean sameProperties(VanillaData a, VanillaData b) {
        if (a.items().size() != b.items().size() || a.dataDrivenBlocks().size() != b.dataDrivenBlocks().size()) {
            return false;
        }
        for (int i = 0; i < a.items().size(); i++) {
            if (!Objects.equals(a.items().get(i).componentData(), b.items().get(i).componentData())) {
                return false;
            }
        }
        return a.dataDrivenBlocks().equals(b.dataDrivenBlocks());
    }
}
