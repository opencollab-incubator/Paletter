package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.Model;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageTest {
    private static final Path MODEL = Path.of(System.getProperty("paletter.root", "..")).resolve("data/model");

    @Test
    void unreachedConstantsAreMembersOfTransforms() throws Exception {
        Class<?> transforms = Class.forName("org.geysermc.paletter.internal.component.Transforms");
        for (String name : Coverage.UNREACHED_CONSTANTS) {
            boolean exists = Arrays.stream(transforms.getDeclaredFields()).anyMatch(f -> f.getName().equals(name))
                || Arrays.stream(transforms.getDeclaredMethods()).anyMatch(m -> m.getName().equals(name));
            assertTrue(exists, "Coverage.UNREACHED_CONSTANTS names Transforms." + name + ", which does not exist");
        }
    }

    private static Model model() throws IOException {
        return Fixtures.read(MODEL).apply(Model.read(MODEL.resolve("components.json")));
    }

    private static Coverage coverage() throws IOException {
        return new Coverage(model(), Fixtures.read(MODEL), Map.of());
    }

    private static MappingInference.Capture capture(String bdsVersion, List<String> unattributed) {
        return new MappingInference.Capture("data/bds/" + bdsVersion + "/spec", bdsVersion, 1, List.of(), Map.of(), Map.of(), unattributed,
            new Feedback(), Map.of(), null);
    }

    @Test
    void theWirePathsNoRuleCoversAreListedPerBuildAndReason() throws IOException {
        Coverage coverage = coverage();
        assertFalse(coverage.markdown(List.of()).contains("## Wire paths no rule covers"), "no section without paths");
        coverage.wirePaths(Map.of("1.26.36.1", Map.of("the creator docs do not describe the component, so the model has no fields for it",
            List.of("item:minecraft:mystery a", "item:minecraft:mystery b"))));
        String markdown = coverage.markdown(List.of());
        assertTrue(markdown.contains("## Wire paths no rule covers\n\nIn the add-on capture of each build, with the reason the model has no rule "
            + "for them. A new path here is a change to review.\n\n### 1.26.36.1\n\nthe creator docs do not describe the component, so the "
            + "model has no fields for it (2):\n\n- `item:minecraft:mystery a`\n- `item:minecraft:mystery b`\n"), markdown);
    }

    @Test
    void theContentLogLinesWithoutAVariantAreListedOncePerBuild() throws IOException {
        Coverage coverage = coverage();
        assertFalse(coverage.markdown(List.of(capture("1.26.36.1", List.of()))).contains("## Content log lines without a variant"));
        String markdown = coverage.markdown(List.of(capture("1.26.36.1", List.of("WARN [Json] a `quoted` line", "WARN [Json] a `quoted` line")),
            capture("1.26.45.1", List.of()), capture("1.26.52.3", List.of("ERROR [Json] another"))));
        assertTrue(markdown.contains("## Content log lines without a variant\n\n"), markdown);
        assertTrue(markdown.contains("\n### 1.26.36.1\n\n- `WARN [Json] a 'quoted' line`\n\n### 1.26.52.3\n\n- `ERROR [Json] another`\n"), markdown);
        assertFalse(markdown.contains("### 1.26.45.1"), "a build without such lines has no heading");
    }

    @Test
    void aBoundTheDocsStateAndBdsDoesNotHaveIsDisputed() throws IOException {
        Model.Range docs = new Model.Range(0.0, 360.0, "");
        assertFalse(Coverage.disputed(docs, JsonParser.parseString("[0, 360]").getAsJsonArray()), "the same bounds, as numbers");
        assertTrue(Coverage.disputed(docs, JsonParser.parseString("[0, 359]").getAsJsonArray()));
        assertTrue(Coverage.disputed(docs, JsonParser.parseString("[1, 360]").getAsJsonArray()));
        assertFalse(Coverage.disputed(null, JsonParser.parseString("[1, 54]").getAsJsonArray()), "no docs range, nothing to dispute");
        assertFalse(Coverage.disputed(new Model.Range(null, 64.0, ""), JsonParser.parseString("[1, 64]").getAsJsonArray()), "a bound the docs lack");

        Coverage coverage = coverage();
        Model model = model();
        // placement_direction.y_rotation_offset: the docs say 0 to 360; bundle_interaction.num_viewable_slots: 1 to 64
        coverage.add(model.component("block:trait:minecraft:placement_direction"), JsonParser.parseString(
            "{\"status\": \"sent\", \"fields\": {\"y_rotation_offset\": {\"status\": \"inferred\", \"bdsRange\": [0, 359]}}}").getAsJsonObject());
        coverage.add(model.component("item:minecraft:bundle_interaction"), JsonParser.parseString(
            "{\"status\": \"sent\", \"fields\": {\"num_viewable_slots\": {\"status\": \"inferred\", \"bdsRange\": [1, 64]}}}").getAsJsonObject());
        String markdown = coverage.markdown(List.of());
        assertTrue(markdown.contains("| `block:trait:minecraft:placement_direction` | `y_rotation_offset` | [0.0, 360.0] | [0,359] | yes |"), markdown);
        assertTrue(markdown.contains("| `item:minecraft:bundle_interaction` | `num_viewable_slots` | [1.0, 64.0] | [1,64] |  |"), markdown);
    }
}
