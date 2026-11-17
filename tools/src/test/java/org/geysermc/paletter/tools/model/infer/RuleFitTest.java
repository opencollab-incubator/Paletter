package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.model.rule.Affix;
import org.geysermc.paletter.tools.model.rule.EnumTable;
import org.geysermc.paletter.tools.model.rule.Leaf;
import org.geysermc.paletter.tools.model.rule.Observation;
import org.geysermc.paletter.tools.model.rule.OrderedSet;
import org.geysermc.paletter.tools.model.rule.Rule;
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
    private static List<Observation> pairs(Object... jsonAndLeaf) {
        List<Observation> pairs = new ArrayList<>();
        for (int i = 0; i < jsonAndLeaf.length; i += 2) {
            JsonElement json = JsonParser.parseString((String) jsonAndLeaf[i]);
            Object leaf = jsonAndLeaf[i + 1];
            pairs.add(new Observation(json, leaf == null ? Map.of() : Map.of(W, new Leaf(Wire.typeOf(leaf), leaf)), "v" + i));
        }
        return pairs;
    }

    private static JsonObject scalar(List<Observation> pairs) {
        return json(RuleFit.scalar(pairs, Observation::json, W));
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
    private static Observation list(String json, String... sent) {
        Map<String, Leaf> wire = new java.util.TreeMap<>();
        for (int i = 0; i < sent.length; i++) {
            wire.put("minecraft:x.list[" + i + "]", new Leaf("string", sent[i]));
        }
        return new Observation(JsonParser.parseString(json), wire, json);
    }

    @Test
    void anOrderOfBdsOwnIsARuleOnlyForAClosedSetOfValuesAllSeen() {
        java.util.Set<String> varying = java.util.Set.of("minecraft:x.list[0]", "minecraft:x.list[1]", "minecraft:x.list[2]");
        List<Observation> faces = List.of(list("[\"north\", \"up\", \"east\"]", "up", "north", "east"), list("[\"east\", \"north\"]", "north", "east"));
        List<JsonObject> closed = json(OrderedSet.fit(faces, varying, java.util.Set.of("up", "north", "east")));
        assertEquals(1, closed.size());
        assertEquals("[\"up\",\"north\",\"east\"]", closed.get(0).get("order").toString());
        // One documented value was never sent: its place in the order is not known
        assertEquals(List.of(), json(OrderedSet.fit(faces, varying, java.util.Set.of("up", "north", "east", "down"))));
        // Any string goes (bones, ordered by the hash of their names): the two captured names order no others
        List<Observation> bones = List.of(list("[\"bone_a\", \"paletter_b\"]", "paletter_b", "bone_a"), list("[\"bone_a\"]", "bone_a"));
        assertEquals(List.of(), json(OrderedSet.fit(bones, varying, java.util.Set.of())));
        // Nor does a table of the strings seen at one place of the list say anything about other strings
        List<Observation> second = pairs("[\"bone_a\", \"paletter_b\"]", "p", "[\"paletter_b\", \"bone_a\"]", "q");
        JsonObject open = json(RuleFit.array(second, W, false));
        assertFalse(open != null && open.get("kind").getAsString().equals("table"), String.valueOf(open));
        assertEquals("table", json(RuleFit.array(second, W, true)).get("kind").getAsString());
        // The JSON's order without repeats needs no known set of values
        List<Observation> tags = List.of(list("[\"a\", \"a\", \"b\"]", "a", "b"), list("[\"b\"]", "b"));
        List<JsonObject> deduped = json(OrderedSet.fit(tags, varying, java.util.Set.of()));
        assertEquals(1, deduped.size());
        assertFalse(deduped.get(0).has("order"));
    }

    @Test
    void aLeafOnlyTheListFormSendsIsNotExplainedByTheScalarFormNotSendingIt() {
        // uv_lock: a byte for true and false, the bones for a list, in an order of BDS's own
        String first = "minecraft:x.leaf[0]";
        List<Observation> uvLock = List.of(
            new Observation(JsonParser.parseString("true"), Map.of(W, new Leaf("byte", (byte) 1)), "true"),
            new Observation(JsonParser.parseString("false"), Map.of(W, new Leaf("byte", (byte) 0)), "false"),
            new Observation(JsonParser.parseString("[\"bone_a\", \"paletter_b\"]"),
                Map.of(first, new Leaf("string", "paletter_b"), "minecraft:x.leaf[1]", new Leaf("string", "bone_a")), "two"),
            new Observation(JsonParser.parseString("[\"bone_a\"]"), Map.of(first, new Leaf("string", "bone_a")), "one"),
            new Observation(JsonParser.parseString("[]"), Map.of(W, new Leaf("list", "[]")), "none"));
        assertEquals("identity", json(RuleFit.mixed(uvLock, W, false, false)).get("kind").getAsString(), "the byte follows the boolean");
        assertNull(json(RuleFit.mixed(uvLock, first, false, false)), "that booleans send no first element is no rule for the lists");
        // A leaf the lists do not send either stays what the scalar part says
        assertEquals("constant", json(RuleFit.mixed(uvLock, "minecraft:x.other", false, false)).get("kind").getAsString());
    }

    @Test
    void tableForEnumsOnly() {
        List<Observation> enums = pairs("\"eat\"", 1, "\"drink\"", 2, "\"spear\"", 6);
        JsonObject rule = scalar(enums);
        assertEquals("table", rule.get("kind").getAsString());
        assertEquals("6", rule.getAsJsonObject("table").get("spear").getAsString());
        assertTrue(RuleFit.tableAllowed(enums, true));
        assertFalse(RuleFit.tableAllowed(enums, false), "free strings must not become a table");
        assertTrue(RuleFit.tableAllowed(pairs("true", (byte) 1, "false", (byte) 2), false));
    }

    @Test
    void aTableOfAnEnumIsCompleteOnlyWhenEveryValueOfTheSchemaWasSeen() {
        List<Observation> seen = pairs("\"eat\"", 1, "\"drink\"", 2, "\"spear\"", 6);
        Rule table = RuleFit.scalar(seen, Observation::json, W);
        EnumTable complete = EnumTable.fit(table, java.util.Set.of("eat", "drink", "spear"));
        assertEquals("enum-table", json(complete).get("kind").getAsString());
        assertEquals("6", json(complete).getAsJsonObject("table").get("spear").getAsString());
        // A value of the schema no capture tried: its leaf is not known, so the table stays a table
        assertNull(EnumTable.fit(table, java.util.Set.of("eat", "drink", "spear", "brush")));
        // A value outside the schema, and a field with no schema enum
        assertNull(EnumTable.fit(table, java.util.Set.of("eat", "drink")));
        assertNull(EnumTable.fit(table, java.util.Set.of()));
        // Not a table at all
        assertNull(EnumTable.fit(RuleFit.scalar(pairs("\"a\"", "a", "\"b\"", "b"), Observation::json, W), java.util.Set.of("a", "b")));
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
        JsonObject rule = json(Affix.fit(pairs("\"minecraft:pig\"", "minecraft:pig<>", "\"cow\"", "minecraft:cow<>"), W));
        assertEquals("affix", rule.get("kind").getAsString());
        assertEquals("", rule.get("prefix").getAsString());
        assertEquals("<>", rule.get("suffix").getAsString());
        assertNull(json(Affix.fit(pairs("\"a\"", "x_a", "\"b\"", "y_b"), W)), "inconsistent prefixes");
    }

    @Test
    void bitset() {
        JsonObject rule = json(RuleFit.array(pairs("[\"down\"]", 1, "[\"up\"]", 2, "[\"north\"]", 4, "[\"up\",\"north\"]", 6, "[]", 0), W, true));
        assertEquals("bitset", rule.get("kind").getAsString());
        assertEquals(4, rule.getAsJsonObject("bits").get("north").getAsInt());
        // OR does not hold: not a bit set
        JsonObject broken = json(RuleFit.array(pairs("[\"down\"]", 1, "[\"up\"]", 2, "[\"up\",\"down\"]", 7), W, true));
        assertTrue(broken == null || !broken.get("kind").getAsString().equals("bitset"));
    }

    @Test
    void listElementWithScale() {
        // rotation [x, y, z] in degrees -> RX quarter turns: element 0, linear 1/90
        List<Observation> pairs = new ArrayList<>();
        for (int[] v : new int[][]{{0, 90, 0}, {90, 180, 270}, {180, 270, 0}}) {
            pairs.add(new Observation(JsonParser.parseString("[" + v[0] + "," + v[1] + "," + v[2] + "]"), Map.of(W, new Leaf("int", v[0] / 90)), ""));
        }
        JsonObject rule = json(RuleFit.array(pairs, W, false));
        assertEquals("linear", rule.get("kind").getAsString());
        assertEquals(0, rule.get("element").getAsInt());
        assertEquals(round(1 / 90.0), rule.get("scale").getAsDouble());
    }

    @Test
    void twoVectorsPickTheElementWithoutOffset() {
        // the accepted rotation variants of the capture: two points fit a line on every element; RY is element 1
        List<Observation> pairs = new ArrayList<>();
        for (int[] v : new int[][]{{90, 180, 270}, {180, 270, 0}}) {
            pairs.add(new Observation(JsonParser.parseString("[" + v[0] + "," + v[1] + "," + v[2] + "]"), Map.of(W, new Leaf("int", v[1] / 90)), ""));
        }
        JsonObject rule = json(RuleFit.array(pairs, W, false));
        assertEquals("linear", rule.get("kind").getAsString());
        assertEquals(1, rule.get("element").getAsInt());
        assertEquals(0.0, rule.get("offset").getAsDouble());
    }

    @Test
    void mapEntry() {
        List<Observation> pairs = new ArrayList<>();
        pairs.add(new Observation(JsonParser.parseString("{\"bone_a\": 3}"), Map.of("x.bone_a", new Leaf("int", 3)), ""));
        pairs.add(new Observation(JsonParser.parseString("{\"bone_a\": 5}"), Map.of("x.bone_a", new Leaf("int", 5)), ""));
        JsonObject rule = json(RuleFit.object(pairs, "x.bone_a", false));
        assertEquals("identity", rule.get("kind").getAsString());
        assertTrue(rule.get("mapEntry").getAsBoolean());
    }

    @Test
    void aMapEntryNeedsTheEvidenceAScalarDoes() {
        // bone_visibility: the second entry only ever had one value, which BDS sends as "1.000000"
        List<Observation> one = List.of(
            new Observation(JsonParser.parseString("{\"bone_a\": \"q.x\"}"), Map.of("x.bone_a", new Leaf("string", "q.x")), ""),
            new Observation(JsonParser.parseString("{\"bone_a\": \"q.x\", \"bone_b\": \"on\"}"),
                Map.of("x.bone_a", new Leaf("string", "q.x"), "x.bone_b", new Leaf("string", "1.000000")), ""));
        assertNull(json(RuleFit.object(one, "x.bone_b", false)), "one value is no constant: another was never tried");
        // two strings sent differently: a table of them is no rule for free text, only for an enum
        List<Observation> two = new ArrayList<>(one);
        two.add(new Observation(JsonParser.parseString("{\"bone_b\": \"q.x\"}"), Map.of("x.bone_b", new Leaf("string", "q.x")), ""));
        assertNull(json(RuleFit.object(two, "x.bone_b", false)));
        assertEquals("table", json(RuleFit.object(two, "x.bone_b", true)).get("kind").getAsString());
        // a leaf that stays the same while the entry's value changes is a constant
        List<Observation> version = List.of(
            new Observation(JsonParser.parseString("{\"bone_a\": \"q.x\"}"), Map.of("x.bone_a.version", new Leaf("short", (short) 13)), ""),
            new Observation(JsonParser.parseString("{\"bone_a\": \"1.0\"}"), Map.of("x.bone_a.version", new Leaf("short", (short) 13)), ""));
        assertEquals("constant", json(RuleFit.object(version, "x.bone_a.version", false)).get("kind").getAsString());
    }

    private static JsonObject json(Rule rule) {
        return rule == null ? null : rule.toJson();
    }

    private static List<JsonObject> json(List<OrderedSet> rules) {
        return rules.stream().map(Rule::toJson).toList();
    }

    private static double round(double d) {
        return Math.round(d * 1e6) / 1e6;
    }
}
