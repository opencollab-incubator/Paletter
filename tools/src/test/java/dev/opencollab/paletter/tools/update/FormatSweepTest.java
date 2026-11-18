package dev.opencollab.paletter.tools.update;

import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.model.Fixtures;
import dev.opencollab.paletter.tools.model.Model;
import dev.opencollab.paletter.tools.model.addon.AddonGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The plan of the format sweep: a component BDS rejects is asked at every known format, each beside a control of that format. */
class FormatSweepTest {
    private static String component(String name, String minFormat) {
        return "{\"id\": \"block:minecraft:" + name + "\", \"kind\": \"block\", \"category\": \"component\", \"name\": \"minecraft:" + name
            + "\", \"status\": \"active\", \"doc\": \"\", \"page\": \"\", " + (minFormat == null ? "" : "\"minFormatVersion\": \"" + minFormat + "\", ")
            + "\"experiments\": [], \"notes\": [], \"forms\": [{\"type\": \"string\"}], \"fields\": []}";
    }

    private static final String MODEL = "{\"source\": \"t\", \"commit\": \"t\", \"license\": \"t\", \"formatNotes\": [], \"components\": ["
        + component("breathability", null) + ", " + component("kept", "1.19.0") + ", " + component("custom_components", null) + "]}";
    private static final String FIXTURES = "{\"formatVersion\": \"1.26.60\", \"filler\": {}, \"errata\": [], \"samples\": {}, \"mapKeys\": {\"default\": \"k\"},"
        + " \"context\": {}, \"skip\": {}, \"requires\": {}, \"sweepValues\": {\"block:minecraft:breathability\": [\"solid\", \"air\"]}}";
    private static final String FEEDBACK = "{\"block:minecraft:breathability\": {\"\": [\"deprecated\"]}, \"block:minecraft:custom_components\": {\"\": [\"unknown\"]}}";

    @Test
    void aRejectedComponentIsAskedAtEveryFormatBesideAControl(@TempDir Path root) throws IOException {
        Path model = Files.createDirectories(root.resolve("model"));
        Files.writeString(model.resolve("components.json"), MODEL);
        Files.writeString(model.resolve("fixtures.json"), FIXTURES);
        Files.writeString(model.resolve("bds-feedback.json"), FEEDBACK);
        Fixtures fixtures = Fixtures.read(model);
        AddonGenerator generator = AddonGenerator.empty(Model.read(model.resolve("components.json")), fixtures, "1.26.60");
        KeyDiscovery.Evidence evidence = new KeyDiscovery.Evidence();
        evidence.bdsVersions.computeIfAbsent("minecraft:kept", k -> new TreeSet<>(AddonGenerator::compare)).addAll(Set.of("1.16.0", "1.20.50", "1.19.0"));

        FormatSweep.plan(generator, fixtures, evidence, "1.26.60");

        for (String format : List.of("1.16.0", "1.19.0", "1.20.50", "1.26.60")) {
            List<AddonGenerator.Variant> at = generator.variants().stream().filter(v -> v.componentId().equals("block:minecraft:breathability")
                && format.equals(v.format())).toList();
            assertEquals(List.of("format", "control", "format", "format"), at.stream().map(AddonGenerator.Variant::role).toList(), format);
            assertEquals(List.of("solid", "air"), at.stream().filter(v -> v.value() != null).map(v -> v.value().getAsString()).toList(),
                "the fixtures' values, whole, at " + format);
        }
        // a component BDS accepts keeps the sweep around its boundaries, without controls
        assertTrue(generator.variants().stream().noneMatch(v -> v.componentId().equals("block:minecraft:kept") && v.role().equals("control")));
        // custom_components: the base is the component's value under its name, not a map of components
        AddonGenerator.Variant custom = generator.variants().stream().filter(v -> v.componentId().equals("block:minecraft:custom_components")
            && v.role().equals("format")).findFirst().orElseThrow();
        JsonObject components = custom.definition().getAsJsonObject("minecraft:block").getAsJsonObject("components");
        assertEquals(custom.component(), components.get("minecraft:custom_components"));
    }
}
