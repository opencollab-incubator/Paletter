package org.geysermc.paletter.tools.model.addon;

import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.Model;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The spec add-on has blocks with block tags, which BDS sends as {@code blockTags}. */
class TagVariantsTest {
    @Test
    void oneBlockHasOneTagAndOneHasAll() throws IOException {
        Path model = Path.of(System.getProperty("paletter.root", "..")).resolve("data/model");
        AddonGenerator generator = new AddonGenerator(Model.read(model.resolve("components.json")), Fixtures.read(model));
        List<AddonGenerator.Variant> tags = generator.variants().stream().filter(v -> v.role().equals("tag")).toList();
        assertEquals(2, tags.size());
        for (AddonGenerator.Variant variant : tags) {
            assertEquals("block", variant.kind());
            JsonObject components = variant.definition().getAsJsonObject("minecraft:block").getAsJsonObject("components");
            assertTrue(components.has("tag:paletter:tag_a"), components.toString());
            assertTrue(components.keySet().stream().noneMatch(key -> key.equals("minecraft:tag")), components.toString());
        }
        assertEquals(1, tags.get(0).value() == null ? tags.get(0).component().getAsJsonObject().size() : 0);
        assertEquals(AddonGenerator.BLOCK_TAGS.size(), tags.get(1).component().getAsJsonObject().size());
    }
}
