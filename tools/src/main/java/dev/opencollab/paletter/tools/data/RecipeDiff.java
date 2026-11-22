package dev.opencollab.paletter.tools.data;

import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.client.RecipeCapture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static dev.opencollab.paletter.tools.data.DiffText.*;

/** Two recipe captures, see {@link Diff#recipes}. */
final class RecipeDiff extends DiffBuilder {
    /**
     * Two recipe captures, whatever their list layout: per group (recipes, mixes, trims) the entries added, removed and
     * changed (with the changed fields), unlock changes, and a changed {@code cleanRecipes} flag. Lines start with the kind.
     */
    static Diff of(RecipeCapture.Recipes previous, RecipeCapture.Recipes captured) {
        // Entries that share an id are told apart by their tag in both captures alike
        Set<String> tagged = union(RecipeData.duplicateKeys(previous), RecipeData.duplicateKeys(captured));
        Map<String, Map<String, JsonObject>> before = RecipeData.normalize(previous, tagged);
        Map<String, Map<String, JsonObject>> after = RecipeData.normalize(captured, tagged);
        RecipeDiff recipes = new RecipeDiff();
        Map<String, List<String>> added = new LinkedHashMap<>();
        Map<String, List<String>> removed = new LinkedHashMap<>();
        Map<String, List<String>> changed = new LinkedHashMap<>();
        List<String> unlock = new ArrayList<>();
        for (String kind : new TreeSet<>(union(before.keySet(), after.keySet()))) {
            Map<String, JsonObject> x = before.getOrDefault(kind, Map.of());
            Map<String, JsonObject> y = after.getOrDefault(kind, Map.of());
            String group = RecipeData.MIXES.contains(kind) ? "mixes" : kind.startsWith(RecipeData.TRIM_PREFIX) ? "trim" : "recipes";
            for (String key : y.keySet()) {
                if (!x.containsKey(key)) {
                    added.computeIfAbsent(group, g -> new ArrayList<>()).add(kind + " " + key);
                }
            }
            for (String key : x.keySet()) {
                if (!y.containsKey(key)) {
                    removed.computeIfAbsent(group, g -> new ArrayList<>()).add(kind + " " + key);
                }
            }
            for (String key : x.keySet()) {
                JsonObject a = x.get(key);
                JsonObject b = y.get(key);
                if (b == null || a.equals(b)) {
                    continue;
                }
                List<String> fields = new ArrayList<>();
                for (String field : new TreeSet<>(union(a.keySet(), b.keySet()))) {
                    if (!Objects.equals(a.get(field), b.get(field))) {
                        fields.add(field);
                    }
                }
                if (fields.remove("unlock")) {
                    unlock.add(kind + " " + key);
                }
                if (!fields.isEmpty()) {
                    changed.computeIfAbsent(group, g -> new ArrayList<>()).add(kind + " " + key + ": " + String.join(", ", fields));
                }
            }
        }
        if (!Objects.equals(previous.crafting().get("cleanRecipes"), captured.crafting().get("cleanRecipes"))) {
            changed.computeIfAbsent("recipes", g -> new ArrayList<>()).add("cleanRecipes: " + previous.crafting().get("cleanRecipes")
                + " -> " + captured.crafting().get("cleanRecipes"));
        }
        for (String group : List.of("recipes", "mixes", "trim")) {
            recipes.putIfAny(group + " added", added.getOrDefault(group, List.of()));
            recipes.putIfAny(group + " removed", removed.getOrDefault(group, List.of()));
            recipes.putIfAny(group + " changed", changed.getOrDefault(group, List.of()));
        }
        recipes.putIfAny("recipe unlock changes", unlock);
        return recipes.diff;
    }
}
