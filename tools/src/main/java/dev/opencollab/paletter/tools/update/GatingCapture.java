package dev.opencollab.paletter.tools.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.codegen.component.Gating;
import dev.opencollab.paletter.tools.model.Model;
import dev.opencollab.paletter.tools.model.addon.AddonGenerator;
import dev.opencollab.paletter.tools.model.rule.Leaf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code spec-capture --gating}: the components the creator docs mark as experiment-gated (the ones with {@code experiments} in
 * {@code components.json}, which also drive {@code @BedrockExperiment}), each as a base and as a control without it, loaded in a world
 * without experiments. BDS rejects the component (an error in the content log, or the file not sent), ignores it (sends the same as
 * the control), or accepts it. The result goes to {@code gating.json} ({@link Gating}), beside what the spec capture, whose world
 * has the experiments on, did with the same base.
 */
final class GatingCapture {
    private GatingCapture() {
    }

    /** The ids of the components with experiments, in the order of the model. */
    static List<String> gated(Model model) {
        return model.components().stream().filter(c -> !c.experiments().isEmpty()).map(Model.Component::id).toList();
    }

    /** Adds the base and the control of every gated component to a generator made with {@link AddonGenerator#empty}. */
    static void plan(AddonGenerator generator, String format) {
        for (String id : gated(generator.model())) {
            generator.probeBase(id);
            generator.controlAt(id, format);
        }
    }

    /**
     * @param experiments what start game of the world reported as on
     * @param leaves      the wire of what BDS sent ({@link SpecCapture#leaves})
     * @param log         the content log per variant identifier
     * @param spec        the spec capture of the same build, or a directory without one
     */
    static JsonObject report(AddonGenerator generator, String build, int protocol, List<String> experiments,
                             Map<String, Map<String, Leaf>> leaves, Map<String, List<String>> log, Path spec) throws IOException {
        Map<String, String> with = withExperiments(spec, build);
        List<Gating.Entry> entries = new ArrayList<>();
        for (String id : gated(generator.model())) {
            AddonGenerator.Variant base = variant(generator, id, "base");
            AddonGenerator.Variant control = variant(generator, id, "control");
            entries.add(new Gating.Entry(id, without(accepted(base.kind(), base.identifier(), leaves, log), leaves.get(key(base.kind(), base.identifier())),
                leaves.get(key(control.kind(), control.identifier()))), with.get(id)));
        }
        return Gating.render(build, protocol, experiments, entries);
    }

    /** Rejected: not sent or logged an error; ignored: sent the same as the control (the wire of the item or block without the component). */
    static String without(boolean accepted, Map<String, Leaf> base, Map<String, Leaf> control) {
        if (!accepted) {
            return Gating.REJECTED;
        }
        return base != null && base.equals(control) ? Gating.IGNORED : Gating.ACCEPTED;
    }

    private static AddonGenerator.Variant variant(AddonGenerator generator, String id, String role) {
        return generator.variants().stream().filter(v -> v.componentId().equals(id) && v.role().equals(role)).findFirst().orElseThrow();
    }

    private static String key(String kind, String identifier) {
        return kind + " " + identifier;
    }

    private static boolean accepted(String kind, String identifier, Map<String, Map<String, Leaf>> leaves, Map<String, List<String>> log) {
        return leaves.containsKey(key(kind, identifier)) && log.getOrDefault(identifier, List.of()).stream().noneMatch(line -> line.startsWith("ERROR"));
    }

    /**
     * What the spec capture of {@code build} did with the base of each component: accepted, rejected or ignored (see {@link #without}). Empty without a spec capture of
     * that build (a gating capture of another build must not be held against it).
     */
    static Map<String, String> withExperiments(Path spec, String build) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        Path capture = spec.resolve("capture.json");
        if (!Files.exists(capture) || !Files.exists(spec.resolve("variants.json")) || !JsonParser.parseString(Files.readString(capture, StandardCharsets.UTF_8))
            .getAsJsonObject().get("bdsVersion").getAsString().equals(build)) {
            return result;
        }
        Map<String, Map<String, Leaf>> leaves = SpecCapture.leaves(spec);
        JsonArray variants = JsonParser.parseString(Files.readString(spec.resolve("variants.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        List<String> identifiers = new ArrayList<>();
        variants.forEach(v -> identifiers.add(v.getAsJsonObject().get("identifier").getAsString()));
        Map<String, List<String>> log = SpecCapture.logByVariant(Files.readAllLines(spec.resolve("content-log.txt"), StandardCharsets.UTF_8), identifiers, new ArrayList<>());
        Map<String, JsonObject> bases = new LinkedHashMap<>();
        Map<String, JsonObject> controls = new LinkedHashMap<>();
        for (JsonElement element : variants) {
            JsonObject v = element.getAsJsonObject();
            String role = v.get("role").getAsString();
            if (role.equals("base")) {
                bases.put(v.get("component").getAsString(), v);
            } else if (role.equals("control")) {
                controls.putIfAbsent(v.get("component").getAsString(), v);
            }
        }
        // The same three answers as without experiments: a server-only component is ignored there, which proves nothing for its gating
        bases.forEach((id, base) -> {
            String kind = base.get("kind").getAsString();
            String identifier = base.get("identifier").getAsString();
            JsonObject control = controls.get(id);
            result.put(id, without(accepted(kind, identifier, leaves, log), leaves.get(key(kind, identifier)),
                control == null ? null : leaves.get(key(control.get("kind").getAsString(), control.get("identifier").getAsString()))));
        });
        return result;
    }
}
