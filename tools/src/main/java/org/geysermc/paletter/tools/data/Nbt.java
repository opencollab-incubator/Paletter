package org.geysermc.paletter.tools.data;

import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/** The one place the tooling reads NBT: gzip detection, either byte order, base64 payloads. */
public final class Nbt {
    private Nbt() {
    }

    /** Reads a file of big or little endian NBT, gzipped or not. */
    public static NbtMap read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > 1 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
            try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                bytes = gzip.readAllBytes();
            }
        }
        try (NBTInputStream input = NbtUtils.createReader(new ByteArrayInputStream(bytes))) {
            return (NbtMap) input.readTag();
        } catch (Exception bigEndian) {
            try (NBTInputStream input = NbtUtils.createReaderLE(new ByteArrayInputStream(bytes))) {
                return (NbtMap) input.readTag();
            }
        }
    }
}
