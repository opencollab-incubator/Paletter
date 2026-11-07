package org.geysermc.paletter.spec.infer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.spec.infer.MappingInference.Pair;
import org.geysermc.paletter.spec.infer.Wire.Leaf;
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
        JsonObject rule = MappingInference.array(pairs("[\"down\"]", 1, "[\"up\"]", 2, "[\"north\"]", 4, "[\"up\",\"north\"]", 6, "[]", 0), W);
        assertEquals("bitset", rule.get("kind").getAsString());
        assertEquals(4, rule.getAsJsonObject("bits").get("north").getAsInt());
        // OR does not hold: not a bit set
        JsonObject broken = MappingInference.array(pairs("[\"down\"]", 1, "[\"up\"]", 2, "[\"up\",\"down\"]", 7), W);
        assertTrue(broken == null || !broken.get("kind").getAsString().equals("bitset"));
    }

    @Test
    void listElementWithScale() {
        // rotation [x, y, z] in degrees -> RX quarter turns: element 0, linear 1/90
        List<Pair> pairs = new ArrayList<>();
        for (int[] v : new int[][]{{0, 90, 0}, {90, 180, 270}, {180, 270, 0}}) {
            pairs.add(new Pair(JsonParser.parseString("[" + v[0] + "," + v[1] + "," + v[2] + "]"), Map.of(W, new Leaf("int", v[0] / 90)), ""));
        }
        JsonObject rule = MappingInference.array(pairs, W);
        assertEquals("linear", rule.get("kind").getAsString());
        assertEquals(0, rule.get("element").getAsInt());
        assertEquals(round(1 / 90.0), rule.get("scale").getAsDouble());
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
