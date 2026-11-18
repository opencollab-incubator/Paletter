package org.geysermc.paletter.tools.update;

import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.Model;
import org.geysermc.paletter.tools.model.Schema;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static final String OLD_ENUM_MODEL = "{\"source\": \"t\", \"commit\": \"t\", \"license\": \"t\", \"formatNotes\": [], \"components\": ["
        + "{\"id\": \"item:minecraft:box\", \"kind\": \"item\", \"category\": \"component\", \"name\": \"minecraft:box\", \"status\": \"active\","
        + " \"doc\": \"\", \"page\": \"\", \"experiments\": [], \"notes\": [], \"forms\": [{\"type\": \"object\"}], \"fields\": ["
        + "{\"path\": \"sound\", \"types\": [{\"type\": \"string\"}], \"required\": false, \"enumValues\": [], \"examples\": [], \"doc\": \"\"},"
        + "{\"path\": \"kept\", \"types\": [{\"type\": \"string\"}], \"required\": false, \"enumValues\": [], \"examples\": [], \"doc\": \"\"}]}]}";
    /** Format 1.26.30 lists enums for {@code sound} and {@code kept}; 1.26.50 only for {@code kept} and has no definition of {@code gone}'s field. */
    private static final String OLD_ENUM_SCHEMA = "{\"bdsVersion\": \"1.26.99.1\", \"formats\": {\"item\": [\"1.26.30\", \"1.26.50\"]}, \"item\": {\"minecraft:box\": ["
        + "{\"formats\": [\"1.26.30\"], \"root\": {\"types\": [\"object\"]}, \"fields\": {\"sound\": {\"types\": [\"string\"], \"enum\": [\"a\", \"b\"]},"
        + " \"kept\": {\"types\": [\"string\"], \"enum\": [\"x\"]}, \"gone\": {\"types\": [\"string\"], \"enum\": [\"y\"]}}},"
        + "{\"formats\": [\"1.26.50\"], \"root\": {\"types\": [\"object\"]}, \"fields\": {\"sound\": {\"types\": [\"string\"]},"
        + " \"kept\": {\"types\": [\"string\"], \"enum\": [\"x\"]}}}]}}";

    @Test
    void everyValueOfAnEnumOnlyAnOlderFormatListsIsAskedAtThatFormat(@TempDir Path root) throws IOException {
        Path model = Files.createDirectories(root.resolve("model"));
        Files.writeString(model.resolve("components.json"), OLD_ENUM_MODEL);
        Files.writeString(model.resolve("fixtures.json"), FIXTURES);
        Files.createDirectories(root.resolve("bds/1.26.99.1"));
        Files.writeString(root.resolve("bds/1.26.99.1/schema.json"), OLD_ENUM_SCHEMA);
        Fixtures fixtures = Fixtures.read(model, true);
        AddonGenerator generator = AddonGenerator.empty(Model.read(model.resolve("components.json")), fixtures, "1.26.50");

        assertEquals(2, FormatSweep.planOlderEnums(generator, fixtures));

        // not kept (the newest format has the enum too) and not gone (the model has no such field)
        assertEquals(List.of("a", "b"), generator.variants().stream().map(v -> v.value().getAsString()).toList());
        assertTrue(generator.variants().stream().allMatch(v -> v.role().equals("enum") && v.path().equals("sound") && "1.26.30".equals(v.format())));
        assertEquals("a", generator.variants().getFirst().definition().getAsJsonObject("minecraft:item").getAsJsonObject("components")
            .getAsJsonObject("minecraft:box").get("sound").getAsString());
    }

    @Test
    void theCommittedSchemaHasEnumsOnlyAnOlderFormatLists() {
        Path data = Path.of(System.getProperty("paletter.root", "..")).resolve("data/bds");
        List<Schema.OlderEnum> found = Schema.newest(data, null).olderEnums();
        System.out.println("older-format enums: " + found.size() + " fields, " + found.stream().mapToInt(e -> e.values().size()).sum() + " values");
        assertFalse(found.isEmpty());
        for (Schema.OlderEnum e : found) {
            assertFalse(e.values().isEmpty(), e.toString());
            assertEquals("", Schema.newest(data, null).enumValues(e.component(), e.path()).stream().findAny().orElse(""),
                e + ": the newest format lists no enum there");
        }
        assertTrue(found.stream().anyMatch(e -> e.component().equals("item:minecraft:swing_sounds") && e.path().equals("attack_hit")));
        assertTrue(found.stream().anyMatch(e -> e.component().equals("item:minecraft:use_modifiers") && e.path().equals("start_sound")));
    }
}
