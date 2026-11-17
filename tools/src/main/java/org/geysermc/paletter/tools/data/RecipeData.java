package org.geysermc.paletter.tools.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.client.RecipeCapture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;

/**
 * Recipe captures in one comparable form, and the assumptions the recipe data rests on.
 * <p>
 * {@link #normalize}: every recipe by kind and key whatever the list layout of its protocol (builds before protocol 2168
 * send one {@code crafting} list with a type per entry), without net ids (BDS sequence numbers) and, except for the fixed
 * multi recipes, without the UUIDs (random per server start). Trim patterns and materials are kinds as well.
 */
public final class RecipeData {
    public static final String TRIM_PATTERN = "trim_pattern";
    public static final String TRIM_MATERIAL = "trim_material";
    /** Kinds that are not recipes of a station: their items are not part of the recipe checks. */
    static final Set<String> MIXES = Set.of("potion_mix", "container_mix", "material_reducer");

    private RecipeData() {
    }

    /** Recipes by kind, then by key (sorted): shaped, shapeless, multi, potion_mix, trim_pattern, ... */
    public static Map<String, Map<String, JsonObject>> normalize(RecipeCapture.Recipes recipes) {
        Map<String, Map<String, JsonObject>> kinds = new TreeMap<>();
        each(recipes, (kind, entry) -> {
            JsonObject copy = entry.deepCopy();
            copy.remove("type");
            copy.remove("netId");
            if (!kind.equals("multi")) {
                copy.remove("uuid");
            }
            Map<String, JsonObject> byKey = kinds.computeIfAbsent(kind, k -> new TreeMap<>());
            String key = key(kind, copy);
            // The same key twice (wire order within a list): number them
            for (int n = 2; byKey.containsKey(key); n++) {
                key = key(kind, copy) + "#" + n;
            }
            byKey.put(key, copy);
        });
        return kinds;
    }

    /** Every entry of the capture with its kind; the pre-2168 {@code crafting} list by the type of each entry. */
    public static void each(RecipeCapture.Recipes recipes, BiConsumer<String, JsonObject> action) {
        for (String list : RecipeCapture.LISTS) {
            JsonArray entries = recipes.crafting().getAsJsonArray(list);
            for (JsonElement entry : entries == null ? new JsonArray() : entries) {
                JsonObject object = entry.getAsJsonObject();
                action.accept(list.equals("crafting") ? object.get("type").getAsString() : list, object);
            }
        }
        if (recipes.trim() != null) {
            for (JsonElement pattern : recipes.trim().getAsJsonArray("patterns")) {
                action.accept(TRIM_PATTERN, pattern.getAsJsonObject());
            }
            for (JsonElement material : recipes.trim().getAsJsonArray("materials")) {
                action.accept(TRIM_MATERIAL, material.getAsJsonObject());
            }
        }
    }

    private static String key(String kind, JsonObject entry) {
        for (String name : List.of("id", "patternId", "materialId", "uuid")) {
            if (entry.has(name)) {
                return entry.get(name).getAsString();
            }
        }
        // Mixes, reducers and furnace recipes: their items
        List<String> parts = new ArrayList<>();
        for (String name : List.of("input", "reagent", "output")) {
            if (entry.has(name)) {
                JsonObject item = entry.getAsJsonObject(name);
                parts.add(item.get("id").getAsString() + ":" + item.get("aux").getAsInt());
            }
        }
        return String.join(" + ", parts) + (entry.has("tag") ? " (" + entry.get("tag").getAsString() + ")" : "");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Assumption checks: each returns the problems, none if the assumption holds

    /** Every ingredient and result item of a recipe is an item of the palette. */
    public static List<String> missingItems(RecipeCapture.Recipes recipes, Set<String> paletteItems) {
        Set<String> missing = new TreeSet<>();
        each(recipes, (kind, entry) -> references(kind, entry, (item, tag) -> {
            if (item != null && !item.equals("minecraft:air") && !paletteItems.contains(item)) {
                missing.add(item + " (" + kind + " " + describe(kind, entry) + ")");
            }
        }));
        return List.copyOf(missing);
    }

    /** Every tag of an ingredient has at least one member. */
    public static List<String> emptyTags(RecipeCapture.Recipes recipes, Map<String, List<String>> tags) {
        Set<String> empty = new TreeSet<>();
        each(recipes, (kind, entry) -> references(kind, entry, (item, tag) -> {
            if (tag != null && tags.getOrDefault(tag, List.of()).isEmpty()) {
                empty.add(tag + " (" + kind + " " + describe(kind, entry) + ")");
            }
        }));
        return List.copyOf(empty);
    }

    /** The net ids of all recipes (and smithing) are exactly 1..n, each once. */
    public static List<String> netIdProblems(RecipeCapture.Recipes recipes) {
        List<Integer> ids = new ArrayList<>();
        each(recipes, (kind, entry) -> {
            if (entry.has("netId")) {
                ids.add(entry.get("netId").getAsInt());
            }
        });
        ids.sort(null);
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            if (ids.get(i) != i + 1) {
                problems.add("net ids are not 1.." + ids.size() + ": " + (i + 1) + " is followed by " + ids.get(i) + " in the sorted ids");
                break;
            }
        }
        return problems;
    }

    private static String describe(String kind, JsonObject entry) {
        return key(kind, entry);
    }

    /** The items and tags a recipe names (one of the two is null per call). */
    private static void references(String kind, JsonObject entry, BiConsumer<String, String> action) {
        if (!MIXES.contains(kind)) {
            entry.entrySet().forEach(member -> walk(member.getValue(), member.getKey(), action));
        }
    }

    private static void walk(JsonElement element, String member, BiConsumer<String, String> action) {
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> walk(child, member, action));
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            String kind = object.has("kind") ? object.get("kind").getAsString() : "";
            if (kind.equals("default")) {
                action.accept(object.get("id").getAsString(), null);
            } else if (kind.equals("item_tag")) {
                action.accept(null, object.get("tag").getAsString());
            } else if (kind.isEmpty() && object.has("id") && (member.equals("result") || member.equals("results") || member.equals("input"))) {
                action.accept(object.get("id").getAsString(), null);
            } else {
                object.entrySet().forEach(child -> walk(child.getValue(), child.getKey(), action));
            }
        }
    }
}
