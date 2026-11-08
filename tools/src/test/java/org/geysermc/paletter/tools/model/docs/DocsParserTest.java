package org.geysermc.paletter.tools.model.docs;

import com.google.gson.JsonPrimitive;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.geysermc.paletter.tools.model.Model;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocsParserTest {
    private static final String PAGE = """
        ---
        title: "Items Documentation - minecraft:durability"
        ---

        # Items Documentation - minecraft:durability

        Sets how much damage the item can take.

        > [!Note]
        > This item requires a format version of at least 1.20.0.

        ## Alternate Simple Representations

        This item can also be represented as a `Integer number`.

        ## Item Durability Properties

        |Name       |Default Value |Type |Description |
        |:----------|:-------------|:----|:-----------|
        | damage_chance | {"max":100,"min":100} | [Damage Chance](#block-intrange) item | Chance. |
        | max_durability | *not set* | Integer number | This is a required parameter and has a minimum of 0. |
        | mode | *not set* | [Mode](#mode-choices) choices | The mode. |

        ### Block IntRange

        #### Block IntRange Properties

        **JSON path:** `damage_chance`

        |Name       |Default Value |Type |Description |
        |:----------|:-------------|:----|:-----------|
        | max | 0 | Integer number |  |
        | min | 0 | Integer number |  |

        ### Mode choices

        |Value       |Title |Description |
        |:-----------|:-----|:-----------|
        | fast | Fast | |
        | slow | Slow | |
        """;

    @Test
    void parsesComponentPage() throws Exception {
        DocsParser.DocsSource source = new DocsParser.DocsSource() {
            @Override
            public String commit() {
                return "test";
            }

            @Override
            public Markdown page(String path) {
                return new Markdown(path, PAGE);
            }

            @Override
            public boolean exists(String path) {
                return true;
            }

            @Override
            public List<String> list(String directory) {
                return List.of();
            }
        };
        java.lang.reflect.Method method = DocsParser.class.getDeclaredMethod("componentPage", String.class, String.class, String.class);
        method.setAccessible(true);
        Model.Component c = (Model.Component) method.invoke(new DocsParser(source), "item", "x/minecraft_durability.md", "active");
        assertEquals("item:minecraft:durability", c.id());
        assertEquals("1.20.0", c.minFormatVersion());
        assertEquals(List.of("object", "integer"), c.forms().stream().map(Model.JType::type).toList());
        assertEquals(List.of("damage_chance", "damage_chance.max", "damage_chance.min", "max_durability", "mode"),
            c.fields().stream().map(Model.Field::path).toList());
        Model.Field max = c.field("max_durability");
        assertTrue(max.required());
        assertEquals(0.0, max.range().min());
        assertEquals(List.of(new JsonPrimitive("fast"), new JsonPrimitive("slow")), c.field("mode").enumValues());
        assertNotNull(c.field("damage_chance").defaultValue());
    }

    @Test
    void valueLists() {
        assertEquals(List.of(new JsonPrimitive(2L), new JsonPrimitive(3L), new JsonPrimitive(4L)),
            DocsParser.valuesFrom("Optional property. Valid values are `2, 3, 4`."));
        assertEquals(4, DocsParser.valuesFrom("Must be [0.0, 90.0, 180.0, 270.0]. Default is 0").size());
        assertEquals(List.of(new JsonPrimitive("minecraft:multi_block_part")),
            DocsParser.valuesFrom("The only valid (and required) value is `minecraft:multi_block_part`."));
    }

    @Test
    void slugsAndPaths() {
        assertEquals("legacy-floatrange", Markdown.slug("Legacy FloatRange"));
        assertEquals("rotation-rotation", Markdown.slug("Rotation (Rotation)"));
        assertEquals(List.of("conditions", "[]", "block_filter", "[]", "name"), AddonGenerator.tokens("conditions[].block_filter[].name"));
        assertEquals(List.of("{}", "values", "min"), AddonGenerator.tokens("{}.values.min"));
        assertEquals("conditions[].block_filter", AddonGenerator.parentOf("conditions[].block_filter[]"));
    }
}
