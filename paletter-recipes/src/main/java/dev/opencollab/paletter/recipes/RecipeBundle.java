package dev.opencollab.paletter.recipes;

import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.RecipeUnlockingRequirement.UnlockingContext;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The bundled recipe file ({@code /paletter/recipes/<data version>.nbt}): a gzipped big endian NBT compound, written by the
 * generator ({@code recipes-import}, see docs/data-pipeline.md) from the BDS captures: identifiers and aux values as BDS
 * sent them, no runtime ids, net ids or random UUIDs.
 * <pre>
 * format: int                        {@value #FORMAT}
 * _generated: string
 * recipes: [recipe]                  in the order BDS sends them
 *     recipe: {type: shaped|shapeless|shulker_box|multi|smithing_transform|smithing_trim, ...}
 *         shaped:             id, width, height, ingredients: [ingredient], results: [result], station, priority, symmetry: boolean, unlock
 *         shapeless, shulker_box: id, ingredients, results, station, priority, unlock
 *         multi:              id (the UUID)
 *         smithing_transform: id, template, base, addition (ingredient), result, station
 *         smithing_trim:      id, template, base, addition, station
 *     ingredient: {item, aux, count} | {tag, count} | {alias, count} | {} (empty)       aux 32767: any
 *     result: {id, aux, count, block?: block state, nbt?: item tag}
 *     unlock: {context: name of the UnlockingContext, ingredients: [ingredient]}
 * potion_mixes: [{input, reagent, output}]     each {id, aux}
 * container_mixes: [{input, reagent, output}]  each an item identifier
 * trim_patterns: [{id, item}], trim_materials: [{id, color, item}]      kept in the bundle, not read (no API for them)
 * </pre>
 */
final class RecipeBundle {
    static final int FORMAT = 1;
    /** The aux value of an ingredient that accepts every variant. */
    static final int ANY_AUX = 32767;

    private RecipeBundle() {
    }

    static VanillaRecipes read(DataVersion version, InputStream stream) throws IOException {
        NbtMap root;
        try (NBTInputStream input = NbtUtils.createGZIPReader(stream)) {
            root = (NbtMap) input.readTag();
        }
        if (root.getInt("format", -1) != FORMAT) {
            throw new IOException("Unsupported recipe bundle format " + root.getInt("format", -1) + ", expected " + FORMAT);
        }
        List<Recipe> recipes = new ArrayList<>();
        for (NbtMap recipe : root.getList("recipes", NbtType.COMPOUND)) {
            recipes.add(recipe(recipe));
        }
        List<PotionMix> potionMixes = new ArrayList<>();
        for (NbtMap mix : root.getList("potion_mixes", NbtType.COMPOUND)) {
            potionMixes.add(new PotionMix(variant(mix.getCompound("input")), variant(mix.getCompound("reagent")), variant(mix.getCompound("output"))));
        }
        List<ContainerMix> containerMixes = new ArrayList<>();
        for (NbtMap mix : root.getList("container_mixes", NbtType.COMPOUND)) {
            containerMixes.add(new ContainerMix(type(mix.getString("input")), type(mix.getString("reagent")), type(mix.getString("output"))));
        }
        return new VanillaRecipes(version, recipes, potionMixes, containerMixes);
    }

    private static Recipe recipe(NbtMap recipe) throws IOException {
        String type = recipe.getString("type");
        return switch (type) {
            case "shaped" -> Recipe.Shaped.builder().id(recipe.getString("id")).grid(recipe.getInt("width"), recipe.getInt("height"))
                .ingredients(ingredients(recipe, "ingredients")).results(results(recipe)).station(recipe.getString("station"))
                .priority(recipe.getInt("priority")).assumeSymmetry(recipe.getBoolean("symmetry")).unlock(unlock(recipe.getCompound("unlock"))).build();
            case "shapeless", "shulker_box" -> Recipe.Shapeless.builder().id(recipe.getString("id")).ingredients(ingredients(recipe, "ingredients"))
                .results(results(recipe)).station(recipe.getString("station")).priority(recipe.getInt("priority")).shulkerBox(type.equals("shulker_box"))
                .unlock(unlock(recipe.getCompound("unlock"))).build();
            case "multi" -> Recipe.Multi.of(UUID.fromString(recipe.getString("id")));
            case "smithing_transform" -> Recipe.SmithingTransform.builder().id(recipe.getString("id")).template(ingredient(recipe.getCompound("template")))
                .base(ingredient(recipe.getCompound("base"))).addition(ingredient(recipe.getCompound("addition")))
                .result(result(recipe.getCompound("result"))).station(recipe.getString("station")).build();
            case "smithing_trim" -> Recipe.SmithingTrim.builder().id(recipe.getString("id")).template(ingredient(recipe.getCompound("template")))
                .base(ingredient(recipe.getCompound("base"))).addition(ingredient(recipe.getCompound("addition"))).station(recipe.getString("station")).build();
            default -> throw new IOException("Unknown recipe type " + type + " in the recipe bundle");
        };
    }

    private static Unlock unlock(NbtMap unlock) {
        return Unlock.read(UnlockingContext.valueOf(unlock.getString("context").toUpperCase(Locale.ROOT)), ingredients(unlock, "ingredients"));
    }

    private static List<Ingredient> ingredients(NbtMap parent, String key) {
        List<Ingredient> ingredients = new ArrayList<>();
        for (NbtMap ingredient : parent.getList(key, NbtType.COMPOUND)) {
            ingredients.add(ingredient(ingredient));
        }
        return ingredients;
    }

    private static Ingredient ingredient(NbtMap ingredient) {
        int count = ingredient.getInt("count");
        if (ingredient.containsKey("item")) {
            int aux = ingredient.getInt("aux");
            return aux == ANY_AUX ? new Ingredient.AnyVariant(type(ingredient.getString("item")), count)
                : new Ingredient.Item(BedrockItemVariant.fromIdentifier(ingredient.getString("item"), aux), count);
        }
        if (ingredient.containsKey("tag")) {
            return new Ingredient.Tag(ingredient.getString("tag"), count);
        }
        if (ingredient.containsKey("alias")) {
            return new Ingredient.Alias(ingredient.getString("alias"), count);
        }
        return new Ingredient.Empty();
    }

    private static List<Result> results(NbtMap recipe) {
        List<Result> results = new ArrayList<>();
        for (NbtMap result : recipe.getList("results", NbtType.COMPOUND)) {
            results.add(result(result));
        }
        return results;
    }

    private static Result result(NbtMap result) {
        return Result.builder().item(variant(result)).count(result.getInt("count")).blockState(result.getCompound("block", NbtMap.EMPTY))
            .tag(result.getCompound("nbt", NbtMap.EMPTY)).build();
    }

    /** The variant of an {@code {id, aux}} compound. */
    private static BedrockItemVariant variant(NbtMap item) {
        return BedrockItemVariant.fromIdentifier(item.getString("id"), item.getInt("aux"));
    }

    private static BedrockItemType type(String identifier) {
        return BedrockItemType.fromIdentifier(identifier);
    }
}
