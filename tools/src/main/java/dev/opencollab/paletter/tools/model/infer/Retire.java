package dev.opencollab.paletter.tools.model.infer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.behaviour.BehaviourGenerator;
import dev.opencollab.paletter.tools.behaviour.BehaviourStore;
import dev.opencollab.paletter.tools.model.MappingWindows;
import dev.opencollab.paletter.tools.model.Model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * {@code retire <window protocol>}: drops a protocol Paletter no longer bundles from the component model. In
 * {@code mappings.json} and {@code mappings-evidence.json} it removes the protocol from every window (and a window left
 * without protocols), the captures of that window protocol, and its envelope windows; neighbouring windows that now have
 * the same wire shape ({@link MappingWindows#wireShape}) become one, keeping the older window's content as the inference
 * does. A data version no capture keeps any more is retired in the behaviour capture store ({@link #retireBehaviour}):
 * {@code generateBehaviour} then folds it into the base. What else belongs to the protocol is listed for the maintainer, not deleted: its {@code versions.txt} row and
 * bundled data, its {@code data/bds} build directories, its CloudburstMC/Data test data.
 */
public final class Retire {
    private Retire() {
    }

    /**
     * Retires a window protocol from the model files under {@code root}.
     *
     * @return what else must go, one line each
     * @throws IllegalArgumentException if no capture of {@code mappings.json} has that window protocol
     */
    public static List<String> run(Path root, int protocol) throws IOException {
        Path model = root.resolve("data/model");
        JsonObject mappings = read(model.resolve("mappings.json"));
        JsonObject evidence = Files.exists(model.resolve(MappingEvidence.FILE)) ? read(model.resolve(MappingEvidence.FILE)) : null;
        List<JsonObject> captures = retire(mappings, evidence, protocol);
        Files.writeString(model.resolve("mappings.json"), Model.GSON.toJson(mappings) + "\n", StandardCharsets.UTF_8);
        if (evidence != null) {
            Files.writeString(model.resolve(MappingEvidence.FILE), Model.GSON.toJson(evidence) + "\n", StandardCharsets.UTF_8);
        }
        List<String> out = retireBehaviour(root, mappings, captures);
        out.addAll(leftovers(root, captures));
        return out;
    }

    /**
     * Retires the data versions no capture keeps from the behaviour capture store: their captures stay (newer versions
     * inherit from them), but no code is generated for them, so the next generation folds what the oldest version had into
     * the base of the next one.
     *
     * @return what was done, one line each
     */
    static List<String> retireBehaviour(Path root, JsonObject mappings, List<JsonObject> retired) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.exists(root.resolve(BehaviourGenerator.CAPTURES))) {
            return out;
        }
        TreeSet<String> kept = new TreeSet<>();
        mappings.getAsJsonArray("captures").forEach(c -> kept.add(c.getAsJsonObject().get("dataVersion").getAsString()));
        BehaviourStore store = BehaviourGenerator.read(root);
        BehaviourStore before = store;
        for (JsonObject capture : retired) {
            String version = capture.get("dataVersion").getAsString();
            if (!kept.contains(version) && store.generated().contains(version)) {
                store = store.retire(version);
                out.add(BehaviourGenerator.CAPTURES + ": retired " + version + " (its captures stay as evidence); generateBehaviour makes "
                    + (store.generated().isEmpty() ? "no version" : store.generated().getFirst()) + " the base");
            }
        }
        if (store != before) {
            BehaviourGenerator.write(root, store);
        }
        return out;
    }

    /**
     * Retires a window protocol from the JSON of {@code mappings.json} and {@code mappings-evidence.json} (in place).
     *
     * @param evidence the evidence file, or null
     * @return the captures of {@code mappings.json} that were of the protocol
     */
    static List<JsonObject> retire(JsonObject mappings, JsonObject evidence, int protocol) {
        List<JsonObject> retired = new ArrayList<>();
        JsonArray captures = mappings.getAsJsonArray("captures");
        for (int i = captures.size() - 1; i >= 0; i--) {
            JsonObject capture = captures.get(i).getAsJsonObject();
            if (capture.get(capture.has("windowProtocol") ? "windowProtocol" : "protocol").getAsInt() == protocol) {
                retired.addFirst(capture);
                captures.remove(i);
            }
        }
        if (retired.isEmpty()) {
            throw new IllegalArgumentException("no capture of mappings.json has the window protocol " + protocol);
        }
        if (mappings.has("envelope")) {
            for (Map.Entry<String, JsonElement> kind : mappings.getAsJsonObject("envelope").entrySet()) {
                retireWindows(kind.getValue().getAsJsonArray(), null, protocol, w -> w.get("leaves"));
            }
        }
        JsonObject components = mappings.getAsJsonObject("components");
        JsonObject evidenceComponents = evidence == null ? new JsonObject() : evidence.getAsJsonObject("components");
        for (String id : new TreeSet<>(components.keySet())) {
            JsonArray windows = components.getAsJsonObject(id).getAsJsonArray(MappingWindows.WINDOWS);
            JsonArray evidenceWindows = evidenceComponents.has(id)
                ? evidenceComponents.getAsJsonObject(id).getAsJsonArray(MappingWindows.WINDOWS) : null;
            retireWindows(windows, evidenceWindows, protocol, MappingWindows::wireShape);
            if (windows.isEmpty()) {
                components.remove(id);
                evidenceComponents.remove(id);
            }
        }
        return retired;
    }

    /**
     * Removes the protocol from each window, drops a window left without one, and joins neighbours of the same shape
     * (the older keeps its content). The evidence windows, aligned with {@code windows}, follow.
     */
    private static void retireWindows(JsonArray windows, JsonArray evidence, int protocol, java.util.function.Function<JsonObject, JsonElement> shape) {
        if (evidence != null && evidence.size() != windows.size()) {
            throw new IllegalArgumentException("mappings-evidence.json has " + evidence.size() + " windows where mappings.json has "
                + windows.size() + ": run inferMappings first");
        }
        for (int i = windows.size() - 1; i >= 0; i--) {
            JsonArray protocols = windows.get(i).getAsJsonObject().getAsJsonArray("protocols");
            removeProtocol(protocols, protocol);
            if (evidence != null) {
                removeProtocol(evidence.get(i).getAsJsonObject().getAsJsonArray("protocols"), protocol);
            }
            if (protocols.isEmpty()) {
                windows.remove(i);
                if (evidence != null) {
                    evidence.remove(i);
                }
            }
        }
        for (int i = windows.size() - 1; i > 0; i--) {
            JsonObject older = windows.get(i - 1).getAsJsonObject();
            JsonObject newer = windows.get(i).getAsJsonObject();
            if (shape.apply(older).equals(shape.apply(newer))) {
                newer.getAsJsonArray("protocols").forEach(older.getAsJsonArray("protocols")::add);
                windows.remove(i);
                if (evidence != null) {
                    evidence.get(i).getAsJsonObject().getAsJsonArray("protocols").forEach(evidence.get(i - 1).getAsJsonObject().getAsJsonArray("protocols")::add);
                    evidence.remove(i);
                }
            }
        }
    }

    private static void removeProtocol(JsonArray protocols, int protocol) {
        for (int i = protocols.size() - 1; i >= 0; i--) {
            if (protocols.get(i).getAsInt() == protocol) {
                protocols.remove(i);
            }
        }
    }

    /**
     * What else belongs to the retired captures and exists under {@code root}: a data version or build another capture still
     * has is kept.
     */
    static List<String> leftovers(Path root, List<JsonObject> retired) throws IOException {
        JsonObject mappings = read(root.resolve("data/model/mappings.json"));
        TreeSet<String> keptVersions = new TreeSet<>();
        TreeSet<String> keptBuilds = new TreeSet<>();
        mappings.getAsJsonArray("captures").forEach(c -> {
            keptVersions.add(c.getAsJsonObject().get("dataVersion").getAsString());
            keptBuilds.add(c.getAsJsonObject().get("bdsVersion").getAsString());
        });
        List<String> out = new ArrayList<>();
        for (JsonObject capture : retired) {
            String version = capture.get("dataVersion").getAsString();
            String build = capture.get("bdsVersion").getAsString();
            if (!keptVersions.contains(version)) {
                Path versions = root.resolve("paletter/src/main/resources/paletter/versions.txt");
                if (Files.exists(versions) && Files.readAllLines(versions).stream().anyMatch(line -> line.startsWith(version + " "))) {
                    out.add("paletter/src/main/resources/paletter/versions.txt: the row of " + version);
                }
                existing(root, out, "paletter/src/main/resources/paletter/" + version + ".nbt",
                    "paletter-recipes/src/main/resources/paletter/recipes/" + version + ".nbt", "tools/src/test/resources/test-data/" + version + "/");
                Path source = root.resolve("tools/src/test/resources/test-data/SOURCE.md");
                if (Files.exists(source) && Files.readString(source).contains(version)) {
                    out.add("tools/src/test/resources/test-data/SOURCE.md: the row of " + version);
                }
            }
            if (!keptBuilds.contains(build)) {
                existing(root, out, "data/bds/" + build + "/", "paletter/src/test/resources/block-id-order/bds-" + build + ".txt");
            }
        }
        out.add("then: builds table, inferMappings (changes nothing once the captures are gone), generateComponentApi,"
            + " generateConstants and generateBehaviour (each its own Gradle run)");
        return out;
    }

    private static void existing(Path root, List<String> out, String... paths) {
        for (String path : paths) {
            if (Files.exists(root.resolve(path))) {
                out.add(path);
            }
        }
    }

    private static JsonObject read(Path file) throws IOException {
        return com.google.gson.JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
