package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What {@code mappings.json} says about the captures of BDS: which protocols were captured, which BDS built them, the values
 * every capture agrees on.
 */
final class Captures {
    private Captures() {
    }

    static void read(ApiModel model, JsonObject mappings) {
        if (mappings.has("captures") && !mappings.getAsJsonArray("captures").isEmpty()) {
            List<Integer> captured = mappings.getAsJsonArray("captures").asList().stream()
                .filter(e -> e.getAsJsonObject().has("protocol")).map(e -> e.getAsJsonObject().get("protocol").getAsInt()).sorted().toList();
            if (!captured.isEmpty()) {
                model.oldestCaptured = captured.getFirst();
                model.newestCaptured = captured.getLast();
            }
            mappings.getAsJsonArray("captures").forEach(e -> {
                JsonObject capture = e.getAsJsonObject();
                if (capture.has("protocol") && capture.has("bdsVersion")) {
                    model.captureVersions.put(capture.get("protocol").getAsInt(), capture.get("bdsVersion").getAsString());
                }
                if (capture.has("windowProtocol") && capture.has("bdsVersion")) {
                    model.windowProtocols.put(capture.get("bdsVersion").getAsString(), capture.get("windowProtocol").getAsInt());
                }
                if (capture.has("windowProtocol") && capture.has("dataVersion")) {
                    model.dataVersions.put(capture.get("windowProtocol").getAsInt(), capture.get("dataVersion").getAsString());
                }
                if (capture.has("windowProtocol")) {
                    int window = capture.get("windowProtocol").getAsInt();
                    if (capture.has("preview")) {
                        model.previewProtocols.put(window, capture.get("preview").getAsString());
                    } else {
                        model.releaseProtocols.add(window);
                    }
                }
            });
            model.firstCustomBlockId = sameInEveryCapture(mappings, "firstCustomBlockId");
            model.blockItemIdPlusBlockId = sameInEveryCapture(mappings, "blockItemIdPlusBlockId");
        }
    }

    /**
     * @return the value of a key of every capture of {@code mappings.json}, or null if no capture has it
     * @throws GenerationException if captures differ: the hand code that uses it would need a protocol window
     */
    static Integer sameInEveryCapture(JsonObject mappings, String key) {
        Set<Integer> values = new TreeSet<>();
        mappings.getAsJsonArray("captures").forEach(e -> {
            if (e.getAsJsonObject().has(key)) {
                values.add(e.getAsJsonObject().get(key).getAsInt());
            }
        });
        if (values.size() > 1) {
            throw new GenerationException("the spec captures differ in " + key + " (" + values + "): the code using it needs a protocol window");
        }
        return values.isEmpty() ? null : values.iterator().next();
    }

    /** The capture directory of the newest BDS, e.g. data/bds/1.26.63.1/spec (where the BDS messages of the model come from), or null. */
    static String captureReference(JsonObject mappings) {
        if (mappings.has("captures") && !mappings.getAsJsonArray("captures").isEmpty()) {
            JsonObject last = mappings.getAsJsonArray("captures").asList().getLast().getAsJsonObject();
            return last.has("capture") ? last.get("capture").getAsString() : null;
        }
        return null;
    }

    /** The BDS the schema feedback is read from: the newest release capture (else the newest capture). */
    static String bdsVersion(JsonObject mappings) {
        if (mappings.has("captures") && !mappings.getAsJsonArray("captures").isEmpty()) {
            List<JsonElement> captures = mappings.getAsJsonArray("captures").asList();
            JsonObject capture = captures.stream().map(JsonElement::getAsJsonObject).filter(c -> !c.has("preview"))
                .reduce((a, b) -> b).orElse(captures.getLast().getAsJsonObject());
            return capture.get("bdsVersion").getAsString();
        }
        return "";
    }
}
