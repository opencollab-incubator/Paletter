package org.geysermc.paletter.recipes;

import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.DefaultDescriptor;
import org.geysermc.paletter.Lookup;
import org.geysermc.paletter.PaletteLayer;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.component.Geometry;
import org.geysermc.paletter.block.component.MaterialInstances;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.component.Icon;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.RecipeUnlockingRequirement.UnlockingContext;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItems;
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
        Result result = new Result(BedrockItems.TORCH.variant(), 4, NbtMap.EMPTY, NbtMap.EMPTY);
        return new Recipe.Shapeless(id, List.of(ingredient), List.of(result), RecipeStations.CRAFTING_TABLE, 0, false, Unlock.of(UnlockingContext.NONE));
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
        Recipe.Shapeless recipe = new Recipe.Shapeless("test:block", List.of(new Ingredient.AnyVariant(BedrockItems.STICK, 1)),
            List.of(new Result(BedrockItems.TORCH.variant(), 1, state, NbtMap.EMPTY)), RecipeStations.CRAFTING_TABLE, 0, false, Unlock.of(UnlockingContext.NONE));
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
            .component(MaterialInstances.builder().all(MaterialInstances.Instance.builder().texture("lamp").build()).build())
            .menuCategory(CreativeCategory.CONSTRUCTION)
            .build();
        Palette palette = PALETTE.extend(PaletteLayer.builder("content").item(ruby).block(lamp).build());
        Result lampResult = new Result(BedrockItemType.fromIdentifier("example:lamp").variant(), 1, lamp.defaultState(), NbtMap.EMPTY);
        Recipe.Shapeless recipe = new Recipe.Shapeless("test:lamp", List.of(new Ingredient.AnyVariant(BedrockItemType.fromIdentifier("example:ruby"), 1)),
            List.of(lampResult), RecipeStations.CRAFTING_TABLE, 0, false, Unlock.of(UnlockingContext.NONE));

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
