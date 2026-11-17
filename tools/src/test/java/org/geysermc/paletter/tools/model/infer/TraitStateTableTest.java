package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.Model;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The trait state table: its naming rule on a synthetic trait, and the table the committed data gives. */
class TraitStateTableTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));

    private static final JsonObject HISTORY = JsonParser.parseString("""
        {"states": {"FACING": {"name": "test:facing", "kind": "enum", "enum": "Facing"},
                    "LINK_A": {"name": "test:link_a", "kind": "bool"}},
         "enums": {"Facing": {"values": {"SOUTH": {"value": "south"}, "NORTH": {"value": "north"}}}}}""").getAsJsonObject();

    private static Model.Component trait(List<String> enabled, String... notes) {
        Model.Field field = new Model.Field("enabled_states", List.of(), null, false,
            enabled.stream().map(v -> (com.google.gson.JsonElement) new JsonPrimitive(v)).toList(), null, null, List.of(), "");
        return new Model.Component("block:trait:test:turn", "block", "trait", "test:turn", "active", "", "", null, List.of(),
            List.of(), List.of(field), List.of(notes));
    }

    @Test
    void aValueAddsTheStateOfItsNameAndTheValueNamingNoneTheRest() {
        Map<String, List<TraitStateTable.State>> states = TraitStateTable.statesOf(trait(List.of("test:facing", "test:links"),
            "adds state test:facing: String north, south", "adds state test:link_a: Boolean true/false",
            "adds state test:part: Integer 0/1/2"), HISTORY);
        assertEquals(List.of("test:facing"), states.get("test:facing").stream().map(TraitStateTable.State::name).toList());
        assertEquals(List.of("test:link_a", "test:part"), states.get("test:links").stream().map(TraitStateTable.State::name).toList());
        // Vanilla order (BDS's palette), not the docs'
        assertEquals("south", states.get("test:facing").getFirst().values().getFirst().getAsString());
        assertEquals("the docs (no vanilla block has the state)", states.get("test:links").get(1).valuesFrom());
    }

    @Test
    void aValueNamingStatesByPartsAddsThem() {
        Map<String, List<TraitStateTable.State>> states = TraitStateTable.statesOf(trait(List.of("test:facing", "test:link_a_and_facing"),
            "adds state test:facing: String north, south", "adds state test:link_a: Boolean true/false"), HISTORY);
        assertEquals(List.of("test:link_a", "test:facing"), states.get("test:link_a_and_facing").stream().map(TraitStateTable.State::name).toList());
    }

    @Test
    void twoValuesNamingNoStateFail() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> TraitStateTable.statesOf(
            trait(List.of("test:a", "test:b"), "adds state test:facing: String north, south"), HISTORY));
        assertEquals("block:trait:test:turn: enabled_states test:a and test:b name no state, so which adds what is open", e.getMessage());
    }

    @Test
    void docsValuesOtherThanTheVanillaStateFail() {
        assertThrows(IllegalStateException.class, () -> TraitStateTable.statesOf(
            trait(List.of("test:facing"), "adds state test:facing: String north, east"), HISTORY));
    }

    @Test
    void theCommittedDataVerifiesTheCapturedDefaults() throws IOException {
        Path modelDir = ROOT.resolve("data/model");
        Model model = Fixtures.read(modelDir).apply(Model.read(modelDir.resolve("components.json")));
        JsonObject table = TraitStateTable.infer(ROOT, model);
        JsonObject cardinal = table.getAsJsonObject("minecraft:placement_direction").getAsJsonArray("minecraft:cardinal_direction")
            .get(0).getAsJsonObject();
        assertEquals("south", cardinal.getAsJsonArray("values").get(0).getAsString());
        assertTrue(cardinal.get("default").getAsString().startsWith("verified"), cardinal.toString());
        // corner_and_cardinal_direction adds both states it names: BDS's hash of the default state says so
        com.google.gson.JsonArray corner = table.getAsJsonObject("minecraft:placement_direction")
            .getAsJsonArray("minecraft:corner_and_cardinal_direction");
        assertEquals(List.of("minecraft:corner", "minecraft:cardinal_direction"),
            corner.asList().stream().map(e -> e.getAsJsonObject().get("name").getAsString()).toList());
        corner.forEach(e -> assertTrue(e.getAsJsonObject().get("default").getAsString().startsWith("verified"), e.toString()));
        JsonObject facing = table.getAsJsonObject("minecraft:placement_direction").getAsJsonArray("minecraft:facing_direction")
            .get(0).getAsJsonObject();
        assertTrue(facing.get("default").getAsString().startsWith("verified"), facing.toString());
        assertEquals(4, table.getAsJsonObject("minecraft:connection").getAsJsonArray("minecraft:cardinal_connections").size());
    }


    @Test
    void aValueNamingNoDocumentedStateIsSkippedAndReported() {
        List<String> skipped = new java.util.ArrayList<>();
        Map<String, List<TraitStateTable.State>> states = TraitStateTable.statesOf(trait(List.of("test:facing", "test:sixteen_way_rotation"),
            "adds state test:facing: String north, south"), HISTORY, skipped);
        assertEquals(List.of("test:facing"), List.copyOf(states.keySet()));
        assertEquals(List.of("block:trait:test:turn: enabled_states test:sixteen_way_rotation adds no documented state"), skipped);
    }

    /** With BDS's schema in the model, the enabled_states it lists beyond the docs (sixteen_way_rotation) do not stop the table. */
    @Test
    void withTheSchemaInTheModelTheTableSkipsWhatNoDocsName() throws IOException {
        Path modelDir = ROOT.resolve("data/model");
        Model model = Fixtures.read(modelDir, true).apply(Model.read(modelDir.resolve("components.json")));
        JsonObject table = TraitStateTable.infer(ROOT, model).getAsJsonObject("minecraft:placement_direction");
        assertTrue(table.has("minecraft:cardinal_direction"));
        assertFalse(table.has("minecraft:sixteen_way_rotation"));
    }
}
