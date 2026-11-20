package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.Inputs;

import java.util.ArrayList;
import java.util.List;

/** The hand input of the generator: what {@code api.json}, {@code bds-feedback.json} and the hand transforms say about a component or field. */
final class Hand {
    final Inputs in;
    /** The names to keep ({@code api-history.json}); it also collects the names of this run. */
    final ApiHistory history;

    Hand(Inputs in) {
        this.in = in;
        this.history = new ApiHistory(in.history());
    }

    JsonObject api(String section) {
        return in.api().has(section) ? in.api().getAsJsonObject(section) : new JsonObject();
    }

    String apiString(String section, String key) {
        JsonObject s = api(section);
        return s.has(key) ? s.get(key).getAsString() : null;
    }

    /** Whether an api.json list ({@code optional}, {@code noDefaults}, {@code openEnums}, {@code blockDescriptors}) has the key. */
    boolean listed(String section, String key) {
        return in.api().has(section) && in.api().getAsJsonArray(section).asList().stream().anyMatch(e -> e.getAsString().equals(key));
    }

    /** What BDS said about a path of a component ({@code bds-feedback.json}): {@code required}, {@code range 0 15}, ... */
    List<String> notes(Comp c, String path) {
        JsonObject feedback = in.feedback().has(c.id) ? in.feedback().getAsJsonObject(c.id) : new JsonObject();
        List<String> notes = new ArrayList<>();
        if (feedback.has(path)) {
            feedback.getAsJsonArray(path).forEach(n -> notes.add(n.getAsString()));
        }
        return notes;
    }

    String standalonePrefix(String id) {
        JsonObject prefixes = api("standalonePaths");
        if (!prefixes.has(id)) {
            throw new GenerationException("api.json standalone: " + id + " needs its object path in standalonePaths");
        }
        return prefixes.get(id).getAsString();
    }

    /** The path {@code bds-feedback.json} has a field under. */
    String feedbackPath(Comp c, String path) {
        return c.standalone ? standalonePrefix(c.id) + "." + path : path;
    }

    /** The hand transform of a field ({@code ""} for the component's value), or null. */
    String transform(String id, String path) {
        return in.transforms().get(id + ":" + path);
    }

    /** The BDS the schema feedback is read from: the newest release capture (else the newest capture). */
    String bdsVersion() {
        return Captures.bdsVersion(in.mappings());
    }
}
