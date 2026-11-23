package dev.opencollab.paletter.tools.codegen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Writing and checking the sources a generator owns, the same way for every generator: a file that is missing or differs is
 * written (or reported when only checking), a file of the generator's own directories that has its header and is no longer
 * generated is deleted (or reported), and a file without the header is hand-written and never touched. Line endings do not count:
 * a checkout with CRLF is not out of date.
 */
public final class GeneratedSources {
    private GeneratedSources() {
    }

    /**
     * The command line of a generator's {@code main}: {@code generate|check [--root DIR] [--sources DIR]}.
     *
     * @param sources where the generated sources go, or null for the generator's own place
     */
    public record Arguments(boolean check, Path root, Path sources) {
        /** @throws IllegalArgumentException for anything else (a misspelt option must not write into the committed sources) */
        public static Arguments parse(String[] args, boolean allowSources) {
            if (args.length == 0 || !List.of("generate", "check").contains(args[0])) {
                throw new IllegalArgumentException("Expected generate or check as the first argument");
            }
            Path root = Path.of(".");
            Path sources = null;
            for (int i = 1; i < args.length; i++) {
                if (args[i].equals("--root") && i + 1 < args.length) {
                    root = Path.of(args[++i]);
                } else if (allowSources && args[i].equals("--sources") && i + 1 < args.length) {
                    sources = Path.of(args[++i]);
                } else {
                    throw new IllegalArgumentException("Unknown argument " + args[i]);
                }
            }
            return new Arguments(args[0].equals("check"), root, sources);
        }
    }

    /**
     * Brings {@code directory} to {@code files}.
     *
     * @param files the generated files by path relative to {@code directory}, with {@code /} as the separator
     * @param ownedDirectories the directories (relative, not searched further down) whose files with {@code header} this generator
     *                         owns: one that is not in {@code files} is stale
     * @param header how the first line of a generated file starts
     * @param check whether to only report
     * @return what differs: {@code <path>: missing}, {@code <path>: outdated} or {@code <path>: no longer generated}, in that order
     */
    public static List<String> sync(Path directory, Map<String, String> files, List<String> ownedDirectories, String header, boolean check)
        throws IOException {
        List<String> changes = new ArrayList<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = directory.resolve(file.getKey());
            String existing = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n") : null;
            if (file.getValue().equals(existing)) {
                continue;
            }
            changes.add(file.getKey() + (existing == null ? ": missing" : ": outdated"));
            if (!check) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
            }
        }
        for (Path path : stale(directory, files, ownedDirectories, header)) {
            changes.add(directory.relativize(path).toString().replace('\\', '/') + ": no longer generated");
            if (!check) {
                Files.delete(path);
            }
        }
        return changes;
    }

    /** The files of the owned directories that have the header and are not in {@code files}. */
    public static List<Path> stale(Path directory, Map<String, String> files, List<String> ownedDirectories, String header) throws IOException {
        List<Path> stale = new ArrayList<>();
        for (String owned : ownedDirectories) {
            Path dir = directory.resolve(owned);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> list = Files.list(dir)) {
                for (Path path : list.filter(Files::isRegularFile).sorted().toList()) {
                    String relative = directory.relativize(path).toString().replace('\\', '/');
                    if (!files.containsKey(relative) && Files.readString(path, StandardCharsets.UTF_8).startsWith(header)) {
                        stale.add(path);
                    }
                }
            }
        }
        return stale;
    }

    /**
     * Prints what {@link #sync} found, one line per file, and the one failure message of a check.
     *
     * @param task the Gradle task that regenerates, named in the message
     * @return the exit code: 1 if a check found anything, else 0
     */
    public static int report(List<String> changes, boolean check, String task) {
        changes.forEach(change -> System.out.println((check ? "stale: " : "written: ") + change));
        if (check && !changes.isEmpty()) {
            System.err.println(changes.size() + " generated files are stale: run ./gradlew " + task);
            return 1;
        }
        return 0;
    }
}
