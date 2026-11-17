package org.geysermc.paletter.recipes;

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
}
