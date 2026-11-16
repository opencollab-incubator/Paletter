package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.model.infer.MappingInference.Pair;
import org.geysermc.paletter.tools.model.infer.Wire.Leaf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The automatic rules on synthetic (JSON value, wire leaf) observations, including cases that must not fit. */
class RuleFitTest {
    private static final String W = "minecraft:x.leaf";

    /** Observations: json text, leaf (null = not sent). */
    private static List<Pair> pairs(Object... jsonAndLeaf) {
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < jsonAndLeaf.length; i += 2) {
            JsonElement json = JsonParser.parseString((String) jsonAndLeaf[i]);
            Object leaf = jsonAndLeaf[i + 1];
            pairs.add(new Pair(json, leaf == null ? Map.of() : Map.of(W, new Leaf(Wire.typeOf(leaf), leaf)), "v" + i));
        }
        return pairs;
    }

    private static JsonObject scalar(List<Pair> pairs) {
        return MappingInference.scalar(pairs, Pair::json, W);
    }

    @Test
    void identityAcrossTypes() {
        assertEquals("identity", scalar(pairs("5", 5, "7", 7)).get("kind").getAsString());
        assertEquals("identity", scalar(pairs("true", (byte) 1, "false", (byte) 0)).get("kind").getAsString());
        assertEquals("identity", scalar(pairs("0.3", 0.3f, "1.5", 1.5f)).get("kind").getAsString()); // float precision
        assertEquals("identity", scalar(pairs("\"a\"", "a", "\"b\"", "b")).get("kind").getAsString());
    }

    @Test
    void linearUnitConversion() {
        JsonObject rule = scalar(pairs("1.6", 32, "3600", 72000, "1", 20));
        assertEquals("linear", rule.get("kind").getAsString());
        assertEquals(20.0, rule.get("scale").getAsDouble());
        assertEquals(0.0, rule.get("offset").getAsDouble());
        JsonObject offset = scalar(pairs("-8", 0.0f, "0", 8.0f, "4", 12.0f));
        assertEquals(1.0, offset.get("scale").getAsDouble());
        assertEquals(8.0, offset.get("offset").getAsDouble());
    }

    /** A list observation: the JSON list and the elements BDS sent for it. */
    private static Pair list(String json, String... sent) {
        Map<String, Leaf> wire = new java.util.TreeMap<>();
        for (int i = 0; i < sent.length; i++) {
            wire.put("minecraft:x.list[" + i + "]", new Leaf("string", sent[i]));
        }
        return new Pair(JsonParser.parseString(json), wire, json);
    }

    @Test
    void anOrderOfBdsOwnIsARuleOnlyForAClosedSetOfValuesAllSeen() {
        java.util.Set<String> varying = java.util.Set.of("minecraft:x.list[0]", "minecraft:x.list[1]", "minecraft:x.list[2]");
        List<Pair> faces = List.of(list("[\"north\", \"up\", \"east\"]", "up", "north", "east"), list("[\"east\", \"north\"]", "north", "east"));
        List<JsonObject> closed = MappingInference.orderedSet(faces, varying, java.util.Set.of("up", "north", "east"));
        assertEquals(1, closed.size());
        assertEquals("[\"up\",\"north\",\"east\"]", closed.get(0).get("order").toString());
        // One documented value was never sent: its place in the order is not known
        assertEquals(List.of(), MappingInference.orderedSet(faces, varying, java.util.Set.of("up", "north", "east", "down")));
        // Any string goes (bones, ordered by the hash of their names): the two captured names order no others
        List<Pair> bones = List.of(list("[\"bone_a\", \"paletter_b\"]", "paletter_b", "bone_a"), list("[\"bone_a\"]", "bone_a"));
        assertEquals(List.of(), MappingInference.orderedSet(bones, varying, java.util.Set.of()));
        // Nor does a table of the strings seen at one place of the list say anything about other strings
        List<Pair> second = pairs("[\"bone_a\", \"paletter_b\"]", "p", "[\"paletter_b\", \"bone_a\"]", "q");
        JsonObject open = MappingInference.array(second, W, false);
        assertFalse(open != null && open.get("kind").getAsString().equals("table"), String.valueOf(open));
        assertEquals("table", MappingInference.array(second, W, true).get("kind").getAsString());
        // The JSON's order without repeats needs no known set of values
        List<Pair> tags = List.of(list("[\"a\", \"a\", \"b\"]", "a", "b"), list("[\"b\"]", "b"));
        List<JsonObject> deduped = MappingInference.orderedSet(tags, varying, java.util.Set.of());
        assertEquals(1, deduped.size());
        assertFalse(deduped.get(0).has("order"));
    }

    @Test
    void aLeafOnlyTheListFormSendsIsNotExplainedByTheScalarFormNotSendingIt() {
        // uv_lock: a byte for true and false, the bones for a list, in an order of BDS's own
        String first = "minecraft:x.leaf[0]";
        List<Pair> uvLock = List.of(
            new Pair(JsonParser.parseString("true"), Map.of(W, new Leaf("byte", (byte) 1)), "true"),
            new Pair(JsonParser.parseString("false"), Map.of(W, new Leaf("byte", (byte) 0)), "false"),
            new Pair(JsonParser.parseString("[\"bone_a\", \"paletter_b\"]"),
                Map.of(first, new Leaf("string", "paletter_b"), "minecraft:x.leaf[1]", new Leaf("string", "bone_a")), "two"),
            new Pair(JsonParser.parseString("[\"bone_a\"]"), Map.of(first, new Leaf("string", "bone_a")), "one"),
            new Pair(JsonParser.parseString("[]"), Map.of(W, new Leaf("list", "[]")), "none"));
        assertEquals("identity", MappingInference.mixed(uvLock, W, false, false).get("kind").getAsString(), "the byte follows the boolean");
        assertNull(MappingInference.mixed(uvLock, first, false, false), "that booleans send no first element is no rule for the lists");
        // A leaf the lists do not send either stays what the scalar part says
        assertEquals("constant", MappingInference.mixed(uvLock, "minecraft:x.other", false, false).get("kind").getAsString());
    }

    @Test
    void tableForEnumsOnly() {
        List<Pair> enums = pairs("\"eat\"", 1, "\"drink\"", 2, "\"spear\"", 6);
        JsonObject rule = scalar(enums);
        assertEquals("table", rule.get("kind").getAsString());
        assertEquals("6", rule.getAsJsonObject("table").get("spear").getAsString());
        assertTrue(MappingInference.tableAllowed(enums, true));
        assertFalse(MappingInference.tableAllowed(enums, false), "free strings must not become a table");
        assertTrue(MappingInference.tableAllowed(pairs("true", (byte) 1, "false", (byte) 2), false));
    }

    @Test
    void inconsistentMappingFails() {
        // the same JSON value gives two different leaves: no function, no rule
        assertNull(scalar(pairs("\"a\"", 1, "\"a\"", 2)));
        // numbers that are neither identity nor linear: a table would fit anything, so callers reject numeric tables
        JsonObject rule = scalar(pairs("1", 5, "2", 3, "3", 9));
        assertEquals("table", rule.get("kind").getAsString());
    }

    @Test
    void constantWhenEveryValueGivesTheSameLeaf() {
        assertEquals("constant", scalar(pairs("1", 64, "2", 64)).get("kind").getAsString());
    }

    @Test
    void affixAndDefaultNamespace() {
        JsonObject rule = MappingInference.affix(pairs("\"minecraft:pig\"", "minecraft:pig<>", "\"cow\"", "minecraft:cow<>"), W);
        assertEquals("affix", rule.get("kind").getAsString());
        assertEquals("", rule.get("prefix").getAsString());
        assertEquals("<>", rule.get("suffix").getAsString());
        assertNull(MappingInference.affix(pairs("\"a\"", "x_a", "\"b\"", "y_b"), W), "inconsistent prefixes");
    }

    @Test
    void bitset() {
        JsonObject rule = MappingInference.array(pairs("[\"down\"]", 1, "[\"up\"]", 2, "[\"north\"]", 4, "[\"up\",\"north\"]", 6, "[]", 0), W, true);
        assertEquals("bitset", rule.get("kind").getAsString());
        assertEquals(4, rule.getAsJsonObject("bits").get("north").getAsInt());
        // OR does not hold: not a bit set
        JsonObject broken = MappingInference.array(pairs("[\"down\"]", 1, "[\"up\"]", 2, "[\"up\",\"down\"]", 7), W, true);
        assertTrue(broken == null || !broken.get("kind").getAsString().equals("bitset"));
    }

    @Test
    void listElementWithScale() {
        // rotation [x, y, z] in degrees -> RX quarter turns: element 0, linear 1/90
        List<Pair> pairs = new ArrayList<>();
        for (int[] v : new int[][]{{0, 90, 0}, {90, 180, 270}, {180, 270, 0}}) {
            pairs.add(new Pair(JsonParser.parseString("[" + v[0] + "," + v[1] + "," + v[2] + "]"), Map.of(W, new Leaf("int", v[0] / 90)), ""));
        }
        JsonObject rule = MappingInference.array(pairs, W, false);
        assertEquals("linear", rule.get("kind").getAsString());
        assertEquals(0, rule.get("element").getAsInt());
        assertEquals(round(1 / 90.0), rule.get("scale").getAsDouble());
    }

    @Test
    void twoVectorsPickTheElementWithoutOffset() {
        // the accepted rotation variants of the capture: two points fit a line on every element; RY is element 1
        List<Pair> pairs = new ArrayList<>();
        for (int[] v : new int[][]{{90, 180, 270}, {180, 270, 0}}) {
            pairs.add(new Pair(JsonParser.parseString("[" + v[0] + "," + v[1] + "," + v[2] + "]"), Map.of(W, new Leaf("int", v[1] / 90)), ""));
        }
        JsonObject rule = MappingInference.array(pairs, W, false);
        assertEquals("linear", rule.get("kind").getAsString());
        assertEquals(1, rule.get("element").getAsInt());
        assertEquals(0.0, rule.get("offset").getAsDouble());
    }

    @Test
    void mapEntry() {
        List<Pair> pairs = new ArrayList<>();
        pairs.add(new Pair(JsonParser.parseString("{\"bone_a\": 3}"), Map.of("x.bone_a", new Leaf("int", 3)), ""));
        pairs.add(new Pair(JsonParser.parseString("{\"bone_a\": 5}"), Map.of("x.bone_a", new Leaf("int", 5)), ""));
        JsonObject rule = MappingInference.object(pairs, "x.bone_a");
        assertEquals("identity", rule.get("kind").getAsString());
        assertTrue(rule.get("mapEntry").getAsBoolean());
    }

    private static double round(double d) {
        return Math.round(d * 1e6) / 1e6;
    }
}
