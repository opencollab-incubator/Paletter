package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageTest {
    private static String component(String id) {
        return """
            {"id": "%s", "kind": "item", "category": "component", "name": "minecraft:x", "status": "active", "doc": "", "page": "",
             "experiments": [], "notes": [], "forms": [{"type": "object"}], "fields": []}""".formatted(id);
    }

    @Test
    void aComponentWithoutAResultIsSkippedPendingCaptureOrNotTested(@TempDir Path root) throws IOException {
        Path model = Files.createDirectories(root.resolve("model"));
        Files.writeString(model.resolve("components.json"), """
            {"source": "test", "commit": "test", "license": "test", "formatNotes": [], "components": [%s, %s, %s, %s]}"""
            .formatted(component("item:minecraft:skipped"), component("item:minecraft:scripted"), component("item:minecraft:plain"),
                component("item:minecraft:done")));
        Files.writeString(model.resolve("fixtures.json"), """
            {"formatVersion": "1.26.60", "filler": {}, "errata": [], "requires": {}, "samples": {}, "mapKeys": {"default": "k"}, "context": {},
             "skip": {"item:minecraft:skipped": "why"},
             "scriptComponents": {"module": "2.0.0", "block": {}, "item": {}, "variants": {"item:minecraft:scripted": [{"json": {}}]}}}""");
        Model docs = Model.read(model.resolve("components.json"));
        Coverage coverage = new Coverage(docs, Fixtures.read(model), Map.of());
        JsonObject done = new JsonObject();
        done.addProperty("status", "sent");
        coverage.add(docs.components().get(3), done);
        assertEquals(java.util.List.of("skipped", "pending capture", "not tested", "sent"),
            docs.components().stream().map(coverage::status).toList());
    }

    @Test
    void unreachedConstantsAreMembersOfTransforms() throws Exception {
        Class<?> transforms = Class.forName("org.geysermc.paletter.internal.component.Transforms");
        for (String name : Coverage.UNREACHED_CONSTANTS) {
            boolean exists = Arrays.stream(transforms.getDeclaredFields()).anyMatch(f -> f.getName().equals(name))
                || Arrays.stream(transforms.getDeclaredMethods()).anyMatch(m -> m.getName().equals(name));
            assertTrue(exists, "Coverage.UNREACHED_CONSTANTS names Transforms." + name + ", which does not exist");
        }
    }
}
