package org.geysermc.paletter.tools.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.codegen.component.Gating;
import org.geysermc.paletter.tools.model.Model;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.geysermc.paletter.tools.model.rule.Leaf;

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
     * What the spec capture of {@code build} did with the base of each component: accepted or rejected. Empty without a spec capture of
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
        Map<String, List<String>> log = SpecCapture.logByVariant(Files.readAllLines(spec.resolve("content-log.txt"), StandardCharsets.UTF_8));
        for (JsonElement element : JsonParser.parseString(Files.readString(spec.resolve("variants.json"), StandardCharsets.UTF_8)).getAsJsonArray()) {
            JsonObject v = element.getAsJsonObject();
            if (v.get("role").getAsString().equals("base")) {
                result.put(v.get("component").getAsString(), accepted(v.get("kind").getAsString(), v.get("identifier").getAsString(), leaves, log)
                    ? Gating.ACCEPTED : Gating.REJECTED);
            }
        }
        return result;
    }
}
