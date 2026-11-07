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
import java.util.List;

/**
 * BDS has no server.properties switch for experiments: they live in the world's {@code level.dat}
 * (8 byte header: storage version and payload length, both little endian int32, then little endian NBT) under
 * {@code experiments: {<toggle>: 1b, experiments_ever_used: 1b, saved_with_toggled_experiments: 1b}}.
 * Create the world by starting BDS once, stop it, run this, start again.
 */
final class LevelDat {
    private LevelDat() {
    }

    static void enableExperiments(Path file, List<String> toggles) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer header = ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN);
        int storageVersion = header.getInt();
        NbtMap root;
        try (NBTInputStream input = NbtUtils.createReaderLE(new ByteArrayInputStream(bytes, 8, bytes.length - 8))) {
            root = (NbtMap) input.readTag();
        }
        NbtMapBuilder experiments = root.getCompound("experiments", NbtMap.EMPTY).toBuilder();
        for (String toggle : toggles) {
            experiments.putBoolean(toggle.strip(), true);
        }
        experiments.putBoolean("experiments_ever_used", true);
        experiments.putBoolean("saved_with_toggled_experiments", true);
        root = root.toBuilder().putCompound("experiments", experiments.build()).build();

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (NBTOutputStream output = NbtUtils.createWriterLE(payload)) {
            output.writeTag(root);
        }
        ByteBuffer out = ByteBuffer.allocate(8 + payload.size()).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(storageVersion).putInt(payload.size()).put(payload.toByteArray());
        Files.write(file, out.array());
        System.out.println("Enabled experiments in " + file + ": " + experiments.build());
    }
}
