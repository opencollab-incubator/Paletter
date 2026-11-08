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
 * Wire shape inference: the shape of every component observed in wire NBT (bundled vanilla data and the captures in
 * {@code codegen/captures}), written to {@code codegen/spec/inferred.json}. The generator's {@code update} pipeline
 * compares new captures with it to report wire format changes. It no longer generates code: the component API and its
 * encoders come from the component model ({@code codegen/model}, see {@code component.ComponentGenerator}). Modes:
 * <ul>
 *     <li>{@code generate}: infer the spec and write it</li>
 *     <li>{@code check}: the same in memory; fails if the committed spec differs</li>
 * </ul>
 */
public final class Codegen {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final String SPEC = "codegen/spec/inferred.json";
    static final String OVERRIDES = "codegen/overrides.json";

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

        JsonObject overrides = JsonParser.parseString(Files.readString(root.resolve(OVERRIDES), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject spec = Inference.infer(root, captures, overrides);

        Map<String, String> outputs = new TreeMap<>();
        outputs.put(SPEC, GSON.toJson(spec) + "\n");
        switch (mode) {
            case "generate" -> write(root, outputs);
            case "check" -> check(root, outputs);
            default -> throw new IllegalArgumentException("Unknown mode " + mode);
        }
    }

    private static void write(Path root, Map<String, String> outputs) throws IOException {
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
        for (Map.Entry<String, String> output : outputs.entrySet()) {
            Path file = root.resolve(output.getKey());
            if (!Files.exists(file)) {
                problems.add("missing " + output.getKey());
            } else if (!Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n").equals(output.getValue())) {
                problems.add("outdated " + output.getKey());
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("codegen/spec/inferred.json is out of date; run ./gradlew :codegen:inferWireShapes\n  "
                + String.join("\n  ", problems));
        }
        System.out.println("All " + outputs.size() + " generated files are up to date");
    }
}
