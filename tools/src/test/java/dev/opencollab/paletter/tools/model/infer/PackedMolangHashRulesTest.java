package dev.opencollab.paletter.tools.model.infer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.internal.component.Wire;
import dev.opencollab.paletter.tools.model.rule.HashOrderedSet;
import dev.opencollab.paletter.tools.model.rule.Leaf;
import dev.opencollab.paletter.tools.model.rule.Molang;
import dev.opencollab.paletter.tools.model.rule.Observation;
import dev.opencollab.paletter.tools.model.rule.PackedBits;
import dev.opencollab.paletter.tools.model.rule.Rule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fitting and evaluating the packed bits, Molang and hash ordered set rules on synthetic observations. */
class PackedMolangHashRulesTest {
    private static final String W = "minecraft:x.packed";

    private static Observation bit(String json, Integer sent) {
        return new Observation(json == null ? null : JsonParser.parseString(json),
            sent == null ? Map.of() : Map.of(W, new Leaf("byte", (byte) (int) sent)), "v");
    }

    // ---- packed bits ---------------------------------------------------------------------------------------------

    @Test
    void aBooleanThatIsOneBitOfASharedLeafIsPackedBits() {
        // face_dimming: true is the default (bit 0 set); isotropic: false is the default, true sets bit 1; bit 2 is set in all
        PackedBits dimming = PackedBits.fit(List.of(bit(null, 5), bit("true", 5), bit("false", 4)), W);
        assertEquals("{\"kind\":\"packed-bits\",\"mask\":1,\"base\":5}", dimming.toJson().toString());
        PackedBits isotropic = PackedBits.fit(List.of(bit(null, 5), bit("false", 5), bit("true", 7)), W);
        assertEquals("{\"kind\":\"packed-bits\",\"mask\":2,\"base\":5}", isotropic.toJson().toString());
        // a bit that false sets
        PackedBits inverted = PackedBits.fit(List.of(bit(null, 4), bit("true", 4), bit("false", 5)), W);
        assertEquals("{\"kind\":\"packed-bits\",\"mask\":1,\"base\":4,\"inverted\":true}", inverted.toJson().toString());
        assertEquals("[4, 5]", java.util.Arrays.toString(dimming.integral()));
    }

    @Test
    void packedBitsNeedTheEvidenceOfASharedLeaf() {
        // the field alone owns the leaf: a flag, an identity or a table
        assertNull(PackedBits.fit(List.of(bit(null, 0), bit("false", 0), bit("true", 1)), W));
        // two bits move together: not one bit
        assertNull(PackedBits.fit(List.of(bit(null, 4), bit("false", 4), bit("true", 7)), W));
        // no observation without the field: the base of the other fields is not known
        assertNull(PackedBits.fit(List.of(bit("false", 4), bit("true", 5)), W));
        // one value only, or two leaves for one value
        assertNull(PackedBits.fit(List.of(bit(null, 5), bit("true", 5)), W));
        assertNull(PackedBits.fit(List.of(bit(null, 5), bit("true", 5), bit("true", 7), bit("false", 4)), W));
        // the leaf without the field is neither of the two
        assertNull(PackedBits.fit(List.of(bit(null, 6), bit("true", 5), bit("false", 4)), W));
        // not a boolean, or a leaf BDS does not send
        assertNull(PackedBits.fit(List.of(bit(null, 5), bit("1", 5), bit("0", 4)), W));
        assertNull(PackedBits.fit(List.of(bit(null, 5), bit("true", 5), bit("false", null)), W));
    }

    @Test
    void packedBitsEvaluateWithTheOtherFieldsAtTheirDefault() {
        PackedBits dimming = PackedBits.fit(List.of(bit(null, 5), bit("true", 5), bit("false", 4)), W);
        dimming.nbt = "byte";
        assertEquals("4 (byte)", dimming.evaluate(JsonParser.parseString("false")));
        assertEquals("5 (byte)", dimming.evaluate(JsonParser.parseString("true")));
        assertNull(dimming.evaluate(JsonParser.parseString("1")));
        PackedBits inverted = PackedBits.fit(List.of(bit(null, 4), bit("true", 4), bit("false", 5)), W);
        inverted.nbt = "byte";
        assertEquals("4 (byte)", inverted.evaluate(JsonParser.parseString("true")));
        assertEquals("5 (byte)", inverted.evaluate(JsonParser.parseString("false")));
    }

    @Test
    void packedBitsExplainTheirBitsToTheUnknownBitGate() {
        JsonObject fields = new JsonObject();
        fields.add("face_dimming", field("{\"kind\": \"packed-bits\", \"mask\": 1, \"base\": 5, \"nbt\": \"byte\", \"wire\": \"m.materials.*.packed_bools\"}"));
        fields.add("isotropic", field("{\"kind\": \"packed-bits\", \"mask\": 2, \"base\": 5, \"nbt\": \"byte\", \"wire\": \"m.materials.*.packed_bools\"}"));
        JsonObject result = new JsonObject();
        result.add("fields", fields);
        JsonArray findings = Findings.findings("block:m", result);
        assertEquals("[2]", findings.get(0).getAsJsonObject().get("bits").toString());
    }

    private static JsonObject field(String rule) {
        return JsonParser.parseString("{\"status\": \"inferred\", \"rules\": [" + rule + "]}").getAsJsonObject();
    }

    // ---- Molang --------------------------------------------------------------------------------------------------

    private static JsonObject molang(String... textAndSent) {
        List<JsonElement> xs = new ArrayList<>();
        List<Leaf> ys = new ArrayList<>();
        for (int i = 0; i < textAndSent.length; i += 2) {
            xs.add(JsonParser.parseString("\"" + textAndSent[i] + "\""));
            ys.add(new Leaf("string", textAndSent[i + 1]));
        }
        Rule rule = RuleFit.scalar(xs.stream().map(x -> new Observation(x, Map.of("x.e", new Leaf("string", ys.get(xs.indexOf(x)).value())), "")).toList(),
            Observation::json, "x.e");
        return rule == null ? null : rule.toJson();
    }

    @Test
    void numbersSentNormalisedAreAMolangRuleWhereAnIdentityDoesNotFit() {
        assertEquals("{\"kind\":\"molang\"}", molang("1.0", "1.000000", ".5", "0.500000", "q.life_time", "q.life_time").toString());
        assertEquals("molang", molang("1e3", "1000.000000", "true", "1.000000", "-0", "-0.000000").get("kind").getAsString());
        // written as BDS sends them: identity
        assertEquals("identity", molang("q.a", "q.a", "q.b", "q.b").get("kind").getAsString());
    }

    @Test
    void aMolangRuleNeedsTwoNumbersAndEveryObservationToAgree() {
        assertNull(Molang.fit(List.of(JsonParser.parseString("\"1.0\""), JsonParser.parseString("\"q.a\"")),
            List.of(new Leaf("string", "1.000000"), new Leaf("string", "q.a"))), "one number");
        assertNull(Molang.fit(List.of(JsonParser.parseString("\"1.0\""), JsonParser.parseString("\"2.0\"")),
            List.of(new Leaf("string", "1.000000"), new Leaf("string", "2.0"))), "a number BDS sent as written");
        assertNull(Molang.fit(List.of(JsonParser.parseString("\"1.0\""), JsonParser.parseString("\"2.0\"")),
            List.of(new Leaf("string", "1.000000"), new Leaf("int", 2))), "another leaf type");
        // whole numbers alone (the permutation conditions 1 and 0): how BDS prints the others is not shown
        assertNull(Molang.fit(List.of(JsonParser.parseString("\"1\""), JsonParser.parseString("\"0\"")),
            List.of(new Leaf("string", "1.000000"), new Leaf("string", "0.000000"))));
        // true/false and a boolean value are the table of booleans' business, not Molang's
        assertNull(Molang.fit(List.of(JsonParser.parseString("true"), JsonParser.parseString("false")),
            List.of(new Leaf("string", "1.000000"), new Leaf("string", "0.000000"))));
    }

    @Test
    void molangEvaluatesTheWayTheEncoderWrites() {
        Rule rule = Rule.of(JsonParser.parseString("{\"kind\": \"molang\", \"nbt\": \"string\"}").getAsJsonObject());
        assertEquals("1.000000 (string)", rule.evaluate(JsonParser.parseString("\"1.0\"")));
        assertEquals("0.007812 (string)", rule.evaluate(JsonParser.parseString("\"0.0078125\"")));
        assertEquals("q.block_state('a:b') (string)", rule.evaluate(JsonParser.parseString("\"q.block_state('a:b')\"")));
        assertEquals("0.000000 (string)", rule.evaluate(JsonParser.parseString("\"false\"")));
    }

    /** The rule and the runtime must agree: the generated encoder calls the runtime, the fit uses the rule. */
    @Test
    void molangIsTheRuntimesNormalisation() {
        for (String text : List.of("1", "1.0", " 1 ", "1.", ".5", "1e3", "1e-3", "1.5E2", "-0", "-0.5", "0.0078125", "0.1234567", "16777217",
            "+1", "1 + 1", "1e+3", "0x10", "1f", "NaN", "Infinity", "1e39", "", "q.life_time", "true", "false")) {
            assertEquals(Wire.molang(text), Molang.normalise(text), text);
        }
    }

    // ---- hash ordered set ----------------------------------------------------------------------------------------

    private static Observation bones(String json, String... sent) {
        Map<String, Leaf> wire = new TreeMap<>();
        for (int i = 0; i < sent.length; i++) {
            wire.put("minecraft:x.bones[" + i + "]", new Leaf("string", sent[i]));
        }
        return new Observation(JsonParser.parseString(json), wire, json);
    }

    private static final Set<String> VARYING = Set.of("minecraft:x.bones[0]", "minecraft:x.bones[1]");

    @Test
    void namesSentByTheirHashAreAHashOrderedSet() {
        // the hash puts c before b before a: ["a","b"] is sent reversed, ["c","b"] as written
        List<HashOrderedSet> rules = HashOrderedSet.fit(List.of(bones("[\"a\", \"b\"]", "b", "a"), bones("[\"c\", \"b\"]", "c", "b"), bones("[\"a\"]", "a")), VARYING);
        assertEquals(1, rules.size());
        assertEquals("{\"kind\":\"hash-ordered-set\",\"nbt\":\"string\",\"tags\":[],\"wire\":\"minecraft:x.bones[]\"}", rules.get(0).toJson().toString());
        // a repeated name is sent once
        assertEquals(1, HashOrderedSet.fit(List.of(bones("[\"a\", \"b\", \"a\"]", "b", "a"), bones("[\"c\", \"b\"]", "c", "b")), VARYING).size());
        // an order that is not by the hash
        assertEquals(List.of(), HashOrderedSet.fit(List.of(bones("[\"a\", \"b\"]", "b", "a"), bones("[\"b\", \"c\"]", "b", "c")), VARYING));
    }

    @Test
    void aHashOrderNeedsMoreThanAReversalOrTheWrittenOrder() {
        // one list sent reversed: a reversal or the hash, there is no telling
        assertEquals(List.of(), HashOrderedSet.fit(List.of(bones("[\"a\", \"b\"]", "b", "a")), VARYING));
        // always as written: the JSON's order explains it
        assertEquals(List.of(), HashOrderedSet.fit(List.of(bones("[\"c\", \"b\"]", "c", "b"), bones("[\"c\", \"a\"]", "c", "a")), VARYING));
        // a list of other things than names
        assertEquals(List.of(), HashOrderedSet.fit(List.of(bones("[1, 2]", "2", "1"), bones("[3, 2]", "3", "2")), VARYING));
    }

    /** The rule and the runtime must agree: the generated encoder calls the runtime, the fit uses the rule. */
    @Test
    void hashOrderIsTheRuntimesOrder() {
        List<List<String>> lists = List.of(List.of("a", "b", "c"), List.of("zeta", "alpha", "mid", "beta"), List.of("bone_a", "bone_a"),
            List.of("lid", "pivot", "root", "up", "body", "0", "", "Top"), List.of("é", "e", "ñandú", "骨", "🦴", "z", "ÿ", "~", "bone_ä"));
        for (List<String> list : lists) {
            assertEquals(Wire.byNameHash(list), HashOrderedSet.byNameHash(list), list.toString());
        }
        assertNotNull(HashOrderedSet.byNameHash(List.of()));
        assertTrue(HashOrderedSet.byNameHash(List.of()).isEmpty());
    }

    // ---- registration --------------------------------------------------------------------------------------------

    @Test
    void theKindsReadAndWriteBackUnchanged() {
        for (String json : List.of("{\"kind\":\"packed-bits\",\"mask\":2,\"base\":5,\"inverted\":true,\"nbt\":\"byte\",\"wire\":\"a.b\"}",
            "{\"kind\":\"molang\",\"nbt\":\"string\",\"wire\":\"a.b{}\"}",
            "{\"kind\":\"hash-ordered-set\",\"nbt\":\"string\",\"tags\":[],\"wire\":\"a.b[]\"}")) {
            assertEquals(json, Rule.of(JsonParser.parseString(json).getAsJsonObject()).toJson().toString());
        }
    }

    @Test
    void theSetsOfAFieldAreTriedInOrderAndHashOnlyWhereNoOrderOfBdsOwnFits() {
        List<Rule> rules = RuleFit.sets(List.of(bones("[\"a\", \"b\"]", "b", "a"), bones("[\"c\", \"b\"]", "c", "b")), VARYING, Set.of());
        assertEquals(List.of("hash-ordered-set"), rules.stream().map(Rule::kind).toList());
        // a closed set of values all seen: the order of BDS's own explains it, the hash is not asked
        List<Rule> closed = RuleFit.sets(List.of(bones("[\"a\", \"b\"]", "b", "a"), bones("[\"c\", \"b\"]", "c", "b"), bones("[\"c\", \"a\", \"b\"]", "c", "b", "a")),
            Set.of("minecraft:x.bones[0]", "minecraft:x.bones[1]", "minecraft:x.bones[2]"), Set.of("a", "b", "c"));
        assertEquals(List.of("ordered-set"), closed.stream().map(Rule::kind).toList());
    }
}
