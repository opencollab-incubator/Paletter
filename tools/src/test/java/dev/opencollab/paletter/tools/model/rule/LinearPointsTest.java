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
