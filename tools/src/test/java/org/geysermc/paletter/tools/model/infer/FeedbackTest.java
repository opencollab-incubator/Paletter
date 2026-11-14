package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The limits read from BDS's content log, on lines as BDS 1.26.63.1 writes them. */
class FeedbackTest {
    private static final String BLOCK = "ERROR [Blocks] block_definitions | behavior_packs/paletter_spec | blocks/b_x_1.json |  -> components -> ";

    private static String facts(String component, String path, String... lines) {
        Feedback feedback = new Feedback();
        for (String line : lines) {
            feedback.add("block", line);
        }
        JsonObject json = feedback.json();
        return json.getAsJsonObject(component).getAsJsonArray(path).toString();
    }

    @Test
    void listLengths() {
        assertEquals("[\"maxItems 1\"]", facts("block:minecraft:liquid_detection", "detection_rules",
            BLOCK + "minecraft:liquid_detection -> detection_rules: too many input elements, expected no more than 1"));
        assertEquals("[\"items 2\"]", facts("block:minecraft:tick", "interval_range",
            BLOCK + "minecraft:tick -> interval_range: wrong number of elements in fixed size container, expected exactly 2"));
    }

    @Test
    void theLimitOfTheSumOfTwoVectorMembers() {
        assertEquals("[\"box origin size -8,0,-8 8,16,8\"]", facts("block:minecraft:selection_box", "",
            BLOCK + "minecraft:selection_box: Invalid box size\"origin\" + \"size\", must be inside the following limit: Vec3(-8,0,-8) and Vec3(8,16,8)"));
        assertEquals("[\"box origin size -8,0,-8 8,24,8\"]", facts("block:minecraft:collision_box", "",
            BLOCK + "minecraft:collision_box: Invalid box sizeThe total of each \"origin\" + \"size\" pair, must be inside the following limit: "
                + "Vec3(-8,0,-8) and Vec3(8,24,8)"));
    }

    @Test
    void stepsOfRules() {
        Feedback feedback = new Feedback();
        feedback.step("block:minecraft:transformation", "rotation", 90);
        assertEquals("[\"step 90\"]", feedback.json().getAsJsonObject("block:minecraft:transformation").getAsJsonArray("rotation").toString());
    }
}
