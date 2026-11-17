package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The limits read from BDS's content log, on lines as BDS 1.26.63.1 writes them. */
class FeedbackTest {
    private static final String BLOCK = "ERROR [Blocks] block_definitions | behavior_packs/paletter_spec | blocks/b_x_1.json |  -> components -> ";

    private static String facts(String component, String path, String... lines) {
        Feedback feedback = new Feedback();
        for (String line : lines) {
            feedback.add("block", line, null);
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
    void theOrderOfTwoMembers() {
        assertEquals("[\"order min max\"]", facts("block:minecraft:random_offset", "x.range",
            BLOCK + "minecraft:random_offset -> x -> range: max value cannot be lower than min value"));
    }

    @Test
    void aValueAnotherFieldIsRefusedWith() {
        String line = BLOCK + "minecraft:geometry: Invalid bones: Cannot use bone visibility feature with: minecraft:geometry.full_block";
        // a variant of bone_visibility whose base has the identifier BDS names
        Feedback bones = new Feedback();
        bones.add("block", line, variant("bone_visibility", "{\"identifier\": \"minecraft:geometry.full_block\", \"bone_visibility\": {\"arm\": \"1.0\"}}"));
        String fact = "[\"conflicts identifier 'minecraft:geometry.full_block'\"]";
        assertEquals(fact, bones.json().getAsJsonObject("block:minecraft:geometry").getAsJsonArray("bone_visibility").toString());
        // a variant of the identifier whose base has bone_visibility: the same fact, of the field the message names
        Feedback identifier = new Feedback();
        identifier.add("block", line, variant("identifier", "{\"identifier\": \"minecraft:geometry.full_block\", \"bone_visibility\": {\"arm\": \"1.0\"}}"));
        assertEquals(fact, identifier.json().getAsJsonObject("block:minecraft:geometry").getAsJsonArray("bone_visibility").toString());
        assertEquals(1, identifier.json().getAsJsonObject("block:minecraft:geometry").size(), "nothing about the identifier itself");
        // a line about the value the variant gives its own field ties nothing, whatever else of the variant it quotes
        Feedback expression = new Feedback();
        expression.add("block", "ERROR [Components] block_definitions | behavior_packs/paletter_spec | blocks/b_geometry_1.json | Bad expression: +1, "
            + "for bone visibilities of bone bone_a of geometry geometry.paletter_cube",
            variant("bone_visibility", "{\"identifier\": \"geometry.paletter_cube\", \"uv_lock\": [\"bone_a\"], \"bone_visibility\": {\"bone_a\": \"+1\"}}"));
        assertEquals(1, expression.json().size(), "only the generated marker");
        // a message that names no other field says nothing about one
        Feedback unnamed = new Feedback();
        unnamed.add("block", BLOCK + "minecraft:geometry: no geometry minecraft:geometry.full_block", variant("identifier",
            "{\"identifier\": \"minecraft:geometry.full_block\", \"bone_visibility\": {\"arm\": \"1.0\"}}"));
        assertEquals(1, unnamed.json().size(), "only the generated marker");
    }

    private static MappingInference.Variant variant(String path, String json) {
        return new MappingInference.Variant("paletter:b_geometry_1", "block", "blocks/b_geometry_1.json", "block:minecraft:geometry", "field", path,
            "label", null, JsonParser.parseString(json), List.of());
    }

    @Test
    void stepsOfRules() {
        Feedback feedback = new Feedback();
        feedback.step("block:minecraft:transformation", "rotation", 90);
        assertEquals("[\"step 90\"]", feedback.json().getAsJsonObject("block:minecraft:transformation").getAsJsonArray("rotation").toString());
    }
}
