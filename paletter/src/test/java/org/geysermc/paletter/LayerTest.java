package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.geysermc.paletter.block.BlockComponents;
import org.geysermc.paletter.block.BlockPalette;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.BlockResolution;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.PaletteBlock;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativeEntry;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.creative.CreativeStack;
import org.geysermc.paletter.creative.ItemMatcher;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.ItemComponents;
import org.geysermc.paletter.item.ItemVariant;
import org.geysermc.paletter.item.PaletteItem;
import org.geysermc.paletter.item.ResolvedItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayerTest {
    private static final Paletter PALETTER = Paletter.bundled();
    private static final int LATEST = 2225;
    private static final int OLDEST = 2168;

    /**
     * Roughly what Geyser would register for furnace minecarts: a custom item next to the hopper minecart.
     */
    private static PaletteLayer minecartLayer() {
        return PaletteLayer.builder("geyser")
            .item(CustomItem.builder("geysermc:furnace_minecart")
                .components(ItemComponents.builder()
                    .icon("minecart_furnace")
                    .displayName("item.minecartFurnace.name")
                    .maxStackSize(1)
                    .build())
                .creative(CreativePlacement.group(CreativeCategory.ITEMS, "itemGroup.name.minecart")
                    .after(ItemMatcher.id("minecraft:hopper_minecart")))
                .build())
            .build();
    }

    @Test
    void customItemsGetRuntimeIdsComponentsAndCreativePlacement() {
        Palette vanilla = PALETTER.vanilla(PALETTER.version(LATEST));
        Palette palette = PALETTER.palette(LATEST, minecartLayer());
        PaletteItem furnace = palette.items().getDefinition("geysermc:furnace_minecart");
        assertNotNull(furnace);
        assertTrue(furnace.isCustom());
        assertEquals(ItemVersion.DATA_DRIVEN, furnace.getVersion());
        int maxVanilla = vanilla.items().definitions().stream().mapToInt(PaletteItem::getRuntimeId).max().orElseThrow();
        assertEquals(maxVanilla + 1, furnace.getRuntimeId());

        NbtMap data = furnace.getComponentData();
        assertEquals("geysermc:furnace_minecart", data.getString("name"));
        assertEquals(furnace.getRuntimeId(), data.getInt("id"));
        NbtMap properties = data.getCompound("components").getCompound("item_properties");
        assertEquals(CreativeCategory.ITEMS.itemPropertyId(), properties.getInt("creative_category"));
        assertEquals("itemGroup.name.minecart", properties.getString("creative_group"));

        // Directly after the hopper minecart, inside the minecart group
        List<CreativeItemData> items = palette.creative().items();
        int hopper = indexOf(items, "minecraft:hopper_minecart");
        assertEquals("geysermc:furnace_minecart", items.get(hopper + 1).getItem().getDefinition().getIdentifier());
        CreativeEntry entry = palette.creative().entry(items.get(hopper + 1).getNetId());
        assertEquals("itemGroup.name.minecart", entry.group());
        assertEquals(vanilla.creative().size() + 1, palette.creative().size());
    }

    @Test
    void matchFindsCustomAndVanillaItems() {
        Palette palette = PALETTER.palette(LATEST, minecartLayer());
        ItemData furnace = ItemData.builder().definition(palette.items().getDefinition("geysermc:furnace_minecart")).count(1).build();
        ItemData hopper = ItemData.builder().definition(palette.items().getDefinition("minecraft:hopper_minecart")).count(1).build();
        int furnaceCanonical = palette.canonicalItem(ItemVariant.of("geysermc:furnace_minecart"));
        assertEquals(PALETTER.canonicalItemCount(), furnaceCanonical, "custom items are appended");
        assertEquals(furnaceCanonical, palette.match(furnace));
        assertEquals(PALETTER.canonicalItem(ItemVariant.of("minecraft:hopper_minecart")), palette.match(hopper));
        assertEquals(-1, PALETTER.canonicalItem(ItemVariant.of("geysermc:furnace_minecart")));
        // The same layer gives the same canonical ids in every version
        assertEquals(furnaceCanonical, PALETTER.palette(OLDEST, minecartLayer()).canonicalItem(ItemVariant.of("geysermc:furnace_minecart")));
    }

    @Test
    void damageVariantsMatchTheirCanonicalId() {
        Palette palette = PALETTER.palette(LATEST);
        PaletteItem potion = palette.items().getDefinition("minecraft:potion");
        int five = PALETTER.canonicalItem(ItemVariant.of("minecraft:potion", 5));
        int base = PALETTER.canonicalItem(ItemVariant.of("minecraft:potion"));
        assertTrue(five >= 0 && base >= 0 && five != base);
        assertEquals(five, palette.match(ItemData.builder().definition(potion).damage(5).count(1).build()));
        // A damage value that is no variant falls back to the base variant
        assertEquals(base, palette.match(ItemData.builder().definition(potion).damage(9999).count(1).build()));
        ResolvedItem resolved = palette.item(five);
        assertSame(potion, resolved.definition());
        assertEquals(5, resolved.damage());
    }

    @Test
    void sessionLayersShareUnchangedDefinitions() {
        Palette shared = PALETTER.palette(LATEST, minecartLayer());
        PaletteLayer session = PaletteLayer.builder("session")
            .item(CustomItem.builder("server:ruby").creative(CreativePlacement.loose(CreativeCategory.ITEMS)).build())
            .build();
        Palette sessionPalette = shared.extend(session);

        assertSame(shared.items().getDefinition("minecraft:apple"), sessionPalette.items().getDefinition("minecraft:apple"));
        assertSame(shared.items().getDefinition("geysermc:furnace_minecart"), sessionPalette.items().getDefinition("geysermc:furnace_minecart"));
        // No custom blocks: block states are shared too
        assertSame(shared.blocks().air(), sessionPalette.blocks().air());
        assertNotNull(sessionPalette.items().getDefinition("server:ruby"));
        assertNull(shared.items().getDefinition("server:ruby"));
        // No custom blocks or block fallbacks: the block palette and its canonical table are shared
        assertSame(shared.blocks(), sessionPalette.blocks());
        // Canonical ids of the shared palette stay valid; the session's custom item is appended
        int furnace = shared.canonicalItem(ItemVariant.of("geysermc:furnace_minecart"));
        assertEquals(furnace, sessionPalette.canonicalItem(ItemVariant.of("geysermc:furnace_minecart")));
        assertSame(shared.item(furnace).definition(), sessionPalette.item(furnace).definition());
        assertEquals(shared.canonicalItemCount(), sessionPalette.canonicalItem(ItemVariant.of("server:ruby")));
        // Extending again with the same layer returns the cached palette
        assertSame(sessionPalette, shared.extend(session));
    }

    @Test
    void sharedLayerStacksAreCachedPerPrefix() {
        PaletteLayer first = minecartLayer();
        PaletteLayer second = PaletteLayer.builder("second").build();
        Palette both = PALETTER.palette(LATEST, first, second);
        assertSame(both, PALETTER.palette(LATEST, first, second));
        assertSame(PALETTER.palette(LATEST, first), PALETTER.palette(LATEST, first));
        assertEquals(List.of(first, second), both.layers());
        // Layers are compared by identity: an equal but new layer is compiled again
        assertNotSame(PALETTER.palette(LATEST, first), PALETTER.palette(LATEST, minecartLayer()));
    }

    @Test
    void overridesChangeVanillaDefinitions() {
        PaletteLayer layer = PaletteLayer.builder("fixes")
            .overrideItem("minecraft:cake", item -> item.components(c -> c.maxStackSize(1)))
            .overrideItem("minecraft:firework_rocket", item -> item.dataDriven(c -> c.maxStackSize(64).throwable(true)))
            .overrideItem("minecraft:structure_block", item -> item.noBlock())
            .build();
        Palette vanilla = PALETTER.vanilla(PALETTER.version(LATEST));
        Palette palette = PALETTER.palette(LATEST, layer);

        PaletteItem cake = palette.items().getDefinition("minecraft:cake");
        assertNotSame(vanilla.items().getDefinition("minecraft:cake"), cake);
        assertEquals(vanilla.items().getDefinition("minecraft:cake").getRuntimeId(), cake.getRuntimeId());
        assertTrue(cake.isComponentBased());
        assertEquals(1, cake.getComponentData().getCompound("components").getCompound("item_properties").getInt("max_stack_size"));

        PaletteItem rocket = palette.items().getDefinition("minecraft:firework_rocket");
        assertEquals(ItemVersion.DATA_DRIVEN, rocket.getVersion());
        assertTrue(rocket.getComponentData().getCompound("components").containsKey("minecraft:throwable"));

        assertTrue(vanilla.items().isBlockItem(vanilla.items().getDefinition("minecraft:structure_block")));
        assertFalse(palette.items().isBlockItem(palette.items().getDefinition("minecraft:structure_block")));
        // Creative stacks use the new definitions
        int cakeIndex = indexOf(palette.creative().items(), "minecraft:cake");
        assertSame(cake, palette.creative().items().get(cakeIndex).getItem().getDefinition());
    }

    @Test
    void customBlocksAreSortedIntoThePaletteLikeTheClientDoes() {
        CustomBlock lamp = CustomBlock.builder("geyser:lamp")
            .property(BlockProperty.bool("geyser:lit"))
            .property(BlockProperty.ints("geyser:color", 0, 1, 2))
            .components(BlockComponents.builder().lightEmission(15).geometry("minecraft:geometry.full_block").build())
            .permutation("q.block_state('geyser:lit') == 1", BlockComponents.builder().lightEmission(15).build())
            .defaultState("geyser:color", 1)
            .creative(CreativePlacement.loose(CreativeCategory.ITEMS))
            .build();
        Palette vanilla = PALETTER.vanilla(PALETTER.version(LATEST));
        Palette palette = PALETTER.palette(LATEST, PaletteLayer.builder("blocks").block(lamp).build());
        BlockPalette blocks = palette.blocks();

        assertEquals(vanilla.blocks().size() + 6, blocks.size());
        assertEquals(6, blocks.states("geyser:lamp").size());
        for (int i = 1; i < blocks.size(); i++) {
            assertTrue(Long.compareUnsigned(BlockPalette.nameHash(blocks.byRuntimeId(i - 1).identifier()),
                BlockPalette.nameHash(blocks.byRuntimeId(i).identifier())) <= 0, "not sorted at " + i);
        }
        // Vanilla ids shifted, and can be translated
        assertTrue(blocks.isRemapped());
        for (int i = 0; i < vanilla.blocks().size(); i += 97) {
            assertEquals(vanilla.blocks().byRuntimeId(i).getState(), blocks.byRuntimeId(blocks.remapVanilla(i)).getState());
        }

        PaletteBlock defaultState = blocks.defaultState("geyser:lamp");
        assertEquals(1, defaultState.states().getInt("geyser:color"));
        assertEquals((byte) 0, defaultState.states().getByte("geyser:lit"));

        BlockPropertyData properties = blocks.blockProperties().stream().filter(p -> p.getName().equals("geyser:lamp")).findFirst().orElseThrow();
        assertEquals(BlockPalette.CUSTOM_BLOCK_ID_START, properties.getProperties().getCompound("vanilla_block_data").getInt("block_id"));
        assertEquals("items", properties.getProperties().getCompound("menu_category").getString("category"));
        // Vanilla data driven blocks are still sent
        assertEquals(vanilla.blocks().blockProperties().size() + 1, blocks.blockProperties().size());

        PaletteItem item = palette.items().getDefinition("geyser:lamp");
        assertSame(defaultState, palette.items().defaultBlock(item));
        CreativeItemData last = palette.creative().items().getLast();
        assertSame(item, last.getItem().getDefinition());
        assertSame(defaultState, last.getItem().getBlockDefinition());
    }

    @Test
    void olderVersionsAdaptStatesAndUseFallbacks() {
        Palette latest = PALETTER.vanilla(PALETTER.version(LATEST));
        Palette oldest = PALETTER.vanilla(PALETTER.version(OLDEST));

        // Stairs gained a state after 26.40; the newest state still resolves
        NbtMap stairs = latest.blocks().states("minecraft:oak_stairs").getLast().getState();
        assertNotNull(latest.blocks().exact(stairs));
        BlockResolution adapted = oldest.blocks().resolve(stairs);
        assertEquals(BlockResolution.Kind.ADAPTED, adapted.kind());
        assertEquals("minecraft:oak_stairs", adapted.block().identifier());
        for (String key : adapted.block().states().keySet()) {
            if (BlockStates.states(stairs).containsKey(key)) {
                assertEquals(BlockStates.states(stairs).get(key), adapted.block().states().get(key), key);
            }
        }

        // A block that does not exist in 26.40
        NbtMap concreteStairs = latest.blocks().states("minecraft:white_concrete_stairs").getFirst().getState();
        BlockResolution fallback = oldest.blocks().resolve(concreteStairs);
        assertEquals(BlockResolution.Kind.FALLBACK, fallback.kind());
        assertEquals("minecraft:smooth_quartz_stairs", fallback.block().identifier());

        // Experimental in 26.40: replaced even though it is in the palette
        NbtMap poplar = latest.blocks().states("minecraft:poplar_planks").getFirst().getState();
        assertNotNull(oldest.blocks().exact(poplar));
        assertEquals("minecraft:pale_oak_planks", oldest.blocks().resolve(poplar).block().identifier());

        // The same for items
        ResolvedItem boat = oldest.items().resolve(ItemVariant.of("minecraft:poplar_boat"));
        assertTrue(boat.fallback());
        assertEquals("minecraft:pale_oak_boat", boat.definition().getIdentifier());
        assertFalse(latest.items().resolve(ItemVariant.of("minecraft:poplar_boat")).fallback());
    }

    @Test
    void fallbacksNeverShadowAnItemsOwnCanonicalId() {
        // Poplar boats fall back to pale oak boats in 26.40; pale oak boats must still match themselves there
        int poplar = PALETTER.canonicalItem(ItemVariant.of("minecraft:poplar_boat"));
        int paleOak = PALETTER.canonicalItem(ItemVariant.of("minecraft:pale_oak_boat"));
        for (DataVersion version : PALETTER.versions()) {
            Palette palette = PALETTER.vanilla(version);
            ResolvedItem resolved = palette.item(paleOak);
            assertEquals(paleOak, palette.match(resolved.toItemData().build()), version.toString());
        }
        Palette oldest = PALETTER.vanilla(PALETTER.version(OLDEST));
        assertTrue(oldest.item(poplar).fallback());
        assertEquals("minecraft:pale_oak_boat", oldest.item(poplar).definition().getIdentifier());
    }

    @Test
    void creativeEditsRunPerVersionAndDropMissingItems() {
        PaletteLayer layer = PaletteLayer.builder("creative")
            .creative(editor -> editor.remove(ItemMatcher.id("minecraft:empty_map").and(ItemMatcher.damage(2))))
            .creative(editor -> editor.insert(CreativeStack.of("minecraft:poplar_boat")).loose(CreativeCategory.ITEMS))
            .build();
        Palette latest = PALETTER.palette(LATEST, layer);
        Palette oldest = PALETTER.palette(OLDEST, layer);
        assertTrue(latest.creativeLayout().locate(ItemMatcher.id("minecraft:empty_map").and(ItemMatcher.damage(2))).isEmpty());
        assertEquals("minecraft:poplar_boat", latest.creative().items().getLast().getItem().getDefinition().getIdentifier());
        // Poplar boats exist in 26.40's item list (experimental), so they are kept; unknown items would be dropped
        PaletteLayer unknown = PaletteLayer.builder("unknown")
            .creative(editor -> editor.insert(CreativeStack.of("test:does_not_exist")).loose(CreativeCategory.ITEMS))
            .build();
        Palette withUnknown = PALETTER.palette(OLDEST, unknown);
        assertEquals(PALETTER.vanilla(PALETTER.version(OLDEST)).creative().size(), withUnknown.creative().size());
        assertTrue(withUnknown.report().entries().stream().anyMatch(entry -> entry.message().contains("test:does_not_exist")));
        assertNotNull(oldest.creative());
    }

    private static int indexOf(List<CreativeItemData> items, String identifier) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getItem().getDefinition().getIdentifier().equals(identifier)) {
                return i;
            }
        }
        throw new AssertionError(identifier + " is not in the creative inventory");
    }
}
