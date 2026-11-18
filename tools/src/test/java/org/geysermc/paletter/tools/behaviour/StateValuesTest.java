package org.geysermc.paletter.tools.behaviour;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.behaviour.BlockBehaviour;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The values the importer takes from the per-state sections of a probe capture, on a small palette and captures made up for it. */
class StateValuesTest {
    /** Hashes 1 to 4: stairs with a corner a to d; 5 to 7: a fence with x to z; 8 to 9: soul fire of age 3 and 4. */
    private static Map<Integer, NbtMap> palette() {
        Map<Integer, NbtMap> palette = new LinkedHashMap<>();
        String[] corners = {"a", "b", "c", "d"};
        for (int i = 0; i < corners.length; i++) {
            palette.put(1 + i, state("minecraft:stairs", NbtMap.builder().putString("corner", corners[i]).build()));
        }
        String[] directions = {"x", "y", "z"};
        for (int i = 0; i < directions.length; i++) {
            palette.put(5 + i, state("minecraft:fence", NbtMap.builder().putString("v", directions[i]).build()));
        }
        palette.put(8, state("minecraft:soul_fire", NbtMap.builder().putInt("age", 3).build()));
        palette.put(9, state("minecraft:soul_fire", NbtMap.builder().putInt("age", 4).build()));
        return palette;
    }

    private static NbtMap state(String name, NbtMap states) {
        return NbtMap.builder().putString("name", name).putCompound("states", states).build();
    }

    private static JsonObject capture(String sections) {
        return JsonParser.parseString("{" + sections + "}").getAsJsonObject();
    }

    private static String summary(String base, String differ, String unclear) {
        return "{\"base\":" + base + ",\"differ\":" + differ + ",\"unclear\":" + unclear + "}";
    }

    private static String light(String blocks) {
        return "\"light_states\":{\"blocks\":{" + blocks + "}}";
    }

    private static Object value(Values<Integer> values, org.geysermc.paletter.behaviour.BehaviourKey<?> key, int hash) {
        Map<Integer, Object> byHash = values.byKey.get(key);
        return byHash == null ? null : byHash.get(hash);
    }

    @Test
    void everyStateOfABlockHasTheBaseValueExceptTheOnesThatDifferOrAreUnclear() {
        JsonObject capture = capture(light("\"minecraft:stairs\":{\"states\":4,\"changed\":[],\"emission\":"
            + summary("0", "[{\"state\":{\"corner\":\"b\"},\"value\":7}]", "[{\"corner\":\"c\"}]")
            + ",\"dampening\":" + summary("2", "[]", "[]") + "}"));
        List<String> log = new ArrayList<>();
        Values<Integer> values = StateValues.blocks(capture, palette(), log, "BDS_PROBE x");
        assertEquals(0, value(values, BlockBehaviour.LIGHT_EMISSION, 1));
        assertEquals(7, value(values, BlockBehaviour.LIGHT_EMISSION, 2));
        assertNull(value(values, BlockBehaviour.LIGHT_EMISSION, 3), "unclear: no value");
        assertEquals(0, value(values, BlockBehaviour.LIGHT_EMISSION, 4));
        assertEquals(2, value(values, BlockBehaviour.LIGHT_DAMPENING, 3), "the dampening of that state was clear");
        assertNull(value(values, BlockBehaviour.LIGHT_EMISSION, 5), "the fence was not probed");
        assertTrue(log.isEmpty(), log.toString());
    }

    @Test
    void aStateBdsDidNotKeepTakesTheValueEveryMeasuredStateOfItsBlockHasAndSaysItWasFilled() {
        JsonObject capture = capture(light("\"minecraft:stairs\":{\"states\":4,\"changed\":[{\"requested\":{\"corner\":\"c\"},\"became\":\"minecraft:stairs\","
            + "\"as\":{\"corner\":\"b\"}}],\"emission\":" + summary("7", "[]", "[]") + ",\"dampening\":" + summary("2", "[]", "[]") + "}"));
        Values<Integer> values = StateValues.blocks(capture, palette(), new ArrayList<>(), "BDS_PROBE x");
        assertEquals(7, value(values, BlockBehaviour.LIGHT_EMISSION, 3));
        assertTrue(values.postProcessed.get(BlockBehaviour.LIGHT_EMISSION).get(3).startsWith("filled: "));
        assertFalse(values.postProcessed.get(BlockBehaviour.LIGHT_EMISSION).containsKey(1), "a measured state is no fill");
    }

    @Test
    void aStateBdsDidNotKeepStaysWithoutAValueWhereTheMeasuredStatesDiffer() {
        JsonObject capture = capture(light("\"minecraft:fence\":{\"states\":3,\"changed\":[{\"requested\":{\"v\":\"z\"},\"became\":null,\"as\":null}],"
            + "\"emission\":" + summary("0", "[{\"state\":{\"v\":\"y\"},\"value\":5}]", "[]") + ",\"dampening\":" + summary("null", "[]", "[]") + "}"));
        Values<Integer> values = StateValues.blocks(capture, palette(), new ArrayList<>(), "BDS_PROBE x");
        assertEquals(0, value(values, BlockBehaviour.LIGHT_EMISSION, 5));
        assertEquals(5, value(values, BlockBehaviour.LIGHT_EMISSION, 6));
        assertNull(value(values, BlockBehaviour.LIGHT_EMISSION, 7), "the measured states of the block differ: no fill");
        assertNull(value(values, BlockBehaviour.LIGHT_DAMPENING, 5), "a block whose readings decide nothing has no value");
        assertNull(value(values, BlockBehaviour.LIGHT_DAMPENING, 7));
    }

    @Test
    void frictionIsAFloatAndAnUnverifiedSectionIsLoggedAndGivesNothing() {
        JsonObject capture = capture("\"friction_states\":{\"blocks\":{\"minecraft:stairs\":{\"states\":4,\"changed\":[],\"friction\":" + summary("0.6", "[]", "[]")
            + "}}},\"light_states\":{\"unverified\":\"control probe:light_emission_7 reads [0,3,0]\"}");
        List<String> log = new ArrayList<>();
        Values<Integer> values = StateValues.blocks(capture, palette(), log, "BDS_PROBE 1.2.3");
        assertEquals(0.6f, value(values, BlockBehaviour.FRICTION, 1));
        assertNull(value(values, BlockBehaviour.LIGHT_EMISSION, 1));
        assertEquals(List.of("BDS_PROBE 1.2.3: light_states is unverified: control probe:light_emission_7 reads [0,3,0]"), log);
    }

    @Test
    void theStatesOfABlockBdsTurnsIntoAnotherAreMeasuredInTheRecipeThatKeptThem() {
        // all of the soul fire states changed on placement: the block has no measured state and nothing to fill from
        JsonObject capture = capture(light("\"minecraft:soul_fire\":{\"states\":2,\"changed\":[{\"requested\":{\"age\":3},\"became\":\"minecraft:air\",\"as\":null},"
            + "{\"requested\":{\"age\":4},\"became\":\"minecraft:air\",\"as\":null}],\"emission\":" + summary("null", "[]", "[]") + ",\"dampening\":"
            + summary("null", "[]", "[]") + "}") + ",\"around_recipes\":{\"blocks\":{\"minecraft:soul_fire\":{\"measured\":[{\"state\":{\"age\":3},"
            + "\"recipe\":\"on soul soil\",\"emission\":10}],\"unclear\":[{\"state\":{\"age\":4},\"reason\":\"not kept\"}]}}}");
        Values<Integer> values = StateValues.blocks(capture, palette(), new ArrayList<>(), "BDS_PROBE x");
        assertEquals(10, value(values, BlockBehaviour.LIGHT_EMISSION, 8));
        assertNull(value(values, BlockBehaviour.LIGHT_EMISSION, 9));
        assertNull(values.postProcessed.getOrDefault(BlockBehaviour.LIGHT_EMISSION, Map.of()).get(8), "measured, not filled");
    }

    @Test
    void aMeasuredRecipeWinsOverAFill() {
        JsonObject capture = capture(light("\"minecraft:soul_fire\":{\"states\":2,\"changed\":[{\"requested\":{\"age\":3},\"became\":\"minecraft:soul_fire\","
            + "\"as\":{\"age\":4}}],\"emission\":" + summary("15", "[]", "[]") + ",\"dampening\":" + summary("null", "[]", "[]") + "}")
            + ",\"around_recipes\":{\"blocks\":{\"minecraft:soul_fire\":{\"measured\":[{\"state\":{\"age\":3},\"recipe\":\"on soul soil\",\"emission\":10}],"
            + "\"unclear\":[]}}}");
        Values<Integer> values = StateValues.blocks(capture, palette(), new ArrayList<>(), "BDS_PROBE x");
        assertEquals(10, value(values, BlockBehaviour.LIGHT_EMISSION, 8));
        assertFalse(values.postProcessed.get(BlockBehaviour.LIGHT_EMISSION).containsKey(8));
        assertEquals(15, value(values, BlockBehaviour.LIGHT_EMISSION, 9));
    }

    @Test
    void statesThePaletteLacksAreCountedInTheLog() {
        JsonObject capture = capture(light("\"minecraft:stairs\":{\"states\":4,\"changed\":[],\"emission\":"
            + summary("0", "[{\"state\":{\"corner\":\"zzz\"},\"value\":7}]", "[]") + ",\"dampening\":" + summary("2", "[]", "[]") + "}"));
        List<String> log = new ArrayList<>();
        StateValues.blocks(capture, palette(), log, "BDS_PROBE x");
        assertEquals(List.of("BDS_PROBE x: 1 probed states are no state of the palette (blocks it does not have)"), log);
    }

    @Test
    void aCaptureWithoutTheSectionsGivesNothing() {
        assertTrue(StateValues.blocks(capture("\"doors\":{}"), palette(), new ArrayList<>(), "x").isEmpty());
    }
}
