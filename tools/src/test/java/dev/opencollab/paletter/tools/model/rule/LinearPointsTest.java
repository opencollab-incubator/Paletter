package dev.opencollab.paletter.tools.model.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** How many different inputs a linear rule needs: two always fit a line, the third is the evidence. */
class LinearPointsTest {
    private static List<JsonElement> xs(String... numbers) {
        return java.util.Arrays.stream(numbers).map(JsonParser::parseString).toList();
    }

    private static List<Leaf> ys(float... values) {
        List<Leaf> leaves = new java.util.ArrayList<>();
        for (float v : values) {
            leaves.add(new Leaf("float", v));
        }
        return leaves;
    }

    @Test
    void twoPointsAreNoLineThreeAre() {
        // two points: any line, so scale 3 offset 8 says nothing
        assertNull(Linear.fit(xs("0", "4"), ys(8, 20)));
        // repeated inputs are no new point
        assertNull(Linear.fit(xs("0", "4", "4", "0"), ys(8, 20, 20, 8)));
        // three points on a line are
        Linear line = Linear.fit(xs("0", "4", "8"), ys(8, 20, 32));
        assertNotNull(line);
        assertEquals(3.0, line.scale);
        assertEquals(8.0, line.offset);
        // three points that are no line are not
        assertNull(Linear.fit(xs("0", "4", "8"), ys(8, 20, 31)));
    }

    @Test
    void aWrappedLineNeedsThreePointsToo() {
        // -90 and 90 degrees as 3 and 1 quarter turns: two points, so no wrapped rule either
        List<Leaf> turns = List.of(new Leaf("int", 3), new Leaf("int", 1));
        assertNull(Linear.fit(xs("-90", "90"), turns));
        Linear wrapped = Linear.fit(xs("-90", "90", "180"), List.of(new Leaf("int", 3), new Leaf("int", 1), new Leaf("int", 2)));
        assertNotNull(wrapped);
        assertEquals(4L, wrapped.modulo);
    }

    private static List<Leaf> ints(int... values) {
        List<Leaf> leaves = new java.util.ArrayList<>();
        for (int v : values) {
            leaves.add(new Leaf("int", v));
        }
        return leaves;
    }

    /**
     * use_modifiers.use_duration seconds -> item_properties.use_duration ticks, as BDS 1.26.36.1 to 1.26.70.24 send them: the
     * float product truncated. 0.35 is 7 (the double product 6.99999988 would truncate to 6), 0.33 is 6 and 0.38 is 7 (not
     * rounded to 7 and 8), the ties 0.025, 0.075 and 0.125 are 0, 1 and 2 (neither half up nor half even), 0.31 is 6 (no ceiling).
     */
    @Test
    void bdsTruncatesTheFloatProductOfUseDuration() {
        List<JsonElement> xs = xs("0", "0.5", "1", "2.25", "30", "0.35", "0.7", "0.95", "0.33", "0.38", "0.025", "0.075", "0.125", "0.31");
        List<Leaf> ys = ints(0, 10, 20, 45, 600, 7, 14, 19, 6, 7, 0, 1, 2, 6);
        Linear line = Linear.fit(xs, ys);
        assertNotNull(line);
        assertEquals(20.0, line.scale);
        assertEquals(0.0, line.offset);
        assertEquals(Linear.Rounding.FLOAT_TRUNCATE, line.rounding);
        line.nbt = "int";
        assertEquals("7 (int)", line.evaluate(JsonParser.parseString("0.35")));
        assertEquals("6 (int)", line.evaluate(JsonParser.parseString("0.33")));
        // the rule round-trips through mappings.json
        Linear read = (Linear) Rule.of(line.toJson());
        assertEquals(Linear.Rounding.FLOAT_TRUNCATE, read.rounding);
        assertEquals("\"float32\"", line.toJson().get("arithmetic").toString());
        assertEquals("\"truncate\"", line.toJson().get("rounding").toString());
    }

    /** Each rounding is told apart by the inputs that need it, and inputs the line maps onto whole numbers need none. */
    @Test
    void theRoundingIsTheOneTheObservationsShow() {
        List<JsonElement> xs = xs("0", "1", "2", "0.33", "0.31");
        // 6.6 -> 7 and 6.2 -> 6: half up (half even and the double arithmetic agree on these, and come first in the order)
        assertEquals(Linear.Rounding.DOUBLE_HALF_UP, Linear.fit(xs, ints(0, 20, 40, 7, 6)).rounding);
        // 6.6 -> 7, 6.2 -> 7: ceiling
        assertEquals(Linear.Rounding.DOUBLE_CEILING, Linear.fit(xs, ints(0, 20, 40, 7, 7)).rounding);
        // whole numbers only: a plain line, no rounding
        assertNull(Linear.fit(xs("0", "1", "2"), ints(0, 20, 40)).rounding);
        // a leaf no rounding of the line explains is no rule
        assertNull(Linear.fit(xs, ints(0, 20, 40, 9, 6)));
        // nor are two exact points with rounded ones: the line needs three
        assertNull(Linear.fit(xs("0", "1", "0.33", "0.31"), ints(0, 20, 6, 6)));
        // a float leaf is never rounded
        assertNull(Linear.fit(xs, ys(0, 20, 40, 7, 6)));
    }

    /**
     * The collision_box origins [1,2,3], [-4,5.5,0], [0,0,0] against minZ 11, 8, 8: x fits {@code (3x + 8) mod 12} by chance
     * (the true rule is z + 8, with too few different z). A float leaf and a scale of 3 are no wrapped steps.
     */
    @Test
    void aWrappedLineByChanceIsNoRule() {
        assertNull(Linear.fit(xs("1", "-4", "0"), ys(11, 8, 8)));
        // the same numbers on an integral leaf: the scale 3 is n, a step size, so only the leaf type refuses it above
        List<Leaf> ints = List.of(new Leaf("int", 11), new Leaf("int", 8), new Leaf("int", 8));
        assertNotNull(Linear.fit(xs("1", "-4", "0"), ints));
        // whole steps are n or 1/n (degrees / 90), never 2.5 or 0.4
        org.junit.jupiter.api.Assertions.assertTrue(Linear.wholeSteps(3) && Linear.wholeSteps(-1 / 90.0) && Linear.wholeSteps(1));
        org.junit.jupiter.api.Assertions.assertFalse(Linear.wholeSteps(2.5) || Linear.wholeSteps(0.4));
    }
}
