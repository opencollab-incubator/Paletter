package org.geysermc.paletter.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class FileUtils {
    public static void safeDelete(Path path) {
        try {
            delete(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void delete(Path path) throws IOException {
        if (Files.notExists(path)) return;

        if (Files.isDirectory(path)) {
            Files.list(path).forEach(FileUtils::safeDelete);
        }
        Files.delete(path);
    }

    public static void copy(Path source, Path dest) throws IOException {
        // Yes, hacky, I hate this too.
        if (source.getClass().getSimpleName().equals("ZipPath")) {
            Files.write(dest, Files.readAllBytes(source));
            return;
        }

        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
    }
}
