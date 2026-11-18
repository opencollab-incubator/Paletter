package org.geysermc.paletter.tools.behaviour;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules from the per-state observations of the probe to values, on observations made up to be what the add-on printed. */
class StateProbeTest {
    private static String observation(String tag, String json) {
        return "[2026-11-09 20:30:30:000 WARN] [Scripting] PROBE " + tag + " " + json;
    }

    /** A complete console log with these observations. */
    private static ProbeLog log(List<String> observations) {
        List<String> lines = new ArrayList<>();
        lines.add("[2026-11-09 20:30:06:870 INFO] Version: 1.26.63.1");
        lines.add("[2026-11-09 20:30:06:871 INFO] Build ID: 53340801");
        lines.addAll(observations);
        lines.add("[2026-11-09 20:30:40:000 WARN] [Scripting] PROBE-DONE");
        return ProbeLog.parse(lines);
    }

    private static JsonObject capture(List<String> observations) {
        JsonObject capture = new JsonObject();
        StateProbe.addTo(capture, log(observations));
        return capture;
    }

    // ---- light ----

    private static String single(String tag, String block, String base) {
        return observation(tag, "{\"block\":\"" + block + "\",\"states\":1,\"base\":" + base + ",\"differ\":[],\"changed\":[]}");
    }

    /** The controls of the light section, reading the way the rules expect (an opaque block's own cell reads 0). */
    private static List<String> lightControls(String emissionOneReading) {
        List<String> lines = new ArrayList<>();
        lines.add(single("light_state", "probe:plain", "[0,0,0]"));
        lines.add(single("light_state", "probe:light_emission_1", emissionOneReading));
        lines.add(single("light_state", "probe:light_emission_7", "[0,6,0]"));
        lines.add(single("light_state", "probe:light_emission_15", "[0,14,0]"));
        int[][] dampening = {{0, 15}, {1, 14}, {2, 13}, {7, 8}, {14, 1}, {15, 0}};
        for (int[] control : dampening) {
            lines.add(single("light_state", "probe:light_dampening_" + control[0], "[0,0," + control[1] + "]"));
        }
        return lines;
    }

    private static JsonObject light(List<String> blocks) {
        List<String> lines = lightControls("[0,0,0]");
        lines.addAll(blocks);
        return capture(lines).getAsJsonObject(StateProbe.LIGHT);
    }

    @Test
    void anOldLogHasNoStateSections() {
        assertTrue(capture(List.of()).keySet().isEmpty());
    }

    @Test
    void emissionIsReadNextToTheStateAndFromItsOwnCellWhereThatSaysMore() {
        // a torch (14, its own cell reads 14), an opaque emitter (15: own cell 0, next to it 14), glowing 1 (own cell 1)
        JsonObject light = light(List.of(
            observation("light_state", "{\"block\":\"minecraft:torch\",\"states\":2,\"base\":[14,13,15],\"differ\":[[{\"torch_facing_direction\":\"top\"},[14,13,14]]],"
                + "\"changed\":[[{\"torch_facing_direction\":\"unknown\"},\"minecraft:torch\",null]]}"),
            observation("light_state", "{\"block\":\"minecraft:magma\",\"states\":1,\"base\":[0,2,0],\"differ\":[],\"changed\":[]}"),
            observation("light_state", "{\"block\":\"minecraft:egg\",\"states\":1,\"base\":[1,0,15],\"differ\":[],\"changed\":[]}"),
            observation("light_state", "{\"block\":\"minecraft:odd\",\"states\":1,\"base\":[5,0,15],\"differ\":[],\"changed\":[]}")));
        JsonObject blocks = light.getAsJsonObject("blocks");
        assertEquals(14, blocks.getAsJsonObject("minecraft:torch").getAsJsonObject("emission").get("base").getAsInt());
        assertEquals(3, blocks.getAsJsonObject("minecraft:magma").getAsJsonObject("emission").get("base").getAsInt());
        assertEquals(1, blocks.getAsJsonObject("minecraft:egg").getAsJsonObject("emission").get("base").getAsInt());
        assertTrue(blocks.getAsJsonObject("minecraft:odd").getAsJsonObject("emission").get("base").isJsonNull(),
            "its own cell says 5 and the cell next to it nothing: that is no reading of an emission");
        assertEquals(0, blocks.getAsJsonObject("probe:plain").getAsJsonObject("emission").get("base").getAsInt());
    }

    @Test
    void dampeningIsReadFromTheSkyLightBelowTheState() {
        JsonObject light = light(List.of(
            observation("light_state", "{\"block\":\"minecraft:glass\",\"states\":1,\"base\":[0,0,15],\"differ\":[],\"changed\":[]}"),
            observation("light_state", "{\"block\":\"minecraft:leaves\",\"states\":3,\"base\":[0,0,14],\"differ\":[[{\"persistent_bit\":true},[0,0,13]]],\"changed\":[]}")));
        JsonObject blocks = light.getAsJsonObject("blocks");
        assertEquals(0, blocks.getAsJsonObject("minecraft:glass").getAsJsonObject("dampening").get("base").getAsInt(), "sky light 15 below: it dampens none");
        JsonObject leaves = blocks.getAsJsonObject("minecraft:leaves").getAsJsonObject("dampening");
        assertEquals(1, leaves.get("base").getAsInt());
        assertEquals(2, leaves.getAsJsonArray("differ").get(0).getAsJsonObject().get("value").getAsInt());
        assertEquals("{\"persistent_bit\":true}", leaves.getAsJsonArray("differ").get(0).getAsJsonObject().get("state").toString());
        assertEquals(15, blocks.getAsJsonObject("probe:plain").getAsJsonObject("dampening").get("base").getAsInt(), "plain: no light gets below it (the existing default)");
    }

    @Test
    void aStateTheProbeCouldNotPlaceAsAskedIsListedWithWhatWasThere() {
        JsonObject light = light(List.of(observation("light_state", "{\"block\":\"minecraft:stairs\",\"states\":2,\"base\":[0,0,0],\"differ\":[],"
            + "\"changed\":[[{\"corner\":\"a\"},\"minecraft:stairs\",{\"corner\":\"b\"}],[{\"corner\":\"c\"},\"minecraft:air\",null]]}")));
        JsonArray changed = light.getAsJsonObject("blocks").getAsJsonObject("minecraft:stairs").getAsJsonArray("changed");
        assertEquals(2, changed.size());
        assertEquals("{\"corner\":\"a\"}", changed.get(0).getAsJsonObject().get("requested").toString());
        assertEquals("{\"corner\":\"b\"}", changed.get(0).getAsJsonObject().get("as").toString());
        assertEquals("minecraft:air", changed.get(1).getAsJsonObject().get("became").getAsString());
        assertTrue(changed.get(1).getAsJsonObject().get("as").isJsonNull());
    }

    @Test
    void emissionOfOneIsOnlyToldFromNoneWhereTheControlReadsDifferently() {
        assertTrue(light(List.of()).getAsJsonObject("controls").get("emission_1_reads_as_none").getAsBoolean());
        List<String> lines = lightControls("[1,0,0]");
        JsonObject distinct = capture(lines).getAsJsonObject(StateProbe.LIGHT);
        assertFalse(distinct.getAsJsonObject("controls").get("emission_1_reads_as_none").getAsBoolean());
        // a control that reads neither as 1 nor as none is no measurement
        List<String> wrong = lightControls("[0,3,0]");
        assertTrue(capture(wrong).getAsJsonObject(StateProbe.LIGHT).has(StateProbe.UNVERIFIED));
    }

    @Test
    void aControlThatDoesNotReadAsSetMakesTheSectionUnverified() {
        List<String> lines = lightControls("[0,0,0]");
        lines.removeIf(line -> line.contains("probe:light_dampening_7"));
        lines.add(single("light_state", "probe:light_dampening_7", "[0,0,3]"));
        JsonObject light = capture(lines).getAsJsonObject(StateProbe.LIGHT);
        assertEquals(1, light.size(), "nothing but the reason");
        assertTrue(light.get(StateProbe.UNVERIFIED).getAsString().contains("probe:light_dampening_7"), light.toString());
    }

    @Test
    void aMissingControlMakesTheSectionUnverified() {
        List<String> lines = lightControls("[0,0,0]");
        lines.removeIf(line -> line.contains("probe:light_emission_15"));
        assertTrue(capture(lines).getAsJsonObject(StateProbe.LIGHT).get(StateProbe.UNVERIFIED).getAsString().contains("probe:light_emission_15"));
    }

    @Test
    void aSectionTheAddOnCouldNotRunIsUnverifiedWithItsError() {
        List<String> lines = lightControls("[0,0,0]");
        lines.add(observation("failed", "{\"section\":\"light\",\"error\":\"Error: no cell\\n    at lightStates (main.js:1)\"}"));
        JsonObject capture = capture(lines);
        assertEquals("the probe could not run it: Error: no cell", capture.getAsJsonObject(StateProbe.LIGHT).get(StateProbe.UNVERIFIED).getAsString());
        // the others are not affected
        assertFalse(capture.has(StateProbe.FRICTION));
    }

    // ---- friction ----

    private static String slide(double engineFriction, double dy) {
        StringBuilder velocities = new StringBuilder("[");
        double velocity = 0.5;
        for (int tick = 0; tick < 10; tick++) {
            velocities.append(tick == 0 ? "" : ",").append(velocity);
            velocity *= engineFriction * BehaviourProbe.MOB_DRAG;
        }
        return "{\"v\":" + velocities + "],\"dy\":" + dy + "}";
    }

    private static List<String> frictionControls() {
        List<String> lines = new ArrayList<>();
        for (int component : new int[]{0, 10, 40, 90}) {
            lines.add(observation("friction_state", "{\"block\":\"probe:friction_" + component + "\",\"states\":1,\"base\":" + slide(1 - component / 100.0, 0)
                + ",\"differ\":[],\"changed\":[]}"));
        }
        return lines;
    }

    @Test
    void frictionIsTheShareOfSpeedKeptPerTickDividedByTheDrag() {
        List<String> lines = frictionControls();
        lines.add(observation("friction_state", "{\"block\":\"minecraft:stone\",\"states\":3,\"base\":" + slide(0.6, 0) + ",\"differ\":[[{\"a\":1}," + slide(0.98, 0)
            + "],[{\"a\":2}," + slide(0.6, 0.3) + "]],\"changed\":[]}"));
        JsonObject friction = capture(lines).getAsJsonObject(StateProbe.FRICTION);
        JsonObject stone = friction.getAsJsonObject("blocks").getAsJsonObject("minecraft:stone");
        assertEquals(3, stone.get("states").getAsInt());
        JsonObject entry = stone.getAsJsonObject("friction");
        assertEquals(0.6, entry.get("base").getAsDouble());
        assertEquals(1, entry.getAsJsonArray("differ").size());
        assertEquals(0.98, entry.getAsJsonArray("differ").get(0).getAsJsonObject().get("value").getAsDouble());
        assertEquals("[{\"a\":2}]", entry.getAsJsonArray("unclear").toString(), "a body that rose 0.3 was not standing on the floor");
        assertEquals(0.9, friction.getAsJsonObject("controls").get("probe:friction_10").getAsDouble());
    }

    @Test
    void aFrictionControlThatReadsOtherwiseMakesTheSectionUnverified() {
        List<String> lines = frictionControls();
        lines.removeIf(line -> line.contains("probe:friction_90"));
        lines.add(observation("friction_state", "{\"block\":\"probe:friction_90\",\"states\":1,\"base\":" + slide(0.5, 0) + ",\"differ\":[],\"changed\":[]}"));
        assertTrue(capture(lines).getAsJsonObject(StateProbe.FRICTION).get(StateProbe.UNVERIFIED).getAsString().contains("probe:friction_90"));
    }

    @Test
    void tooFewControlsMakeTheSectionUnverified() {
        List<String> lines = frictionControls().subList(0, 2);
        assertTrue(capture(lines).getAsJsonObject(StateProbe.FRICTION).has(StateProbe.UNVERIFIED));
    }

    @Test
    void aSlideIsOnlyAFrictionIfItsSharesAgreeAndItIsLongEnough() {
        assertEquals(0.6, StateProbe.slideFriction(velocities(0.6, 10), 0.005).getAsDouble());
        assertEquals(OptionalDouble.empty(), StateProbe.slideFriction(velocities(0.6, 3), 0.005), "2 shares: not enough");
        List<Double> bent = new ArrayList<>(velocities(0.6, 10));
        bent.set(6, bent.get(6) * 0.5);
        assertEquals(OptionalDouble.empty(), StateProbe.slideFriction(bent, 0.005), "a tick of another drag");
        assertTrue(StateProbe.slideFriction(bent, Double.MAX_VALUE).isPresent(), "the old rule has no limit");
        assertEquals(OptionalDouble.empty(), StateProbe.slideFriction(List.of(0.0, 0.0, 0.0, 0.0, 0.0), 1), "at rest");
    }

    private static List<Double> velocities(double friction, int ticks) {
        List<Double> velocities = new ArrayList<>();
        double velocity = 0.5;
        for (int i = 0; i < ticks; i++) {
            velocities.add(velocity);
            velocity *= friction * BehaviourProbe.MOB_DRAG;
        }
        return velocities;
    }

    // ---- around ----

    private static String around(String block, String state, String attempts) {
        return observation("around", "{\"block\":\"" + block + "\",\"state\":" + state + ",\"attempts\":[" + attempts + "]}");
    }

    private static String attempt(String recipe, boolean kept, int light, String... materials) {
        return "{\"recipe\":\"" + recipe + "\",\"materials\":[\"" + String.join("\",\"", materials) + "\"],\"kept\":" + kept + ",\"light\":" + light + "}";
    }

    /** The light section with the blocks of the recipes: stone and soul soil emit none, magma block does. */
    private static List<String> recipeLight() {
        List<String> lines = lightControls("[0,0,0]");
        lines.add(single("light_state", "minecraft:stone", "[0,0,0]"));
        lines.add(single("light_state", "minecraft:soul_soil", "[0,0,0]"));
        lines.add(single("light_state", "minecraft:magma_block", "[0,2,0]"));
        return lines;
    }

    @Test
    void aStateBdsKeptInARecipeThatEmitsNothingHasTheLightOfItsCell() {
        List<String> lines = recipeLight();
        lines.add(around("minecraft:soul_fire", "{\"age\":3}", attempt("on soul soil", true, 10, "minecraft:stone", "minecraft:soul_soil")));
        lines.add(around("minecraft:soul_fire", "{\"age\":4}", attempt("on soul soil", false, 0, "minecraft:stone", "minecraft:soul_soil")
            + "," + attempt("on soul sand", false, 0, "minecraft:stone", "minecraft:soul_sand")));
        lines.add(around("minecraft:bubble_column", "{\"drag_down\":true}", attempt("on magma", true, 2, "minecraft:stone", "minecraft:magma_block")));
        JsonObject blocks = capture(lines).getAsJsonObject(StateProbe.AROUND).getAsJsonObject("blocks");
        JsonObject fire = blocks.getAsJsonObject("minecraft:soul_fire");
        assertEquals(1, fire.getAsJsonArray("measured").size());
        assertEquals(10, fire.getAsJsonArray("measured").get(0).getAsJsonObject().get("emission").getAsInt());
        assertEquals("on soul soil", fire.getAsJsonArray("measured").get(0).getAsJsonObject().get("recipe").getAsString());
        assertEquals("BDS kept the state in none of the recipes", fire.getAsJsonArray("unclear").get(0).getAsJsonObject().get("reason").getAsString());
        JsonObject bubbles = blocks.getAsJsonObject("minecraft:bubble_column");
        assertTrue(bubbles.getAsJsonArray("measured").isEmpty());
        assertTrue(bubbles.getAsJsonArray("unclear").get(0).getAsJsonObject().get("reason").getAsString().contains("minecraft:magma_block"),
            "the magma block lights the cell: the reading may be its light");
    }

    @Test
    void withoutTheLightSectionNoRecipeIsClean() {
        List<String> lines = new ArrayList<>();
        lines.add(around("minecraft:soul_fire", "{\"age\":3}", attempt("on soul soil", true, 10, "minecraft:stone", "minecraft:soul_soil")));
        JsonObject fire = capture(lines).getAsJsonObject(StateProbe.AROUND).getAsJsonObject("blocks").getAsJsonObject("minecraft:soul_fire");
        assertTrue(fire.getAsJsonArray("measured").isEmpty());
        assertTrue(fire.getAsJsonArray("unclear").get(0).getAsJsonObject().get("reason").getAsString().contains("minecraft:stone"));
    }

    // ---- the controls are in the pack ----

    @Test
    void theControlsAreBlocksOfTheProbePackWithTheValuesTheRulesCheck() throws Exception {
        java.nio.file.Path blocks = java.nio.file.Path.of(System.getProperty("paletter.root", "..")).resolve("data/addons/probe/blocks");
        for (var control : StateProbe.EMISSION_CONTROLS.entrySet()) {
            assertEquals(control.getValue(), component(blocks, control.getKey(), "minecraft:light_emission"));
        }
        assertEquals(1, component(blocks, StateProbe.EMISSION_ONE, "minecraft:light_emission"));
        for (var control : StateProbe.DAMPENING_CONTROLS.entrySet()) {
            assertEquals(control.getValue(), component(blocks, control.getKey(), "minecraft:light_dampening"));
        }
        JsonObject plain = JsonParser.parseString(java.nio.file.Files.readString(blocks.resolve("plain.json"))).getAsJsonObject();
        assertTrue(plain.getAsJsonObject("minecraft:block").getAsJsonObject("components").isEmpty());
    }

    private static int component(java.nio.file.Path blocks, String id, String component) throws java.io.IOException {
        JsonObject block = JsonParser.parseString(java.nio.file.Files.readString(blocks.resolve(id.substring("probe:".length()) + ".json"))).getAsJsonObject();
        JsonObject description = block.getAsJsonObject("minecraft:block");
        assertEquals(id, description.getAsJsonObject("description").get("identifier").getAsString());
        JsonElement value = description.getAsJsonObject("components").get(component);
        assertNull(description.getAsJsonObject("components").get("minecraft:friction"), "a control sets one component");
        return value.getAsInt();
    }
}
