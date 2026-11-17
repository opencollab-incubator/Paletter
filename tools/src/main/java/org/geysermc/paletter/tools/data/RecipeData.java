package org.geysermc.paletter.tools.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.client.RecipeCapture;

import java.util.ArrayList;
import java.util.HashSet;
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
    public static final String TRIM_PREFIX = "trim_";
    public static final String TRIM_PATTERN = TRIM_PREFIX + "pattern";
    public static final String TRIM_MATERIAL = TRIM_PREFIX + "material";
    /** The mix and reducer kinds: items by {@code input}, {@code reagent} and {@code output}, no ingredients. */
    static final Set<String> MIXES = Set.of("potion_mix", "container_mix", "material_reducer");

    private RecipeData() {
    }

    /** Recipes by kind, then by key (sorted): shaped, shapeless, multi, potion_mix, trim_pattern, ... */
    public static Map<String, Map<String, JsonObject>> normalize(RecipeCapture.Recipes recipes) {
        return normalize(recipes, duplicateKeys(recipes));
    }

    /**
     * @param tagged the kind and key ({@code kind|key}) of entries that are told apart by their station tag ({@code id (tag)}),
     *               because two entries share an id (the furnace recipes of the smelting stations). Both captures of a
     *               diff must use the same set, so that an id that is unique in one of them still has the same key.
     */
    public static Map<String, Map<String, JsonObject>> normalize(RecipeCapture.Recipes recipes, Set<String> tagged) {
        Map<String, Map<String, JsonObject>> kinds = new TreeMap<>();
        each(recipes, (kind, entry) -> {
            JsonObject copy = entry.deepCopy();
            copy.remove("type");
            copy.remove("netId");
            if (!kind.equals("multi")) {
                copy.remove("uuid");
            }
            Map<String, JsonObject> byKey = kinds.computeIfAbsent(kind, k -> new TreeMap<>());
            String base = key(kind, copy);
            String key = tagged.contains(kind + "|" + base) && copy.has("tag") ? base + " (" + copy.get("tag").getAsString() + ")" : base;
            // Still the same key (wire order within a list): number them
            for (int n = 2; byKey.containsKey(key); n++) {
                key = base + "#" + n;
            }
            byKey.put(key, copy);
        });
        return kinds;
    }

    /** The {@code kind|key} of entries whose key another entry of the capture has as well. */
    public static Set<String> duplicateKeys(RecipeCapture.Recipes recipes) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new TreeSet<>();
        each(recipes, (kind, entry) -> {
            if (!seen.add(kind + "|" + key(kind, entry))) {
                duplicates.add(kind + "|" + key(kind, entry));
            }
        });
        return duplicates;
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
            for (String[] part : new String[][]{{"patterns", TRIM_PATTERN}, {"materials", TRIM_MATERIAL}}) {
                JsonArray entries = recipes.trim().getAsJsonArray(part[0]);
                for (JsonElement entry : entries == null ? new JsonArray() : entries) {
                    action.accept(part[1], entry.getAsJsonObject());
                }
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
                missing.add(item + " (" + kind + " " + key(kind, entry) + ")");
            }
        }));
        return List.copyOf(missing);
    }

    /** Every tag of an ingredient has at least one member. */
    public static List<String> emptyTags(RecipeCapture.Recipes recipes, Map<String, List<String>> tags) {
        Set<String> empty = new TreeSet<>();
        each(recipes, (kind, entry) -> references(kind, entry, (item, tag) -> {
            if (tag != null && tags.getOrDefault(tag, List.of()).isEmpty()) {
                empty.add(tag + " (" + kind + " " + key(kind, entry) + ")");
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

    /** A result with a block runtime id has a block state as well: the capture could not resolve it otherwise. */
    public static List<String> missingBlockStates(RecipeCapture.Recipes recipes) {
        List<String> missing = new ArrayList<>();
        each(recipes, (kind, entry) -> {
            for (String member : List.of("result", "results")) {
                JsonElement results = entry.get(member);
                if (results == null) {
                    continue;
                }
                for (JsonElement result : results.isJsonArray() ? results.getAsJsonArray() : List.of(results)) {
                    JsonObject item = result.getAsJsonObject();
                    if (item.has("block_runtime_id") && !item.has("block_state_b64")) {
                        missing.add(item.get("id").getAsString() + " block " + item.get("block_runtime_id").getAsString() + " (" + kind + " " + key(kind, entry) + ")");
                    }
                }
            }
        });
        return missing;
    }

    /** The items and tags a recipe names (one of the two is null per call). Mixes and reducers name items only. */
    private static void references(String kind, JsonObject entry, BiConsumer<String, String> action) {
        if (MIXES.contains(kind)) {
            for (String member : List.of("input", "reagent", "output")) {
                if (entry.has(member)) {
                    action.accept(entry.getAsJsonObject(member).get("id").getAsString(), null);
                }
            }
            if (entry.has("itemCounts")) {
                entry.getAsJsonArray("itemCounts").forEach(count -> action.accept(count.getAsJsonObject().get("id").getAsString(), null));
            }
        } else {
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
