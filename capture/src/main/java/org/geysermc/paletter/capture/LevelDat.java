package org.geysermc.paletter.capture;

import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * BDS has no server.properties switch for experiments: they live in the world's {@code level.dat}
 * (8 byte header: storage version and payload length, both little endian int32, then little endian NBT) under
 * {@code experiments: {<toggle>: 1b, experiments_ever_used: 1b, saved_with_toggled_experiments: 1b}}.
 * Create the world by starting BDS once, stop it, run this, start again.
 * <p>
 * BDS 1.26.63.1 reads the compound on start and writes it back on stop with only the toggles it knows: unknown keys
 * are dropped without a log line. That makes a start/stop round trip the oracle for which toggle ids exist (see
 * {@code generator}'s experiment discovery).
 */
public final class LevelDat {
    /** Bookkeeping keys of the {@code experiments} compound; not toggles. */
    public static final String EVER_USED = "experiments_ever_used";
    public static final String SAVED_WITH_TOGGLED = "saved_with_toggled_experiments";

    private LevelDat() {
    }

    /** The parsed file: storage version and root compound. */
    record Contents(int storageVersion, NbtMap root) {
    }

    static Contents read(byte[] bytes) throws IOException {
        if (bytes.length < 9) {
            throw new IOException("level.dat too short: " + bytes.length + " bytes");
        }
        ByteBuffer header = ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN);
        int storageVersion = header.getInt();
        int length = header.getInt();
        if (length != bytes.length - 8) {
            throw new IOException("level.dat header says " + length + " payload bytes, file has " + (bytes.length - 8));
        }
        try (NBTInputStream input = NbtUtils.createReaderLE(new ByteArrayInputStream(bytes, 8, bytes.length - 8))) {
            Object tag = input.readTag();
            if (!(tag instanceof NbtMap root)) {
                throw new IOException("level.dat root is not a compound");
            }
            return new Contents(storageVersion, root);
        }
    }

    static byte[] write(Contents contents) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (NBTOutputStream output = NbtUtils.createWriterLE(payload)) {
            output.writeTag(contents.root());
        }
        ByteBuffer out = ByteBuffer.allocate(8 + payload.size()).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(contents.storageVersion()).putInt(payload.size()).put(payload.toByteArray());
        return out.array();
    }

    /**
     * @return the {@code experiments} compound as {@code key -> value != 0}, including the two bookkeeping keys; empty if
     *         the world has none
     */
    public static Map<String, Boolean> experiments(Path file) throws IOException {
        return experiments(Files.readAllBytes(file));
    }

    static Map<String, Boolean> experiments(byte[] bytes) throws IOException {
        Map<String, Boolean> toggles = new TreeMap<>();
        read(bytes).root().getCompound("experiments", NbtMap.EMPTY).forEach((key, value) ->
            toggles.put(key, value instanceof Number number && number.intValue() != 0));
        return toggles;
    }

    /** Toggles switched on, without the bookkeeping keys. */
    public static List<String> enabled(Map<String, Boolean> experiments) {
        return experiments.entrySet().stream()
            .filter(entry -> entry.getValue() && !entry.getKey().equals(EVER_USED) && !entry.getKey().equals(SAVED_WITH_TOGGLED))
            .map(Map.Entry::getKey).sorted().toList();
    }

    /** Switches toggles on, keeping the ones already set. */
    static void enableExperiments(Path file, List<String> toggles) throws IOException {
        Files.write(file, enable(Files.readAllBytes(file), toggles, false));
        System.out.println("Enabled experiments in " + file + ": " + toggles);
    }

    /**
     * Replaces every toggle with exactly {@code toggles}. {@code experiments_ever_used} stays set once it was (BDS
     * never clears it); {@code saved_with_toggled_experiments} is set when any toggle is on.
     */
    public static void setExperiments(Path file, Collection<String> toggles) throws IOException {
        Files.write(file, enable(Files.readAllBytes(file), toggles, true));
    }

    static byte[] enable(byte[] bytes, Collection<String> toggles, boolean replace) throws IOException {
        Contents contents = read(bytes);
        NbtMap existing = contents.root().getCompound("experiments", NbtMap.EMPTY);
        NbtMapBuilder experiments = NbtMap.builder();
        boolean everUsed = existing.getBoolean(EVER_USED, false);
        if (!replace) {
            experiments.putAll(existing);
        }
        for (String toggle : toggles) {
            String key = toggle.strip();
            if (key.isEmpty() || key.equals(EVER_USED) || key.equals(SAVED_WITH_TOGGLED)) {
                throw new IllegalArgumentException("Not an experiment toggle: '" + toggle + "'");
            }
            experiments.putBoolean(key, true);
        }
        boolean any = experiments.entrySet().stream().anyMatch(entry -> !entry.getKey().equals(EVER_USED)
            && !entry.getKey().equals(SAVED_WITH_TOGGLED) && entry.getValue() instanceof Number n && n.intValue() != 0);
        experiments.putBoolean(EVER_USED, everUsed || any);
        experiments.putBoolean(SAVED_WITH_TOGGLED, any);
        NbtMap root = contents.root().toBuilder().putCompound("experiments", experiments.build()).build();
        return write(new Contents(contents.storageVersion(), root));
    }
}
