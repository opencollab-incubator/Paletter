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
    void twoPointsFitALineTodayButNeedAThirdOnceTheRecaptureHasOne() {
        // two points: any line, so scale 3 offset 8 says nothing
        assertEquals(2, Linear.MIN_DISTINCT_POINTS, "3 once the recapture varies every linear field in three values");
        assertNotNull(Linear.fit(xs("0", "4"), ys(8, 20)));
        assertNull(Linear.fit(xs("0", "4"), ys(8, 20), 3));
        // repeated inputs are no new point
        assertNull(Linear.fit(xs("0", "4", "4", "0"), ys(8, 20, 20, 8), 3));
        // three points on a line are
        Linear line = Linear.fit(xs("0", "4", "8"), ys(8, 20, 32), 3);
        assertEquals(3.0, line.scale);
        assertEquals(8.0, line.offset);
        // three points that are no line are not
        assertNull(Linear.fit(xs("0", "4", "8"), ys(8, 20, 31), 3));
    }
}
