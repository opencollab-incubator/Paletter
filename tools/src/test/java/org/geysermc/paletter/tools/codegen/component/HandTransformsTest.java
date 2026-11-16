package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every hand transform is backed by BDS: the inference found accepted variants of a captured build that set what it
 * compiles ({@code transforms.<path>.verified} in {@code mappings.json}), so the conformance suite compares its output
 * with what BDS sent. A transform no capture covers would only be checked by its own unit test.
 */
class HandTransformsTest {
    @Test
    void everyHandTransformIsCoveredByACapturedVariant() throws IOException {
        JsonObject components = JsonParser.parseString(Files.readString(ComponentConformanceTest.MODEL.resolve("mappings.json")))
            .getAsJsonObject().getAsJsonObject("components");
        List<String> uncovered = new ArrayList<>();
        for (Map.Entry<String, String> transform : HandTransforms.BY_PATH.entrySet()) {
            // "<component>:<path>": the component is the longest name in mappings.json the key starts with
            String key = transform.getKey();
            String component = components.keySet().stream().filter(name -> key.startsWith(name + ":"))
                .max((a, b) -> Integer.compare(a.length(), b.length())).orElse(null);
            JsonObject transforms = component == null ? null : components.getAsJsonObject(component).getAsJsonObject("transforms");
            String path = component == null ? null : key.substring(component.length() + 1);
            JsonObject entry = transforms == null || !transforms.has(path) ? null : transforms.getAsJsonObject(path);
            if (entry == null || !entry.has("verified") || entry.getAsJsonObject("verified").get("variants").getAsInt() == 0) {
                uncovered.add(key + " (" + transform.getValue() + ")");
            }
        }
        assertEquals(List.of(), uncovered, "hand transforms without an accepted captured variant (see coverage.md)");
    }
}
