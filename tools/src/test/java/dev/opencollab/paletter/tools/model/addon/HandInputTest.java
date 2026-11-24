package dev.opencollab.paletter.tools.model.addon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.model.Fixtures;
import dev.opencollab.paletter.tools.model.MappingWindows;
import dev.opencollab.paletter.tools.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guards of the hand input: errata that are stale or misplaced, and entries that name no component. */
class HandInputTest {
    private static Model apply(Path root, String errata) throws IOException {
        Path model = Files.createDirectories(root.resolve("model"));
        Files.writeString(model.resolve("components.json"), EdgeCaseTest.MODEL);
        Files.writeString(model.resolve("fixtures.json"), EdgeCaseTest.FIXTURES.replace("\"errata\": []", "\"errata\": " + errata));
        return Fixtures.read(model).apply(Model.read(model.resolve("components.json")));
    }

    private static String types(Model model, String path) {
        return model.components().getFirst().field(path).types().stream().map(Object::toString).toList().toString();
    }

    @Test
    void anErratumThatAddsATypePutsItFirst(@TempDir Path root) throws IOException {
        Model model = apply(root, "[{\"component\": \"block:minecraft:box\", \"path\": \"mode\", \"types\": [\"integer\"], \"why\": \"x\"}]");
        assertTrue(types(model, "mode").startsWith("[integer"), types(model, "mode"));
        assertTrue(types(model, "mode").contains("string"), "the documented type stays: " + types(model, "mode"));
    }

    @Test
    void anErratumThatChangesNothingTheDocsSayIsStale(@TempDir Path root) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> apply(root, "[{\"component\": \"block:minecraft:box\", \"path\": \"mode\", \"types\": [\"string\"], \"why\": \"x\"}]"));
        assertTrue(e.getMessage().contains("stale"), e.getMessage());
    }

    @Test
    void anErratumWithAPathIsStaleWhenItsFormsAddNothing(@TempDir Path root) {
        // the path would add a field, but the form it also names is documented: the form half of the erratum is stale
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> apply(root,
            "[{\"component\": \"block:minecraft:box\", \"forms\": [\"object\"], \"path\": \"mode.extra\", \"types\": [\"string\"], \"why\": \"x\"}]"));
        assertTrue(e.getMessage().contains("adds no form the docs lack"), e.getMessage());
    }

    @Test
    void anErratumThatOnlyReordersTheDocumentedTypesIsStale(@TempDir Path root) {
        // shown is documented as map<string> and map<boolean>: naming them in the other order adds no type
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> apply(root,
            "[{\"component\": \"block:minecraft:box\", \"path\": \"shown\", \"types\": [\"map<boolean>\", \"map<string>\"], \"why\": \"x\"}]"));
        assertTrue(e.getMessage().contains("adds no type the docs lack"), e.getMessage());
    }

    @Test
    void anErratumWhoseParentIsNoFieldIsAMistypedPath(@TempDir Path root) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> apply(root, "[{\"component\": \"block:minecraft:box\", \"path\": \"nowhere.child\", \"types\": [\"string\"], \"why\": \"x\"}]"));
        assertTrue(e.getMessage().contains("parent"), e.getMessage());
    }

    @Test
    void anErratumMayAddAFieldBelowADocumentedOne(@TempDir Path root) throws IOException {
        Model model = apply(root, "[{\"component\": \"block:minecraft:box\", \"path\": \"mode.extra\", \"types\": [\"string\"], \"why\": \"x\"}]");
        assertEquals("[string]", types(model, "mode.extra"));
    }

    /** The schema of box takes angle as a string. */
    private static final String SCHEMA = """
        {"bdsVersion": "1.26.99.1", "formats": {"block": ["1.26.60"]}, "block": {"minecraft:box": [{"formats": ["1.26.60"], "root": {"types": ["object"]},
         "fields": {"angle": {"types": ["string"]}}}]}}""";

    private static List<String> redundant(Path root, String errata, boolean schema) throws IOException {
        Path model = Files.createDirectories(root.resolve("model"));
        Files.writeString(model.resolve("components.json"), EdgeCaseTest.MODEL);
        Files.writeString(model.resolve("fixtures.json"), EdgeCaseTest.FIXTURES.replace("\"errata\": []", "\"errata\": " + errata));
        if (schema) {
            Files.createDirectories(root.resolve("bds/1.26.99.1"));
            Files.writeString(root.resolve("bds/1.26.99.1/schema.json"), SCHEMA);
        }
        return Fixtures.read(model).redundantErrata(Model.read(model.resolve("components.json")));
    }

    @Test
    void anErratumThatSaysWhatTheSchemaSaysIsRedundant(@TempDir Path root) throws IOException {
        // angle: the schema gives string first already; mode: the schema says nothing of it
        assertEquals(List.of("block:minecraft:box angle"), redundant(root, """
            [{"component": "block:minecraft:box", "path": "angle", "types": ["string"], "why": "x"},
             {"component": "block:minecraft:box", "path": "mode", "types": ["integer"], "why": "x"}]""", true));
    }

    @Test
    void withoutASchemaNoErratumIsRedundant(@TempDir Path root) throws IOException {
        assertEquals(List.of(), redundant(root, "[{\"component\": \"block:minecraft:box\", \"path\": \"angle\", \"types\": [\"string\"], \"why\": \"x\"}]", false));
    }

    @Test
    void theCommittedErrataSayWhatTheSchemaDoesNot() throws IOException {
        Path model = Path.of("..", "data", "model").toAbsolutePath().normalize();
        Fixtures fixtures = Fixtures.read(model);
        assertEquals(List.of(), fixtures.redundantErrata(Model.read(model.resolve("components.json"))), "remove these errata: the schema says the same");
        // and each says why the schema does not do (a reason, for the next look)
        fixtures.errata().forEach(e -> assertTrue(e.has("schema") && !e.get("schema").getAsString().isBlank(), e.toString()));
    }

    @Test
    void theSchemaCorrectsTheTypesTheDocsHaveWrong() throws IOException {
        // Formerly errata: the docs' type column says boolean for tint_method, string for face_dimming and object for uv_lock
        Path model = Path.of("..", "data", "model").toAbsolutePath().normalize();
        Model merged = Fixtures.read(model).apply(Model.read(model.resolve("components.json")));
        Model.Component instances = merged.component("block:minecraft:material_instances");
        for (String face : List.of("*", "up", "down", "north", "south", "east", "west")) {
            assertTrue(instances.field(face + ".tint_method").types().getFirst().toString().equals("string")
                && instances.field(face + ".tint_method").enumValues().size() > 2, face + ".tint_method " + instances.field(face + ".tint_method"));
            assertEquals("boolean", instances.field(face + ".face_dimming").types().getFirst().toString(), face + ".face_dimming");
        }
        for (String visual : List.of("block:minecraft:item_visual", "block:minecraft:embedded_visual")) {
            assertTrue(types(merged.component(visual), "geometry.uv_lock").startsWith("[boolean, array<string>"), visual + " " + types(merged.component(visual), "geometry.uv_lock"));
        }
    }

    private static String types(Model.Component component, String path) {
        return component.field(path).types().stream().map(Object::toString).toList().toString();
    }

    @Test
    void entriesThatNameNoComponentAreOrphans() {
        JsonObject fixtures = JsonParser.parseString("""
            {"samples": {"block:minecraft:box:count": 1, "block:minecraft:gone:x": 2, "name": "bare field names are no component keys"},
             "probeOnly": ["item:minecraft:gone:y"], "skip": {"block:minecraft:box": "kept"}}""").getAsJsonObject();
        JsonObject api = JsonParser.parseString("""
            {"types": {"block:minecraft:box:count#name": "Count", "item:minecraft:renamed:field": "x"}, "exclude": ["block:minecraft:gone"]}""").getAsJsonObject();
        assertEquals(List.of("fixtures.json samples: block:minecraft:gone:x", "fixtures.json probeOnly: item:minecraft:gone:y",
            "api.json types: item:minecraft:renamed:field", "api.json exclude: block:minecraft:gone"),
            Fixtures.orphans(fixtures, api, Set.of("block:minecraft:box")));
    }

    @Test
    void theWindowsOfAComponentAreItsWindowList() {
        JsonObject component = JsonParser.parseString("""
            {"windows": [{"protocols": [1, 2], "status": "sent"}, {"protocols": [3, 4], "status": "sent"}, {"protocols": [5]}],
             "formats": {}}""").getAsJsonObject();
        assertEquals(3, MappingWindows.of(component).size());
        assertEquals(java.util.TreeSet.class, MappingWindows.protocols(component).getClass());
        assertEquals(List.of(1, 2, 3, 4, 5), List.copyOf(MappingWindows.protocols(component)));
        assertEquals(List.of(1, 2), MappingWindows.oldest(component).getAsJsonArray("protocols").asList().stream().map(e -> e.getAsInt()).toList());
        assertEquals(List.of(5), MappingWindows.newest(component).getAsJsonArray("protocols").asList().stream().map(e -> e.getAsInt()).toList());
        // a component without windows, or a window without protocols, is no mapping
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> MappingWindows.of(JsonParser.parseString("{\"status\": \"sent\"}").getAsJsonObject()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> MappingWindows.of(JsonParser.parseString("{\"windows\": [{\"status\": \"sent\"}]}").getAsJsonObject()));
    }
}
