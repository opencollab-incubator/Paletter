package org.geysermc.paletter.tools.client;

import com.google.gson.JsonObject;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.TrimMaterial;
import org.cloudburstmc.protocol.bedrock.data.TrimPattern;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.ContainerMixData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.PotionMixData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.RecipeUnlockingRequirement;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.MultiRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.ShapedRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.ShapelessRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.DefaultDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemDescriptorWithCount;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemTagDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.MolangDescriptor;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.TrimDataPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A small synthetic crafting data packet written and read back: identifiers instead of runtime ids, raw fields kept. */
class RecipeCaptureTest {
    private static ItemDefinition definition(String name, int id) {
        return new SimpleItemDefinition(name, id, ItemVersion.DATA_DRIVEN, false, null);
    }

    @Test
    void writesAndReadsBack(@TempDir Path directory) throws IOException {
        ItemDefinition plank = definition("minecraft:oak_planks", 5);
        ItemDefinition stick = definition("minecraft:stick", 6);
        ItemDefinition table = definition("minecraft:crafting_table", 7);
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CraftingDataPacket crafting = new CraftingDataPacket();
        crafting.setCleanRecipes(true);
        crafting.getShapedData().add(ShapedRecipeData.shaped("minecraft:table", 2, 1,
            List.of(new ItemDescriptorWithCount(new DefaultDescriptor(plank, 32767), 1),
                new ItemDescriptorWithCount(new ItemTagDescriptor("minecraft:planks"), 1)),
            List.of(ItemData.builder().definition(table).count(1).blockDefinition(new CaptureClient.RawBlock(42)).build()),
            uuid, "crafting_table", 50, 9, true, RecipeUnlockingRequirement.INVALID));
        crafting.getShapelessData().add(ShapelessRecipeData.shapeless("minecraft:sticks",
            List.of(new ItemDescriptorWithCount(new MolangDescriptor("query.is_planks", 3), 2)),
            List.of(ItemData.builder().definition(stick).count(4).damage(1).build()), uuid, "crafting_table", 0, 10,
            RecipeUnlockingRequirement.INVALID));
        crafting.getMultiData().add(MultiRecipeData.of(uuid, 11));
        crafting.getPotionMixData().add(new PotionMixData(5, 1, 6, 0, 7, 2));
        crafting.getContainerMixData().add(new ContainerMixData(5, 6, 7));
        TrimDataPacket trim = new TrimDataPacket();
        trim.getPatterns().add(new TrimPattern("minecraft:coast_armor_trim_smithing_template", "coast"));
        trim.getMaterials().add(new TrimMaterial("amethyst", "§u", "minecraft:amethyst_shard"));

        Map<Integer, String> names = Map.of(5, plank.getIdentifier(), 6, stick.getIdentifier(), 7, table.getIdentifier());
        NbtMap state = NbtMap.builder().putString("name", "minecraft:crafting_table").build();
        new RecipeCapture(names, hash -> hash == 42 ? state : null).write(directory, crafting, trim, "1.0.0.0");

        RecipeCapture.Recipes recipes = CapturedData.recipes(directory);
        assertEquals("_generated", recipes.crafting().keySet().iterator().next());
        assertTrue(recipes.crafting().get("_generated").getAsString().contains("Do not edit"));
        assertTrue(recipes.crafting().get("cleanRecipes").getAsBoolean());
        assertEquals(1, recipes.count("shaped"));
        assertEquals(1, recipes.count("shapeless"));
        assertEquals(1, recipes.count("multi"));
        assertEquals(0, recipes.count("shulker_box"));
        JsonObject shaped = recipes.list("shaped").get(0).getAsJsonObject();
        JsonObject first = shaped.getAsJsonArray("ingredients").get(0).getAsJsonObject();
        assertEquals("minecraft:oak_planks", first.get("id").getAsString());
        assertEquals(32767, first.get("aux").getAsInt());
        assertEquals("item_tag", shaped.getAsJsonArray("ingredients").get(1).getAsJsonObject().get("kind").getAsString());
        JsonObject result = shaped.getAsJsonArray("results").get(0).getAsJsonObject();
        assertEquals("minecraft:crafting_table", result.get("id").getAsString());
        assertTrue(result.has("block_state_b64"));
        assertEquals(9, shaped.get("netId").getAsInt());
        assertTrue(shaped.get("assumeSymmetry").getAsBoolean());
        assertEquals("none", shaped.getAsJsonObject("unlock").get("context").getAsString());
        JsonObject molang = recipes.list("shapeless").get(0).getAsJsonObject().getAsJsonArray("ingredients").get(0).getAsJsonObject();
        assertEquals("molang", molang.get("kind").getAsString());
        assertEquals("query.is_planks", molang.get("expression").getAsString());
        assertEquals(2, molang.get("count").getAsInt());
        assertEquals(uuid.toString(), recipes.list("multi").get(0).getAsJsonObject().get("uuid").getAsString());
        JsonObject potion = recipes.list("potion_mix").get(0).getAsJsonObject();
        assertEquals("minecraft:stick", potion.getAsJsonObject("reagent").get("id").getAsString());
        assertEquals(2, potion.getAsJsonObject("output").get("aux").getAsInt());
        assertEquals("minecraft:crafting_table",
            recipes.list("container_mix").get(0).getAsJsonObject().getAsJsonObject("output").get("id").getAsString());
        assertEquals(1, recipes.trim().getAsJsonArray("patterns").size());
        assertEquals("amethyst", recipes.trim().getAsJsonArray("materials").get(0).getAsJsonObject().get("materialId").getAsString());
        assertTrue(recipes.trim().get("_generated").getAsString().contains("Do not edit"));
    }
}
