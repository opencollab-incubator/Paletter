package org.geysermc.paletter.tools.codegen.constants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code :tools:generateConstants} ({@code generate}) and {@code :tools:checkConstants} ({@code check}, part of
 * {@code ./gradlew check}: fails if the committed constants, enums or history differ from what the committed data,
 * names and history give).
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        String mode = args[0];
        Path root = Path.of(".");
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--root")) {
                root = Path.of(args[++i]);
            } else {
                throw new IllegalArgumentException("Unknown argument " + args[i]);
            }
        }
        ConstantsGenerator.Result result = ConstantsGenerator.generate(root);
        switch (mode) {
            case "generate" -> write(root, result);
            case "check" -> check(root, result);
            default -> throw new IllegalArgumentException("Unknown mode " + mode);
        }
    }

    /** Generated files that the result no longer has: enums, and the preview classes once no preview is bundled. */
    public static List<Path> stale(Path root, Map<String, String> files) throws IOException {
        List<Path> stale = new ArrayList<>();
        Path dir = root.resolve(ConstantsGenerator.STATE_DIR);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> list = Files.list(dir)) {
                list.filter(f -> !files.containsKey(ConstantsGenerator.STATE_DIR + "/" + f.getFileName())).forEach(stale::add);
            }
        }
        for (String preview : List.of("BedrockPreviewBlocks.java", "BedrockPreviewItems.java")) {
            String path = ConstantsGenerator.PACKAGE_DIR + "/" + preview;
            if (!files.containsKey(path) && Files.exists(root.resolve(path))) {
                stale.add(root.resolve(path));
            }
        }
        return stale;
    }

    private static void write(Path root, ConstantsGenerator.Result result) throws IOException {
        for (Path file : stale(root, result.files())) {
            Files.delete(file);
        }
        int changed = 0;
        for (Map.Entry<String, String> output : result.files().entrySet()) {
            Path file = root.resolve(output.getKey());
            if (Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8).equals(output.getValue())) {
                continue;
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, output.getValue(), StandardCharsets.UTF_8);
            changed++;
        }
        System.out.println(summary(result));
        System.out.println("Wrote " + changed + " of " + result.files().size() + " files");
    }

    static String summary(ConstantsGenerator.Result result) {
        StringBuilder out = new StringBuilder("Constants of " + result.release() + (result.preview() == null ? "" : " (preview " + result.preview() + ")")
            + ": " + result.counts());
        out.append("\nBlocks with a lookup table instead of strides: ").append(result.tableBlocks().isEmpty() ? "none" : "");
        result.tableBlocks().forEach(block -> out.append("\n  ").append(block));
        if (!result.added().isEmpty()) {
            out.append("\nAdded: ").append(result.added().size());
            result.added().stream().limit(50).forEach(added -> out.append("\n  ").append(added));
            if (result.added().size() > 50) {
                out.append("\n  ...");
            }
        }
        if (!result.deprecated().isEmpty()) {
            out.append("\nDeprecated: ").append(result.deprecated().size());
            result.deprecated().forEach(deprecated -> out.append("\n  ").append(deprecated));
        }
        return out.toString();
    }

    private static void check(Path root, ConstantsGenerator.Result result) throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path file : stale(root, result.files())) {
            problems.add("stale generated file " + file);
        }
        for (Map.Entry<String, String> output : result.files().entrySet()) {
            Path file = root.resolve(output.getKey());
            if (!Files.exists(file)) {
                problems.add("missing " + output.getKey());
            } else if (!Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n").equals(output.getValue())) {
                problems.add("outdated " + output.getKey());
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Generated constants are out of date; run ./gradlew :tools:generateConstants\n  "
                + String.join("\n  ", problems));
        }
        System.out.println("All " + result.files().size() + " generated constants files are up to date");
    }
}
