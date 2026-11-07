package org.geysermc.paletter.codegen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Component code generation. Modes:
 * <ul>
 *     <li>{@code generate}: infer the spec from wire NBT, write it, generate the component types from it plus the
 *     overrides, and write {@code needs-capture.txt}</li>
 *     <li>{@code check}: the same in memory; fails if any committed output differs</li>
 * </ul>
 */
public final class Codegen {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final String SPEC = "codegen/spec/inferred.json";
    static final String OVERRIDES = "codegen/overrides.json";
    static final String NEEDS_CAPTURE = "codegen/needs-capture.txt";
    static final String COMPONENT_DIR = "paletter/src/main/java/org/geysermc/paletter/component";
    static final List<String> GENERATED_PACKAGES = List.of("item", "block");

    private Codegen() {
    }

    public static void main(String[] args) throws IOException {
        String mode = args[0];
        Path root = Path.of(".");
        List<Path> captures = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> root = Path.of(args[++i]);
                case "--captures" -> captures.add(Path.of(args[++i]));
                default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
            }
        }

        JsonObject spec = Inference.infer(root, captures);
        JsonObject overrides = JsonParser.parseString(Files.readString(root.resolve(OVERRIDES), StandardCharsets.UTF_8)).getAsJsonObject();

        Map<String, String> outputs = new TreeMap<>();
        outputs.put(SPEC, GSON.toJson(spec) + "\n");
        if (mode.equals("generate")) {
            // Written first, so a spec the overrides do not cover yet can be inspected
            Files.createDirectories(root.resolve(SPEC).getParent());
            Files.writeString(root.resolve(SPEC), outputs.get(SPEC), StandardCharsets.UTF_8);
        }
        // The generator reads the spec back from its serialised form, so the committed file is the whole truth
        Generator generator = new Generator(JsonParser.parseString(outputs.get(SPEC)).getAsJsonObject(), overrides);
        generator.generate().forEach((file, content) -> outputs.put(COMPONENT_DIR + "/" + file, content));
        outputs.put(NEEDS_CAPTURE, generator.needsCapture());

        switch (mode) {
            case "generate" -> write(root, outputs);
            case "check" -> check(root, outputs);
            default -> throw new IllegalArgumentException("Unknown mode " + mode);
        }
    }

    private static List<Path> stale(Path root, Map<String, String> outputs) throws IOException {
        List<Path> stale = new ArrayList<>();
        for (String pkg : GENERATED_PACKAGES) {
            Path dir = root.resolve(COMPONENT_DIR).resolve(pkg);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    if (!outputs.containsKey(COMPONENT_DIR + "/" + pkg + "/" + file.getFileName())) {
                        stale.add(file);
                    }
                }
            }
        }
        return stale;
    }

    private static void write(Path root, Map<String, String> outputs) throws IOException {
        for (Path file : stale(root, outputs)) {
            Files.delete(file);
        }
        int changed = 0;
        for (Map.Entry<String, String> output : outputs.entrySet()) {
            Path file = root.resolve(output.getKey());
            if (Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8).equals(output.getValue())) {
                continue;
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, output.getValue(), StandardCharsets.UTF_8);
            changed++;
        }
        System.out.println("Wrote " + changed + " of " + outputs.size() + " files");
    }

    private static void check(Path root, Map<String, String> outputs) throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path file : stale(root, outputs)) {
            problems.add("stale generated file " + file);
        }
        for (Map.Entry<String, String> output : outputs.entrySet()) {
            Path file = root.resolve(output.getKey());
            if (!Files.exists(file)) {
                problems.add("missing " + output.getKey());
            } else if (!Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n").equals(output.getValue())) {
                problems.add("outdated " + output.getKey());
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Generated component sources are out of date; run ./gradlew :codegen:generateComponents\n  "
                + String.join("\n  ", problems));
        }
        System.out.println("All " + outputs.size() + " generated files are up to date");
    }
}
