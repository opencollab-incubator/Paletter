package dev.opencollab.paletter.recipes;

import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.DefaultDescriptor;
import dev.opencollab.paletter.Lookup;
import dev.opencollab.paletter.PaletteLayer;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.component.Geometry;
import dev.opencollab.paletter.block.component.MaterialInstances;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.component.Icon;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.RecipeUnlockingRequirement.UnlockingContext;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the encoder does with missing content, net ids and UUIDs. (Its output against BDS is checked by the tools' CraftingDataConformanceTest.) */
class CraftingDataTest {
    private static final DataVersion VERSION = VanillaRecipes.versions().getLast();
    private static final Palette PALETTE = Paletter.builder().build().palette(VERSION.protocolVersion());

    private static Recipe.Shapeless shapeless(String id, Ingredient ingredient) {
        Result result = Result.builder().item(BedrockItems.TORCH.variant()).count(4).build();
        return Recipe.Shapeless.builder().id(id).ingredients(List.of(ingredient)).results(List.of(result)).station(RecipeStations.CRAFTING_TABLE).build();
    }

    @Test
    void missingContentDropsTheRecipeAndNetIdsSkipIt() {
        Recipe good = shapeless("test:good", new Ingredient.AnyVariant(BedrockItems.STICK, 1));
        Recipe missingItem = shapeless("test:item", new Ingredient.AnyVariant(BedrockItemType.fromIdentifier("test:missing"), 1));
        Recipe emptyTag = shapeless("test:tag", new Ingredient.Tag("test:no_such_tag", 1));
        Recipe later = shapeless("test:later", new Ingredient.Item(BedrockItems.COAL.variant(), 1));

        CraftingData data = CraftingData.builder(PALETTE).recipes(List.of(good, missingItem, emptyTag, later)).build();

        assertEquals(List.of(missingItem, emptyTag), data.dropped());
        assertEquals(1, data.netId(good));
        assertEquals(-1, data.netId(missingItem));
        assertEquals(-1, data.netId(emptyTag));
        assertEquals(2, data.netId(later));
        CraftingDataPacket packet = new CraftingDataPacket();
        data.applyTo(packet);
        assertEquals(2, packet.getShapelessData().size());
        assertTrue(packet.isCleanRecipes());
    }

    @Test
    void mixesWithMissingItemsAreReported() {
        BedrockItemType missing = BedrockItemType.fromIdentifier("test:missing");
        PotionMix badPotion = new PotionMix(missing.variant(), BedrockItems.STICK.variant(), BedrockItems.STICK.variant());
        PotionMix goodPotion = new PotionMix(BedrockItems.STICK.variant(), BedrockItems.COAL.variant(), BedrockItems.TORCH.variant());
        ContainerMix badContainer = new ContainerMix(BedrockItems.STICK, missing, BedrockItems.COAL);
        ContainerMix goodContainer = new ContainerMix(BedrockItems.STICK, BedrockItems.COAL, BedrockItems.TORCH);

        CraftingData data = CraftingData.builder(PALETTE).potionMixes(List.of(badPotion, goodPotion)).containerMixes(List.of(badContainer, goodContainer)).build();

        assertEquals(List.of(badPotion), data.droppedPotionMixes());
        assertEquals(List.of(badContainer), data.droppedContainerMixes());
        CraftingDataPacket packet = new CraftingDataPacket();
        data.applyTo(packet);
        assertEquals(1, packet.getPotionMixData().size());
        assertEquals(1, packet.getContainerMixData().size());
    }

    @Test
    void uuidsAreTheSameOnEveryRun() {
        List<Recipe> recipes = List.of(shapeless("test:a", new Ingredient.AnyVariant(BedrockItems.STICK, 1)));
        CraftingDataPacket first = new CraftingDataPacket();
        CraftingDataPacket second = new CraftingDataPacket();
        CraftingData.builder(PALETTE).recipes(recipes).build().applyTo(first);
        CraftingData.builder(PALETTE).recipes(recipes).build().applyTo(second);
        assertEquals(first.getShapelessData().getFirst().getUuid(), second.getShapelessData().getFirst().getUuid());
    }

    @Test
    void multiRecipesKeepTheirUuid() {
        Recipe.Multi multi = VanillaRecipes.of(VERSION).recipes().stream().filter(recipe -> recipe instanceof Recipe.Multi)
            .map(recipe -> (Recipe.Multi) recipe).findFirst().orElseThrow();
        CraftingDataPacket packet = new CraftingDataPacket();
        CraftingData.builder(PALETTE).recipes(List.of(multi)).build().applyTo(packet);
        assertEquals(multi.id(), packet.getMultiData().getFirst().getUuid());
    }

    @Test
    void aMissingResultBlockStateDropsTheRecipe() {
        NbtMap state = NbtMap.builder().putString("name", "test:no_such_block").putCompound("states", NbtMap.EMPTY).build();
        Recipe.Shapeless recipe = Recipe.Shapeless.builder().id("test:block").ingredients(List.of(new Ingredient.AnyVariant(BedrockItems.STICK, 1)))
            .results(List.of(Result.builder().item(BedrockItems.TORCH.variant()).blockState(state).build())).station(RecipeStations.CRAFTING_TABLE).build();
        CraftingData data = CraftingData.builder(PALETTE).recipes(List.of(recipe)).build();
        assertEquals(List.of(recipe), data.dropped());
        assertEquals(-1, data.netId(recipe));
    }

    @Test
    void anEqualRecipeAddedTwiceIsEncodedOnce() {
        Recipe good = shapeless("test:good", new Ingredient.AnyVariant(BedrockItems.STICK, 1));
        Recipe bad = shapeless("test:bad", new Ingredient.Tag("test:no_such_tag", 1));
        Recipe other = shapeless("test:other", new Ingredient.AnyVariant(BedrockItems.COAL, 1));
        CraftingData data = CraftingData.builder(PALETTE).recipes(List.of(good, bad, good, bad, other)).build();
        assertEquals(List.of(bad), data.dropped());
        assertEquals(1, data.netId(good));
        assertEquals(2, data.netId(other));
        CraftingDataPacket packet = new CraftingDataPacket();
        data.applyTo(packet);
        assertEquals(2, packet.getShapelessData().size());
    }

    @Test
    void palettesBelowProtocol2168GetTheSingleRecipeList() {
        DataVersion oldest = VanillaRecipes.versions().getFirst();
        assertTrue(oldest.protocolVersion() < 2168, oldest.toString());
        Palette old = Paletter.builder().build().palette(oldest.protocolVersion());
        Recipe shapeless = shapeless("test:a", new Ingredient.AnyVariant(BedrockItems.STICK, 1));
        Recipe.Multi multi = VanillaRecipes.of(oldest).recipes().stream().filter(recipe -> recipe instanceof Recipe.Multi)
            .map(recipe -> (Recipe.Multi) recipe).findFirst().orElseThrow();

        CraftingDataPacket packet = new CraftingDataPacket();
        CraftingData.builder(old).recipes(List.of(shapeless, multi)).build().applyTo(packet);
        assertEquals(2, packet.getCraftingData().size());
        assertEquals(1, packet.getShapelessData().size(), "the lists of their own are filled too");

        CraftingDataPacket current = new CraftingDataPacket();
        CraftingData.builder(PALETTE).recipes(List.of(shapeless, multi)).build().applyTo(current);
        assertTrue(current.getCraftingData().isEmpty());
    }

    @Test
    void itemsAndBlocksOfALayerResolve() {
        CustomItem ruby = CustomItem.builder("example:ruby").component(Icon.of("ruby")).menuCategory(CreativeCategory.ITEMS).build();
        CustomBlock lamp = CustomBlock.builder("example:lamp")
            .component(Geometry.of("minecraft:geometry.full_block"))
            .component(MaterialInstances.builder().all(MaterialInstances.InstanceOrAlias.define(MaterialInstances.Instance.builder().texture("lamp").build())).build())
            .menuCategory(CreativeCategory.CONSTRUCTION)
            .build();
        Palette palette = PALETTE.extend(PaletteLayer.builder("content").item(ruby).block(lamp).build());
        Result lampResult = Result.builder().item(BedrockItemType.fromIdentifier("example:lamp").variant()).blockState(lamp.defaultState()).build();
        Recipe.Shapeless recipe = Recipe.Shapeless.builder().id("test:lamp")
            .ingredients(List.of(new Ingredient.AnyVariant(BedrockItemType.fromIdentifier("example:ruby"), 1))).results(List.of(lampResult))
            .station(RecipeStations.CRAFTING_TABLE).build();

        CraftingData data = CraftingData.builder(palette).recipes(List.of(recipe)).build();
        assertEquals(List.of(), data.dropped());
        CraftingDataPacket packet = new CraftingDataPacket();
        data.applyTo(packet);
        var encoded = packet.getShapelessData().getFirst();
        DefaultDescriptor ingredient = assertInstanceOf(DefaultDescriptor.class, encoded.getIngredients().getFirst().getDescriptor());
        assertEquals("example:ruby", ingredient.getItemId().getIdentifier());
        BlockDefinition block = encoded.getResults().getFirst().getBlockDefinition();
        assertEquals(palette.runtimeId(palette.blockState(lamp.defaultState(), Lookup.EXACT)), block.getRuntimeId());

        // Without the layer the same recipe is dropped
        assertFalse(CraftingData.builder(PALETTE).recipes(List.of(recipe)).build().dropped().isEmpty());
    }
}
