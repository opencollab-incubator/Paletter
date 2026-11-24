package dev.opencollab.paletter;

import dev.opencollab.paletter.item.ResolvedItem;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import dev.opencollab.paletter.block.component.Geometry;
import dev.opencollab.paletter.block.component.LightEmission;
import dev.opencollab.paletter.block.BlockHashes;
import dev.opencollab.paletter.block.BlockRegistry;
import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.BlockFallback;
import dev.opencollab.paletter.internal.block.BlockResolution;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.creative.CreativeEntry;
import dev.opencollab.paletter.creative.CreativePlacement;
import dev.opencollab.paletter.creative.ItemMatcher;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.item.BedrockItemStack;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.component.ItemDisplayName;
import dev.opencollab.paletter.item.component.Icon;
import dev.opencollab.paletter.item.component.MaxStackSize;
import dev.opencollab.paletter.item.ItemFallback;
import dev.opencollab.paletter.internal.item.ItemVariant;
import dev.opencollab.paletter.item.RegisteredItem;
import dev.opencollab.paletter.item.component.ThrowableItem;
import dev.opencollab.paletter.vanilla.BedrockBlocks;
import dev.opencollab.paletter.vanilla.BedrockItems;
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
                .components(Icon.of("minecart_furnace"),
                    ItemDisplayName.builder().value("item.minecartFurnace.name").build(),
                    MaxStackSize.of(1))
                .menuCategory(CreativeCategory.ITEMS, "itemGroup.name.minecart")
                .creativePlacement(CreativePlacement.after(ItemMatcher.id("minecraft:hopper_minecart")))
                .build())
            .build();
    }

    @Test
    void customItemsGetRuntimeIdsComponentsAndCreativePlacement() {
        Palette vanilla = PALETTER.vanilla(PALETTER.version(LATEST));
        Palette palette = Palettes.palette(LATEST, minecartLayer());
        RegisteredItem furnace = palette.items().getDefinition("geysermc:furnace_minecart");
        assertNotNull(furnace);
        assertTrue(furnace.isCustom());
        assertEquals(ItemVersion.DATA_DRIVEN, furnace.getVersion());
        int maxVanilla = vanilla.items().definitions().stream().mapToInt(RegisteredItem::getRuntimeId).max().orElseThrow();
        assertEquals(maxVanilla + 1, furnace.getRuntimeId());

        NbtMap data = furnace.getComponentData();
        assertEquals("geysermc:furnace_minecart", data.getString("name"));
        assertEquals(furnace.getRuntimeId(), data.getInt("id"));
        NbtMap properties = data.getCompound("components").getCompound("item_properties");
        // items (data/model/mappings.json, item:description menu_category.category)
        assertEquals(4, properties.getInt("creative_category"));
        assertEquals("minecraft:itemGroup.name.minecart", properties.getString("creative_group"));

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
        Palette palette = Palettes.palette(LATEST, minecartLayer());
        ItemData furnace = ItemData.builder().definition(palette.items().getDefinition("geysermc:furnace_minecart")).count(1).build();
        ItemData hopper = ItemData.builder().definition(palette.items().getDefinition("minecraft:hopper_minecart")).count(1).build();
        int furnaceCanonical = Ids.item(palette, ItemVariant.of("geysermc:furnace_minecart"));
        assertEquals(Ids.itemCount(PALETTER), furnaceCanonical, "custom items are appended");
        assertEquals(furnaceCanonical, Ids.match(palette, furnace));
        assertEquals(Ids.item(PALETTER, ItemVariant.of("minecraft:hopper_minecart")), Ids.match(palette, hopper));
        assertEquals(-1, Ids.item(PALETTER, ItemVariant.of("geysermc:furnace_minecart")));
        // The same layer gives the same canonical ids in every version
        assertEquals(furnaceCanonical, Ids.item(Palettes.palette(OLDEST, minecartLayer()), ItemVariant.of("geysermc:furnace_minecart")));
    }

    @Test
    void damageVariantsMatchTheirCanonicalId() {
        Palette palette = PALETTER.palette(LATEST);
        RegisteredItem potion = palette.items().getDefinition("minecraft:potion");
        int five = Ids.item(PALETTER, ItemVariant.of("minecraft:potion", 5));
        int base = Ids.item(PALETTER, ItemVariant.of("minecraft:potion"));
        assertTrue(five >= 0 && base >= 0 && five != base);
        assertEquals(five, Ids.match(palette, ItemData.builder().definition(potion).damage(5).count(1).build()));
        // A damage value that is no variant falls back to the base variant
        assertEquals(base, Ids.match(palette, ItemData.builder().definition(potion).damage(9999).count(1).build()));
        ResolvedItem resolved = Ids.itemAt(palette, five);
        assertSame(potion, resolved.definition());
        assertEquals(5, resolved.aux());
    }

    @Test
    void sessionLayersShareUnchangedDefinitions() {
        Palette shared = Palettes.palette(LATEST, minecartLayer());
        PaletteLayer session = PaletteLayer.builder("session")
            .item(CustomItem.builder("server:ruby").menuCategory(CreativeCategory.ITEMS).build())
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
        int furnace = Ids.item(shared, ItemVariant.of("geysermc:furnace_minecart"));
        assertEquals(furnace, Ids.item(sessionPalette, ItemVariant.of("geysermc:furnace_minecart")));
        assertSame(Ids.itemAt(shared, furnace).definition(), Ids.itemAt(sessionPalette, furnace).definition());
        assertEquals(Ids.itemCount(shared), Ids.item(sessionPalette, ItemVariant.of("server:ruby")));
        // Extending again with the same layer returns the cached palette
        assertSame(sessionPalette, shared.extend(session));
    }

    @Test
    void overridesChangeVanillaDefinitions() {
        PaletteLayer layer = PaletteLayer.builder("fixes")
            .overrideItem("minecraft:cake", item -> item.modifyComponents(MaxStackSize.of(1)))
            // TODO(client test): firework_rocket is hard coded; BDS rejected the hard coded stick at 1.26.60 and the rocket was
            // never probed (inferred). If BDS rejects it too, this deviates from BDS. The client's result is unverified.
            .overrideItem("minecraft:firework_rocket", item -> item.replaceComponents(MaxStackSize.of(64),
                ThrowableItem.builder().doSwingAnimation(true).build()))
            .overrideItem("minecraft:structure_block", item -> item.noBlock())
            .build();
        Palette vanilla = PALETTER.vanilla(PALETTER.version(LATEST));
        Palette palette = Palettes.palette(LATEST, layer);

        RegisteredItem cake = palette.items().getDefinition("minecraft:cake");
        assertNotSame(vanilla.items().getDefinition("minecraft:cake"), cake);
        assertEquals(vanilla.items().getDefinition("minecraft:cake").getRuntimeId(), cake.getRuntimeId());
        assertTrue(cake.isComponentBased());
        assertEquals(1, cake.getComponentData().getCompound("components").getCompound("item_properties").getInt("max_stack_size"));

        RegisteredItem rocket = palette.items().getDefinition("minecraft:firework_rocket");
        assertEquals(ItemVersion.DATA_DRIVEN, rocket.getVersion());
        assertTrue(rocket.getComponentData().getCompound("components").containsKey("minecraft:throwable"));

        assertTrue(vanilla.items().isBlockItem(vanilla.items().getDefinition("minecraft:structure_block")));
        assertFalse(palette.items().isBlockItem(palette.items().getDefinition("minecraft:structure_block")));
        // Creative stacks use the new definitions
        int cakeIndex = indexOf(palette.creative().items(), "minecraft:cake");
        assertSame(cake, palette.creative().items().get(cakeIndex).getItem().getDefinition());
    }

    @Test
    void customBlocksKeepVanillaIdsAndGetHashedOnes() {
        CustomBlock lamp = CustomBlock.builder("geyser:lamp")
            .property(BlockProperty.bool("geyser:lit"))
            .property(BlockProperty.ints("geyser:color", 0, 1, 2))
            .components(LightEmission.of(15), Geometry.of("minecraft:geometry.full_block"))
            .permutation("q.block_state('geyser:lit') == 1", LightEmission.of(15))
            .defaultState("geyser:color", 1)
            .menuCategory(CreativeCategory.ITEMS)
            .build();
        Palette vanilla = PALETTER.vanilla(PALETTER.version(LATEST));
        Palette palette = Palettes.palette(LATEST, PaletteLayer.builder("blocks").block(lamp).build());
        BlockRegistry blocks = palette.blocks();

        assertEquals(vanilla.blocks().size() + 6, blocks.size());
        assertEquals(6, blocks.states("geyser:lamp").size());
        // Ids are hashes: vanilla blocks keep theirs, whatever is added
        for (int i = 0; i < vanilla.blocks().size(); i += 97) {
            assertEquals(vanilla.blocks().byIndex(i).getState(), blocks.byIndex(i).getState());
            assertEquals(vanilla.blocks().byIndex(i).getRuntimeId(), blocks.byIndex(i).getRuntimeId());
        }
        for (RegisteredBlockState state : blocks.states("geyser:lamp")) {
            assertEquals(BlockHashes.hash(state.getState()), state.getRuntimeId());
        }

        RegisteredBlockState defaultState = blocks.defaultState("geyser:lamp");
        assertEquals(1, defaultState.states().getInt("geyser:color"));
        assertEquals((byte) 0, defaultState.states().getByte("geyser:lit"));

        BlockPropertyData properties = blocks.blockProperties().stream().filter(p -> p.getName().equals("geyser:lamp")).findFirst().orElseThrow();
        assertEquals(BlockRegistry.CUSTOM_BLOCK_ID_START, properties.getProperties().getCompound("vanilla_block_data").getInt("block_id"));
        assertEquals("items", properties.getProperties().getCompound("menu_category").getString("category"));
        // Vanilla data driven blocks are still sent
        assertEquals(vanilla.blocks().blockProperties().size() + 1, blocks.blockProperties().size());

        RegisteredItem item = palette.items().getDefinition("geyser:lamp");
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

        // A block that does not exist in 26.40: nothing is bundled, the palette sends minecraft:unknown
        NbtMap concreteStairs = latest.blocks().states("minecraft:white_concrete_stairs").getFirst().getState();
        assertEquals(ResolutionKind.DEFAULT, oldest.explain(concreteStairs).kind());
        assertEquals("minecraft:unknown", oldest.explain(concreteStairs).sent().get("name"));
        // ... unless the consumer defines a fallback
        Palette withFallback = Paletter.builder().fallbacks(Fallbacks.builder()
            .block(BedrockBlocks.WHITE_CONCRETE_STAIRS, BlockFallback.of(BedrockBlocks.SMOOTH_QUARTZ_STAIRS)).build())
            .build().palette(PALETTER.version(OLDEST).protocolVersion());
        BlockResolution fallback = withFallback.blocks().resolve(concreteStairs);
        assertEquals(BlockResolution.Kind.FALLBACK, fallback.kind());
        assertEquals("minecraft:smooth_quartz_stairs", fallback.block().identifier());

        // Behind y_2026_drop_3 in 26.40: the state is in the palette, but without the experiment it is missing, so a
        // plain (non-forced) fallback applies, and the default one without
        NbtMap poplar = latest.blocks().states("minecraft:poplar_planks").getFirst().getState();
        assertNotNull(oldest.blocks().exact(poplar));
        assertNull(oldest.blocks().resolve(poplar).block());
        assertEquals(ResolutionKind.DEFAULT, oldest.explain(poplar).kind());
        Palette flat = Paletter.builder().fallbacks(Fallbacks.builder()
            .block(BedrockBlocks.POPLAR_PLANKS, BlockFallback.of(BedrockBlocks.PALE_OAK_PLANKS))
            .item(BedrockItems.POPLAR_BOAT, ItemFallback.of(BedrockItems.PALE_OAK_BOAT)).build())
            .build().palette(PALETTER.version(OLDEST).protocolVersion());
        assertEquals("minecraft:pale_oak_planks", flat.blocks().resolve(poplar).block().identifier());

        // The same for items
        ResolvedItem boat = flat.items().resolve(ItemVariant.of("minecraft:poplar_boat"));
        assertTrue(boat.fallback());
        assertEquals("minecraft:pale_oak_boat", boat.definition().getIdentifier());
        // With the experiment enabled the content is there; in the newest version it is regular content
        Palette enabled = Paletter.builder().experiments("y_2026_drop_3").fallbacks(Fallbacks.builder()
            .block(BedrockBlocks.POPLAR_PLANKS, BlockFallback.of(BedrockBlocks.PALE_OAK_PLANKS)).build())
            .build().palette(PALETTER.version(OLDEST).protocolVersion());
        assertEquals("minecraft:poplar_planks", enabled.blocks().resolve(poplar).block().identifier());
        assertFalse(enabled.items().resolve(ItemVariant.of("minecraft:poplar_boat")).fallback());
        assertFalse(latest.items().resolve(ItemVariant.of("minecraft:poplar_boat")).fallback());
    }

    @Test
    void fallbacksNeverShadowAnItemsOwnCanonicalId() {
        // A layer sends poplar boats as pale oak boats in 26.40; pale oak boats must still match themselves there
        Paletter forcing = Paletter.builder().fallbacks(Fallbacks.builder()
            .item(BedrockItems.POPLAR_BOAT, ItemFallback.of(BedrockItems.PALE_OAK_BOAT).forced()).build()).build();
        int poplar = Ids.item(PALETTER, ItemVariant.of("minecraft:poplar_boat"));
        int paleOak = Ids.item(PALETTER, ItemVariant.of("minecraft:pale_oak_boat"));
        for (DataVersion version : PALETTER.versions()) {
            Palette palette = forcing.palette(version.protocolVersion());
            ResolvedItem resolved = Ids.itemAt(palette, paleOak);
            assertEquals(paleOak, Ids.match(palette, resolved.toItemData().build()), version.toString());
        }
        Palette oldest = forcing.palette(PALETTER.version(OLDEST).protocolVersion());
        assertTrue(Ids.itemAt(oldest, poplar).fallback());
        assertEquals("minecraft:pale_oak_boat", Ids.itemAt(oldest, poplar).definition().getIdentifier());
    }

    @Test
    void creativeEditsRunPerVersionAndDropMissingItems() {
        PaletteLayer layer = PaletteLayer.builder("creative")
            .creative(editor -> editor.remove(ItemMatcher.id("minecraft:empty_map").and(ItemMatcher.aux(2))))
            .creative(editor -> editor.insert(BedrockItemStack.of(BedrockItemType.fromIdentifier("minecraft:poplar_boat"))).loose(CreativeCategory.ITEMS))
            .build();
        Palette latest = Palettes.palette(LATEST, layer);
        Palette oldest = Palettes.palette(OLDEST, layer);
        assertFalse(latest.creativeLayout().contains(ItemMatcher.id("minecraft:empty_map").and(ItemMatcher.aux(2))));
        assertEquals("minecraft:poplar_boat", latest.creative().items().getLast().getItem().getDefinition().getIdentifier());
        // Poplar boats exist in 26.40's item list (experimental), so they are kept; unknown items would be dropped
        PaletteLayer unknown = PaletteLayer.builder("unknown")
            .creative(editor -> editor.insert(BedrockItemStack.of(BedrockItemType.fromIdentifier("test:does_not_exist"))).loose(CreativeCategory.ITEMS))
            .build();
        Palette withUnknown = Palettes.palette(OLDEST, unknown);
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

    @Test
    void theItemsOfALayerAreInRegistrationOrder() {
        CustomItem first = CustomItem.builder("order:first").menuCategory(CreativeCategory.ITEMS).build();
        CustomItem second = CustomItem.builder("order:second").menuCategory(CreativeCategory.ITEMS).build();
        PaletteLayer layer = PaletteLayer.builder("order").item(first).item(second).build();
        assertEquals(List.of(first, second), layer.items());
    }

    @Test
    void theBuilderRejectsNullContent() {
        assertThrows(NullPointerException.class, () -> PaletteLayer.builder("null").item(null));
        assertThrows(NullPointerException.class, () -> PaletteLayer.builder("null").block(null));
    }
}
