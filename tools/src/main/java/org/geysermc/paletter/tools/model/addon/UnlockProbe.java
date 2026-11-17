package org.geysermc.paletter.tools.model.addon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Probe recipes for how BDS sends {@code unlock}: one shapeless recipe per unlock form and per recipe format version, so
 * the crafting data of the world they are in shows what BDS makes of each. The add-on {@code unlock: AlwaysUnlocked} of
 * format {@code 1.20.10} was sent as {@code NONE} (docs/wire-notes.md), so the answer depends on the format, which a single
 * recipe cannot show. Shaped recipes have the same field and are not probed.
 * <p>
 * Everything comes from BDS, nothing is guessed:
 * <ul>
 *     <li>the formats are the {@code format_version}s BDS's own vanilla packs give {@code minecraft:recipe_shapeless} and
 *     {@code minecraft:recipe_shaped} ({@code behavior_packs/*}{@code /__brarchive/recipes.brarchive} of BDS 1.26.63.1); a format
 *     the BDS under test does not know is rejected, which its content log records;</li>
 *     <li>the item and tag forms are the ones those packs use: a list of {@code {"item"}} with and without {@code "data"}, and
 *     of {@code {"tag"}};</li>
 *     <li>the context forms are the object {@code {"context": ...}} with the names of the packs ({@code None},
 *     {@code PlayerInWater}) and the two more that BDS's own binary has as strings ({@code AlwaysUnlocked},
 *     {@code PlayerHasManyItems}).</li>
 * </ul>
 * The recipes are in the creative order probe pack ({@link FixedAddons#writeCreative}); {@code update} keeps the capture of them
 * (the recipes whose identifier starts with {@link #PREFIX}) in {@code data/bds/<build>/recipe-probe}.
 */
public final class UnlockProbe {
    /** The identifier prefix of every probe recipe. */
    public static final String PREFIX = "paletter:unlock_";
    /** The recipe formats BDS's vanilla packs use for shapeless and shaped recipes, 1.26.63.1. */
    public static final List<String> FORMATS = List.of("1.12", "1.14", "1.16", "1.19", "1.20.10", "1.20.30", "1.20.60", "1.21.30", "1.21.40",
        "1.21.50", "1.21.60", "1.21.80", "1.21.90", "1.21.100", "1.21.110", "1.26.0", "1.26.30", "1.26.50");
    /** The unlock forms by name, as JSON; {@code none} has no {@code unlock} key at all. */
    static final Map<String, String> FORMS = new LinkedHashMap<>();

    static {
        FORMS.put("none", null);
        FORMS.put("item", "[{\"item\": \"minecraft:stick\"}]");
        FORMS.put("item_data", "[{\"item\": \"minecraft:stick\", \"data\": 0}]");
        FORMS.put("tag", "[{\"tag\": \"minecraft:planks\"}]");
        FORMS.put("context_none", "{\"context\": \"None\"}");
        FORMS.put("context_always_unlocked", "{\"context\": \"AlwaysUnlocked\"}");
        FORMS.put("context_player_in_water", "{\"context\": \"PlayerInWater\"}");
        FORMS.put("context_player_has_many_items", "{\"context\": \"PlayerHasManyItems\"}");
    }

    private UnlockProbe() {
    }

    /** The probe recipes by identifier: one per form and format. */
    public static Map<String, JsonObject> recipes() {
        Map<String, JsonObject> recipes = new LinkedHashMap<>();
        for (String format : FORMATS) {
            FORMS.forEach((form, unlock) -> {
                String identifier = PREFIX + form + "_" + format.replace('.', '_');
                JsonObject description = new JsonObject();
                description.addProperty("identifier", identifier);
                JsonObject recipe = new JsonObject();
                recipe.add("description", description);
                recipe.add("tags", JsonParser.parseString("[\"crafting_table\"]"));
                recipe.add("ingredients", JsonParser.parseString("[{\"item\": \"minecraft:dirt\"}]"));
                recipe.add("result", JsonParser.parseString("{\"item\": \"minecraft:stick\"}"));
                if (unlock != null) {
                    recipe.add("unlock", JsonParser.parseString(unlock));
                }
                JsonObject file = new JsonObject();
                file.addProperty("format_version", format);
                file.add("minecraft:recipe_shapeless", recipe);
                recipes.put(identifier, file);
            });
        }
        return recipes;
    }

    /** Writes the recipes into {@code bp/recipes}, one file per recipe. */
    static void write(Path bp) throws IOException {
        for (Map.Entry<String, JsonObject> recipe : recipes().entrySet()) {
            FixedAddons.write(bp.resolve("recipes/" + recipe.getKey().substring("paletter:".length()) + ".json"), recipe.getValue());
        }
    }
}
