package org.geysermc.paletter.recipes;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.ContainerMixData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.CraftingDataType;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.PotionMixData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.RecipeUnlockingRequirement;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.MultiRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.ShapedRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.ShapelessRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.SmithingTransformRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.SmithingTrimRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ComplexAliasDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.DefaultDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemDescriptorWithCount;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemTagDescriptor;
import org.geysermc.paletter.Lookup;
import org.geysermc.paletter.block.BlockRegistry;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockItemType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Recipes to Cloudburst's recipe data for one palette. A method returns null where the palette lacks an item, a block state
 * or the members of a tag: BDS drops such a recipe.
 */
final class RecipeEncoder {
    private final Palette palette;
    private final Map<String, List<String>> tags;

    RecipeEncoder(Palette palette) {
        this.palette = palette;
        this.tags = palette.itemTags();
    }

    @Nullable ShapedRecipeData shaped(Recipe.Shaped recipe, int netId) {
        List<ItemDescriptorWithCount> ingredients = ingredients(recipe.ingredients());
        List<ItemData> results = results(recipe.results());
        RecipeUnlockingRequirement unlock = unlock(recipe.unlock());
        if (ingredients == null || results == null || unlock == null) {
            return null;
        }
        return ShapedRecipeData.shaped(recipe.id(), recipe.width(), recipe.height(), ingredients, results, uuid(recipe.id(), recipe.station()),
            recipe.station(), recipe.priority(), netId, recipe.assumeSymmetry(), unlock);
    }

    @Nullable ShapelessRecipeData shapeless(Recipe.Shapeless recipe, int netId) {
        List<ItemDescriptorWithCount> ingredients = ingredients(recipe.ingredients());
        List<ItemData> results = results(recipe.results());
        RecipeUnlockingRequirement unlock = unlock(recipe.unlock());
        if (ingredients == null || results == null || unlock == null) {
            return null;
        }
        CraftingDataType type = recipe.shulkerBox() ? CraftingDataType.SHULKER_BOX : CraftingDataType.SHAPELESS;
        return ShapelessRecipeData.of(type, recipe.id(), ingredients, results, uuid(recipe.id(), recipe.station()), recipe.station(), recipe.priority(), netId, unlock);
    }

    static MultiRecipeData multi(Recipe.Multi recipe, int netId) {
        return MultiRecipeData.of(recipe.id(), netId);
    }

    @Nullable SmithingTransformRecipeData smithingTransform(Recipe.SmithingTransform recipe, int netId) {
        ItemDescriptorWithCount template = descriptor(recipe.template());
        ItemDescriptorWithCount base = descriptor(recipe.base());
        ItemDescriptorWithCount addition = descriptor(recipe.addition());
        ItemData result = result(recipe.result());
        if (template == null || base == null || addition == null || result == null) {
            return null;
        }
        return SmithingTransformRecipeData.of(recipe.id(), template, base, addition, result, recipe.station(), netId);
    }

    @Nullable SmithingTrimRecipeData smithingTrim(Recipe.SmithingTrim recipe, int netId) {
        ItemDescriptorWithCount template = descriptor(recipe.template());
        ItemDescriptorWithCount base = descriptor(recipe.base());
        ItemDescriptorWithCount addition = descriptor(recipe.addition());
        if (template == null || base == null || addition == null) {
            return null;
        }
        return SmithingTrimRecipeData.of(recipe.id(), base, addition, template, recipe.station(), netId);
    }

    @Nullable PotionMixData potionMix(PotionMix mix) {
        ItemDefinition input = definition(mix.input().type());
        ItemDefinition reagent = definition(mix.reagent().type());
        ItemDefinition output = definition(mix.output().type());
        if (input == null || reagent == null || output == null) {
            return null;
        }
        return new PotionMixData(input.getRuntimeId(), mix.input().aux(), reagent.getRuntimeId(), mix.reagent().aux(), output.getRuntimeId(), mix.output().aux());
    }

    @Nullable ContainerMixData containerMix(ContainerMix mix) {
        ItemDefinition input = definition(mix.input());
        ItemDefinition reagent = definition(mix.reagent());
        ItemDefinition output = definition(mix.output());
        if (input == null || reagent == null || output == null) {
            return null;
        }
        return new ContainerMixData(input.getRuntimeId(), reagent.getRuntimeId(), output.getRuntimeId());
    }

    /** The station is part of the UUID because BDS has furnace recipes of several stations under one id. */
    private static UUID uuid(String id, String station) {
        return UUID.nameUUIDFromBytes((id + "\n" + station).getBytes(StandardCharsets.UTF_8));
    }

    private @Nullable ItemDefinition definition(BedrockItemType type) {
        return palette.itemDefinition(type);
    }

    private @Nullable RecipeUnlockingRequirement unlock(Unlock unlock) {
        List<ItemDescriptorWithCount> ingredients = ingredients(unlock.ingredients());
        if (ingredients == null) {
            return null;
        }
        RecipeUnlockingRequirement requirement = new RecipeUnlockingRequirement(unlock.context());
        requirement.getIngredients().addAll(ingredients);
        return requirement;
    }

    private @Nullable List<ItemDescriptorWithCount> ingredients(List<Ingredient> ingredients) {
        List<ItemDescriptorWithCount> descriptors = new ArrayList<>(ingredients.size());
        for (Ingredient ingredient : ingredients) {
            ItemDescriptorWithCount descriptor = descriptor(ingredient);
            if (descriptor == null) {
                return null;
            }
            descriptors.add(descriptor);
        }
        return descriptors;
    }

    private @Nullable ItemDescriptorWithCount descriptor(Ingredient ingredient) {
        return switch (ingredient) {
            case Ingredient.Item item -> counted(item.item().type(), item.item().aux(), item.count());
            case Ingredient.AnyVariant any -> counted(any.type(), RecipeBundle.ANY_AUX, any.count());
            case Ingredient.Tag tag -> tags.getOrDefault(tag.tag(), List.of()).isEmpty() ? null
                : new ItemDescriptorWithCount(new ItemTagDescriptor(tag.tag()), tag.count());
            case Ingredient.Alias alias -> new ItemDescriptorWithCount(new ComplexAliasDescriptor(alias.name()), alias.count());
            case Ingredient.Empty empty -> ItemDescriptorWithCount.EMPTY;
        };
    }

    private @Nullable ItemDescriptorWithCount counted(BedrockItemType type, int aux, int count) {
        ItemDefinition definition = definition(type);
        return definition == null ? null : new ItemDescriptorWithCount(new DefaultDescriptor(definition, aux), count);
    }

    private @Nullable List<ItemData> results(List<Result> results) {
        List<ItemData> items = new ArrayList<>(results.size());
        for (Result result : results) {
            ItemData item = result(result);
            if (item == null) {
                return null;
            }
            items.add(item);
        }
        return items;
    }

    private @Nullable ItemData result(Result result) {
        ItemDefinition definition = definition(result.item().type());
        if (definition == null) {
            return null;
        }
        ItemData.Builder item = ItemData.builder().definition(definition).damage(result.item().aux()).count(result.count());
        if (!result.blockState().isEmpty()) {
            BlockDefinition block = block(result.blockState());
            if (block == null) {
                return null;
            }
            item.blockDefinition(block);
        }
        if (!result.tag().isEmpty()) {
            item.tag(result.tag());
        }
        return item.build();
    }

    /**
     * The block a result is sent with: the state exactly as this palette's version has it. A state that only a preview
     * has (a block added since the newest release) has no {@link BedrockBlockState}, the constants do not reach it: it is
     * found by its state, as long as it is in the data and not a stand-in for something the palette lacks.
     */
    private @Nullable BlockDefinition block(NbtMap state) {
        BedrockBlockState handle = palette.blockState(state, Lookup.EXACT);
        if (handle != null) {
            return palette.blockDefinition(handle);
        }
        BlockRegistry blocks = palette.blocks();
        int canonical = blocks.canonical().index(state);
        if (canonical == -1 || blocks.isMissing(canonical) || blocks.isFallback(canonical)) {
            return null;
        }
        return blocks.exact(state);
    }
}
