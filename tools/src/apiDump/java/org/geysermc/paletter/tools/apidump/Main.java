package org.geysermc.paletter.tools.apidump;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code dump} writes the API dump of paletter's compiled classes, {@code check} fails (exit 1, with the difference) if the
 * committed file is not what the classes give.
 * <pre>
 * Main dump|check --classes DIR[;DIR...] --module-info FILE --file FILE
 * </pre>
 * The exported packages are those of the {@code module-info.java}, the one place that says what is API.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: dump|check --classes DIRS --module-info FILE --file FILE");
        }
        String mode = args[0];
        List<Path> classes = new ArrayList<>();
        Path moduleInfo = null;
        Path file = null;
        for (int i = 1; i < args.length; i += 2) {
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + args[i]);
            }
            switch (args[i]) {
                case "--classes" -> {
                    for (String directory : args[i + 1].split(java.io.File.pathSeparator)) {
                        classes.add(Path.of(directory));
                    }
                }
                case "--module-info" -> moduleInfo = Path.of(args[i + 1]);
                case "--file" -> file = Path.of(args[i + 1]);
                default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
            }
        }
        if (classes.isEmpty() || moduleInfo == null || file == null) {
            throw new IllegalArgumentException("--classes, --module-info and --file are required");
        }
        Set<String> packages = ApiDump.exports(Files.readString(moduleInfo, StandardCharsets.UTF_8));
        String generated = ApiDump.dump(ApiDump.read(classes), packages);
        switch (mode) {
            case "dump" -> {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.writeString(file, generated, StandardCharsets.UTF_8);
                System.out.println("Wrote " + file + " (" + generated.lines().count() + " lines, " + packages.size() + " packages)");
            }
            case "check" -> {
                String committed = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n") : "";
                if (committed.equals(generated)) {
                    System.out.println("The API dump " + file + " is up to date");
                    return;
                }
                System.err.println("The API dump " + file + " is stale. Differences (- committed, + current):\n");
                System.err.println(ApiDump.diff(committed, generated));
                System.err.println("If the change of the API is intended, run ./gradlew :paletter:apiDump and commit the file.");
                System.exit(1);
            }
            default -> throw new IllegalArgumentException("Unknown mode " + mode);
        }
    }
}
