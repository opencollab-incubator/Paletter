package dev.opencollab.paletter.tools.model.infer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.opencollab.paletter.tools.model.Fixtures;
import dev.opencollab.paletter.tools.model.Model;
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

    /** The builds of the committed spec captures to their protocol windows, as the inference passes them. */
    private static Map<String, Integer> windows() throws IOException {
        Map<String, Integer> windows = new java.util.LinkedHashMap<>();
        JsonParser.parseString(java.nio.file.Files.readString(ROOT.resolve("data/model/mappings.json"))).getAsJsonObject()
            .getAsJsonArray("captures").forEach(e -> windows.put(e.getAsJsonObject().get("bdsVersion").getAsString(),
                e.getAsJsonObject().get("windowProtocol").getAsInt()));
        return windows;
    }

    private static JsonObject committedTable() throws IOException {
        Path modelDir = ROOT.resolve("data/model");
        Model model = Fixtures.read(modelDir).apply(Model.read(modelDir.resolve("components.json")));
        return TraitStateTable.infer(ROOT, model, windows());
    }

    /** The windows of a value: the only one when every build adds the same. */
    private static com.google.gson.JsonArray windowsOf(JsonObject table, String trait, String value) {
        return table.getAsJsonObject(trait).getAsJsonObject(value).getAsJsonArray("windows");
    }

    private static com.google.gson.JsonArray onlyWindowStates(JsonObject table, String trait, String value) {
        com.google.gson.JsonArray windows = windowsOf(table, trait, value);
        assertEquals(1, windows.size(), windows.toString());
        return windows.get(0).getAsJsonObject().getAsJsonArray("states");
    }

    @Test
    void theCommittedDataVerifiesTheCapturedDefaults() throws IOException {
        JsonObject table = committedTable();
        JsonObject cardinal = onlyWindowStates(table, "minecraft:placement_direction", "minecraft:cardinal_direction").get(0).getAsJsonObject();
        assertEquals("south", cardinal.getAsJsonArray("values").get(0).getAsString());
        assertTrue(cardinal.get("default").getAsString().startsWith("verified"), cardinal.toString());
        // corner_and_cardinal_direction adds both states it names: BDS's hash of the default state says so
        com.google.gson.JsonArray corner = onlyWindowStates(table, "minecraft:placement_direction", "minecraft:corner_and_cardinal_direction");
        assertEquals(List.of("minecraft:corner", "minecraft:cardinal_direction"),
            corner.asList().stream().map(e -> e.getAsJsonObject().get("name").getAsString()).toList());
        corner.forEach(e -> assertTrue(e.getAsJsonObject().get("default").getAsString().startsWith("verified"), e.toString()));
        JsonObject facing = onlyWindowStates(table, "minecraft:placement_direction", "minecraft:facing_direction").get(0).getAsJsonObject();
        assertTrue(facing.get("default").getAsString().startsWith("verified"), facing.toString());
        assertEquals(4, onlyWindowStates(table, "minecraft:connection", "minecraft:cardinal_connections").size());
    }

    /** A state with BDS's values takes each build's own dump: duplicate values or none make the value unsupported in the build. */
    @Test
    void aBuildsOwnDumpDecidesItsValuesOrMakesTheValueUnsupported() {
        TraitStateTable.State bds = new TraitStateTable.BdsStates("9.9.9", Map.of("test:turn", List.of(0, 1, 2))).state("test:turn");
        TraitStateTable.Version own = TraitStateTable.versionOf(List.of(bds),
            new TraitStateTable.BdsStates("1.0.0", Map.of("test:turn", List.of("a", "b"))));
        assertEquals(null, own.unsupported());
        assertEquals("string", own.states().getFirst().type());
        assertEquals(List.of("a", "b"), own.states().getFirst().values().stream().map(JsonPrimitive::getAsString).toList());
        assertTrue(own.states().getFirst().valuesFrom().startsWith("BDS 1.0.0"), own.states().getFirst().valuesFrom());

        TraitStateTable.Version duplicates = TraitStateTable.versionOf(List.of(bds),
            new TraitStateTable.BdsStates("1.0.0", Map.of("test:turn", List.of("down", "up", "down"))));
        assertEquals("test:turn", duplicates.unsupported());
        assertEquals(List.of("down", "up", "down"), duplicates.dumpValues());
        assertEquals(null, duplicates.states());

        TraitStateTable.Version none = TraitStateTable.versionOf(List.of(bds), TraitStateTable.BdsStates.NONE);
        assertEquals("test:turn", none.unsupported());
        assertEquals(List.of(), none.dumpValues());
        // A state not from BDS is the same in every build
        TraitStateTable.State vanilla = TraitStateTable.statesOf(trait(List.of("test:facing"), "adds state test:facing: String north, south"),
            HISTORY).get("test:facing").getFirst();
        assertEquals(List.of(vanilla), TraitStateTable.versionOf(List.of(vanilla), TraitStateTable.BdsStates.NONE).states());
        assertEquals("BDS 1.0.0 lists test:turn as [down, up, down] (its Script API dump, BlockStates.getAll()): duplicate values, a BDS bug, "
            + "so the states the client adds for it are not well defined", TraitStateTable.reason("test:turn", Map.of("1.0.0", List.of("down", "up", "down"))));
    }

    /**
     * BDS 1.26.36.1 lists minecraft:sixteen_way_rotation as {@code [down, up, north, south, west, east, down, ...]}: in its window
     * (26.30) the value is unsupported, with its dump values and the hashes it sent as evidence; from 1.26.45.1 on it adds 0 to 15.
     */
    @Test
    void sixteenWayRotationIsUnsupportedIn26_30AndAddsZeroToFifteenFrom26_40() throws IOException {
        com.google.gson.JsonArray windows = windowsOf(committedTable(), "minecraft:placement_direction", "minecraft:sixteen_way_rotation");
        assertEquals(2, windows.size(), windows.toString());
        JsonObject old = windows.get(0).getAsJsonObject();
        assertEquals(List.of(1001), old.getAsJsonArray("protocols").asList().stream().map(e -> e.getAsInt()).toList());
        JsonObject unsupported = old.getAsJsonObject("unsupported");
        assertEquals("minecraft:sixteen_way_rotation", unsupported.get("state").getAsString());
        assertTrue(unsupported.get("reason").getAsString().startsWith("BDS 1.26.36.1 lists minecraft:sixteen_way_rotation as [down, up, north, "
            + "south, west, east, down, down"), unsupported.toString());
        List<String> evidence = unsupported.getAsJsonArray("evidence").asList().stream().map(e -> e.getAsString()).toList();
        assertTrue(evidence.getFirst().startsWith("BDS 1.26.36.1's Script API dump"), evidence.toString());
        // one hash per placement_direction variant with sixteen_way_rotation, each with "down"
        assertEquals(8, evidence.stream().filter(e -> e.contains("sent hash") && e.contains("= down")).count(), evidence.toString());
        JsonObject newer = windows.get(1).getAsJsonObject();
        assertEquals(1001, old.getAsJsonArray("protocols").get(0).getAsInt());
        assertFalse(newer.getAsJsonArray("protocols").asList().contains(new JsonPrimitive(1001)));
        JsonObject state = newer.getAsJsonArray("states").get(0).getAsJsonObject();
        assertEquals("int", state.get("type").getAsString());
        assertEquals(java.util.stream.IntStream.range(0, 16).boxed().toList(),
            state.getAsJsonArray("values").asList().stream().map(e -> e.getAsInt()).toList());
        assertTrue(state.get("default").getAsString().startsWith("verified"), state.toString());
    }


    @Test
    void aValueNamingNoDocumentedStateIsSkippedAndReported() {
        List<String> skipped = new java.util.ArrayList<>();
        Map<String, List<TraitStateTable.State>> states = TraitStateTable.statesOf(trait(List.of("test:facing", "test:sixteen_way_rotation"),
            "adds state test:facing: String north, south"), HISTORY, TraitStateTable.BdsStates.NONE, skipped);
        assertEquals(List.of("test:facing"), List.copyOf(states.keySet()));
        assertEquals(List.of("block:trait:test:turn: enabled_states test:sixteen_way_rotation adds no documented state"), skipped);
    }

    /** A value the docs name no state for adds the state of its own name where BDS's block states have one, with BDS's values. */
    @Test
    void aValueNamingNoDocumentedStateTakesBdsState() {
        List<String> skipped = new java.util.ArrayList<>();
        TraitStateTable.BdsStates bds = new TraitStateTable.BdsStates("9.9.9", Map.of("test:sixteen_way_rotation", List.of(0, 1, 2)));
        Map<String, List<TraitStateTable.State>> states = TraitStateTable.statesOf(trait(List.of("test:facing", "test:sixteen_way_rotation"),
            "adds state test:facing: String north, south"), HISTORY, bds, skipped);
        TraitStateTable.State state = states.get("test:sixteen_way_rotation").getFirst();
        assertEquals("test:sixteen_way_rotation", state.name());
        assertEquals("int", state.type());
        assertEquals(List.of(0, 1, 2), state.values().stream().map(JsonPrimitive::getAsInt).toList());
        assertTrue(state.valuesFrom().startsWith("BDS 9.9.9"), state.valuesFrom());
        assertEquals(List.of(), skipped);
    }

    /**
     * With BDS's schema in the model, the enabled_states it lists beyond the docs (sixteen_way_rotation) do not stop the table:
     * they add BDS's state of their name where a committed dump keeps BDS's block states, else they are skipped.
     */
    @Test
    void withTheSchemaInTheModelTheTableTakesBdsStatesForWhatNoDocsName() throws IOException {
        JsonObject all = committedTable();
        JsonObject table = all.getAsJsonObject("minecraft:placement_direction");
        assertTrue(table.has("minecraft:cardinal_direction"));
        TraitStateTable.State bds = TraitStateTable.BdsStates.newest(ROOT).state("minecraft:sixteen_way_rotation");
        if (bds == null) {
            assertFalse(table.has("minecraft:sixteen_way_rotation"));
        } else if (table.has("minecraft:sixteen_way_rotation")) {
            // The newest window has the newest dump's values
            com.google.gson.JsonArray windows = windowsOf(all, "minecraft:placement_direction", "minecraft:sixteen_way_rotation");
            JsonObject state = windows.get(windows.size() - 1).getAsJsonObject().getAsJsonArray("states").get(0).getAsJsonObject();
            assertEquals("minecraft:sixteen_way_rotation", state.get("name").getAsString());
            assertEquals(bds.values(), state.getAsJsonArray("values").asList());
        }
    }
}
