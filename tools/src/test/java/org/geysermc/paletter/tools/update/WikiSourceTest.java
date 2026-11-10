package org.geysermc.paletter.tools.update;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The wiki markdown is read for identifier tokens and versions only. */
class WikiSourceTest {
    @Test
    void componentPagesListNestedKeysAndTheKeysOfAMember() {
        String markdown = """
            ### Geometry

            #### Object Format {#geometry-object}

            -   `identifier` — String
                -   The identifier of the geometry.
            -   `n_way_visual_rotation` — Object (optional)
                -   Keys are axes (`x`, `y` or `z`) and values are state names (e.g. `"minecraft:a"`).
            -   `bone_visibility` — Object (optional)
                -   Keys represent bone names.

            ```json
            "minecraft:geometry": {"identifier": "geometry.example"}
            ```

            ### No Example

            -   `lost` — String
            """;

        assertEquals(List.of(
            new WikiSource.Key("block:minecraft:geometry", "identifier"),
            new WikiSource.Key("block:minecraft:geometry", "n_way_visual_rotation"),
            new WikiSource.Key("block:minecraft:geometry", "n_way_visual_rotation.x"),
            new WikiSource.Key("block:minecraft:geometry", "n_way_visual_rotation.y"),
            new WikiSource.Key("block:minecraft:geometry", "n_way_visual_rotation.z"),
            new WikiSource.Key("block:minecraft:geometry", "bone_visibility")), WikiSource.keys("block", markdown),
            "the keys of a section count once its example names the component; the value in the text is not a key");
    }

    @Test
    void formatHistoriesMarkTheVersionedChanges() {
        String markdown = """
            ## 1.21.120

            ### Components

            -   Released `minecraft:flower_pottable` from experimental

            <Tag name="experimental" />
            <Label color="green">Versioned</Label>

            -   Updated `minecraft:material_instances`
                -   Added `alpha_masked_tint` instance parameter which determines whether something.
                    -   This requires a `tint_method` to be specified.

            ## 1.21.110

            ### Components

            <Label color="green">Versioned</Label>

            -   Updated `minecraft:block_entity`
                -   Updated `container` parameter
                    -   Added `title` parameter which determines the heading.
            """;

        List<WikiSource.Change> changes = WikiSource.history("block", markdown);

        assertEquals(List.of(
            new WikiSource.Change("1.21.120", false, "block:minecraft:flower_pottable", null, null),
            new WikiSource.Change("1.21.120", true, "block:minecraft:material_instances", null, null),
            new WikiSource.Change("1.21.120", true, "block:minecraft:material_instances", null, "alpha_masked_tint"),
            new WikiSource.Change("1.21.110", true, "block:minecraft:block_entity", null, null),
            new WikiSource.Change("1.21.110", true, "block:minecraft:block_entity", null, "container"),
            new WikiSource.Change("1.21.110", true, "block:minecraft:block_entity", "container", "title")), changes);
    }
}
