package dev.opencollab.paletter.tools.model.addon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The unlock probe has every form at every format, once, and the creative order probe pack carries it. */
class UnlockProbeTest {
    @Test
    void everyFormAtEveryFormatOnce() {
        Map<String, JsonObject> recipes = UnlockProbe.recipes();
        assertEquals(UnlockProbe.FORMATS.size() * UnlockProbe.FORMS.size(), recipes.size());
        assertEquals(UnlockProbe.FORMATS.size(), new HashSet<>(UnlockProbe.FORMATS).size(), "a format twice");
        for (String format : UnlockProbe.FORMATS) {
            UnlockProbe.FORMS.forEach((form, unlock) -> {
                String identifier = UnlockProbe.PREFIX + form + "_" + format.replace('.', '_');
                JsonObject file = recipes.get(identifier);
                assertEquals(format, file.get("format_version").getAsString(), identifier);
                JsonObject recipe = file.getAsJsonObject("minecraft:recipe_shapeless");
                assertEquals(identifier, recipe.getAsJsonObject("description").get("identifier").getAsString());
                assertEquals(unlock == null, !recipe.has("unlock"), identifier);
                if (unlock != null) {
                    assertEquals(JsonParser.parseString(unlock), recipe.get("unlock"), identifier);
                }
            });
        }
    }

    @Test
    void theContextFormsAreTheOnesBdsKnows() {
        assertEquals(java.util.List.of("none", "item", "item_data", "tag", "context_none", "context_always_unlocked",
            "context_player_in_water", "context_player_has_many_items"), java.util.List.copyOf(UnlockProbe.FORMS.keySet()));
        assertTrue(UnlockProbe.FORMS.get("context_always_unlocked").contains("AlwaysUnlocked"));
    }

    @Test
    void theCreativeProbePackHasOneFilePerRecipe(@TempDir Path out) throws IOException {
        FixedAddons.writeCreative(out);
        Path recipes = out.resolve("behavior_pack/recipes");
        try (Stream<Path> files = Files.list(recipes)) {
            assertEquals(UnlockProbe.recipes().size(), files.count());
        }
        for (String identifier : UnlockProbe.recipes().keySet()) {
            Path file = recipes.resolve(identifier.substring("paletter:".length()) + ".json");
            assertTrue(Files.isRegularFile(file), file.toString());
            assertFalse(JsonParser.parseString(Files.readString(file)).getAsJsonObject().isEmpty());
        }
    }
}
