package dev.opencollab.paletter;

import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.item.BedrockItemStack;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.ItemFallback;
import dev.opencollab.paletter.item.ResolvedItem;
import dev.opencollab.paletter.item.component.MaxStackSize;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import dev.opencollab.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A palette hands back the types a registered {@link CustomItem} or {@link CustomBlock} owns, so custom content compares
 * with {@code ==} like vanilla constants, in every version and at every level of layers.
 */
class CustomHandleIdentityTest {
    private static final Paletter PALETTER = Paletter.bundled();

    private static CustomBlock lamp(String identifier) {
        return CustomBlock.builder(identifier).property(BlockProperty.bool("example:lit"))
            .menuCategory(CreativeCategory.CONSTRUCTION).build();
    }

    private static CustomItem ruby(String identifier) {
        return CustomItem.builder(identifier).menuCategory(CreativeCategory.ITEMS).build();
    }

    @Test
    void customTypesAreCreatedOnce() {
        CustomItem ruby = ruby("example:ruby");
        CustomBlock lamp = lamp("example:lamp");
        assertSame(ruby.type(), ruby.type());
        assertSame(ruby.type().variant(), ruby.type().variant());
        assertSame(ruby.type().variant(), ruby.type().variant(0));
        assertSame(lamp.type(), lamp.type());
        assertSame(lamp.type().defaultState(), lamp.type().defaultState());
        assertSame(lamp.itemType(), lamp.itemType());
    }

    @Test
    void aPaletteHandsBackTheTypesItsContentOwns() {
        CustomItem ruby = ruby("example:ruby");
        CustomBlock lamp = lamp("example:lamp");
        PaletteLayer layer = PaletteLayer.builder("content").item(ruby).block(lamp).build();
        Paletter paletter = Palettes.paletter(layer);
        List<DataVersion> versions = paletter.versions();
        for (DataVersion version : List.of(versions.getFirst(), versions.getLast())) {
            assertOwned(paletter.palette(version.protocolVersion()), ruby, lamp);
        }
    }

    @Test
    void handlesStayTheOwnedOnesAcrossExtendLevels() {
        CustomItem ruby = ruby("example:ruby");
        CustomBlock lamp = lamp("example:lamp");
        CustomItem sapphire = ruby("example:sapphire");
        CustomBlock lantern = lamp("example:lantern");
        BedrockItemVariant given = ruby.type().variant(5);
        Palette base = Palettes.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("global").item(ruby).block(lamp).build());
        Palette session = base.extend(PaletteLayer.builder("session").item(sapphire).block(lantern)
            // A variant of an item of the layer below
            .variant(given).build());
        Palette deeper = session.extend(PaletteLayer.builder("deeper").item(ruby("example:emerald")).build());
        for (Palette palette : List.of(base, session, deeper)) {
            assertOwned(palette, ruby, lamp);
        }
        for (Palette palette : List.of(session, deeper)) {
            assertOwned(palette, sapphire, lantern);
            BedrockItemVariant damaged = palette.itemVariant("example:ruby", 5, Lookup.RENAMED);
            assertNotNull(damaged);
            assertSame(ruby.type(), damaged.type(), "a variant of a custom item is of the item's type");
            assertSame(damaged, palette.itemVariant("example:ruby", 5, Lookup.RENAMED));
            // Not the variant the layer was given: a variant of another damage value is equal, not identical (docs/api.md)
            assertEquals(given, damaged);
            assertNotSame(given, damaged);
        }
        assertNull(base.itemVariant("example:sapphire", 0, Lookup.RENAMED));
    }

    @Test
    void anOverriddenCustomItemKeepsItsOwnedKey() {
        CustomItem ruby = ruby("example:ruby");
        CustomBlock lamp = lamp("example:lamp");
        Palette base = PALETTER.palette(PALETTER.latest().protocolVersion())
            .extend(PaletteLayer.builder("content").item(ruby).block(lamp).build());
        Palette overridden = base.extend(PaletteLayer.builder("fixes")
            .overrideItem(ruby.type(), item -> item.modifyComponents(MaxStackSize.of(1))).build());
        assertEquals(1, overridden.items().getDefinition("example:ruby").getComponentData().getCompound("components")
            .getCompound("item_properties").getInt("max_stack_size"), "the override applies");
        assertOwned(overridden, ruby, lamp);
    }

    @Test
    void aFallbackToACustomItemResolvesToItsOwnedVariant() {
        CustomItem ruby = ruby("example:ruby");
        Paletter paletter = Paletter.builder()
            .fallbacks(Fallbacks.builder().item(BedrockItems.DIAMOND_SWORD, ItemFallback.of(ruby.type()).forced()).build())
            .layer(PaletteLayer.builder("content").item(ruby).build())
            .build();
        Palette palette = paletter.palette(paletter.latest().protocolVersion());
        ResolvedItem resolved = palette.resolve(BedrockItems.DIAMOND_SWORD);
        assertNotNull(resolved);
        assertTrue(resolved.fallback());
        assertSame(ruby.type(), resolved.definition().type());
        assertSame(ruby.type().variant(), palette.resolve(BedrockItems.DIAMOND_SWORD).variant());
        assertSame(ruby.type().variant(), palette.decode(palette.encode(BedrockItemStack.of(BedrockItems.DIAMOND_SWORD))).variant());
    }

    @Test
    void aBlockItemIsTheBlocksOwnItemType() {
        CustomBlock none = CustomBlock.builder("example:none").item(CustomBlock.BlockItem.NONE).build();
        Palette blockOnly = PALETTER.palette(PALETTER.latest().protocolVersion())
            .extend(PaletteLayer.builder("blocks").block(none).build());
        assertNull(blockOnly.itemVariant("example:none", 0, Lookup.RENAMED));
        assertNull(blockOnly.itemOf(none.type()));
        CustomBlock lamp = CustomBlock.builder("example:lamp").property(BlockProperty.bool("example:lit"))
            .menuCategory(CreativeCategory.CONSTRUCTION).build();
        Palette palette = blockOnly.extend(PaletteLayer.builder("lamps").block(lamp).build());
        BedrockItemType itemType = lamp.itemType();
        assertSame(itemType, palette.itemOf(lamp.type()));
        assertSame(itemType.variant(), palette.itemVariant("example:lamp", 0, Lookup.RENAMED));
        assertSame(itemType, palette.itemDefinition(itemType).type());
        BedrockItemStack decoded = palette.decode(palette.encode(BedrockItemStack.of(itemType)));
        assertNotNull(decoded);
        assertSame(itemType.variant(), decoded.variant());
        assertSame(lamp.type().defaultState(), palette.blockOf(itemType.variant()));
    }

    @Test
    void equalItemsInSiblingLayersEachGetTheirOwnHandles() {
        CustomItem first = ruby("example:ruby");
        CustomItem second = ruby("example:ruby");
        CustomBlock firstLamp = lamp("example:lamp");
        CustomBlock secondLamp = lamp("example:lamp");
        assertEquals(first, second);
        assertEquals(firstLamp, secondLamp);
        Palette vanilla = PALETTER.palette(PALETTER.latest().protocolVersion());
        Palette a = vanilla.extend(PaletteLayer.builder("a").item(first).block(firstLamp).build());
        Palette b = vanilla.extend(PaletteLayer.builder("b").item(second).block(secondLamp).build());
        assertOwned(a, first, firstLamp);
        assertOwned(b, second, secondLamp);
        // Not the same objects, but equal: equals and hash keys work across the two
        assertNotSame(a.itemVariant("example:ruby", 0, Lookup.RENAMED), b.itemVariant("example:ruby", 0, Lookup.RENAMED));
        assertEquals(a.itemVariant("example:ruby", 0, Lookup.RENAMED), b.itemVariant("example:ruby", 0, Lookup.RENAMED));
        assertEquals(a.itemVariant("example:ruby", 0, Lookup.RENAMED).hashCode(), b.itemVariant("example:ruby", 0, Lookup.RENAMED).hashCode());
        BedrockBlockState lampA = a.blockState(firstLamp.type().defaultState().toNbt(), Lookup.RENAMED);
        BedrockBlockState lampB = b.blockState(firstLamp.type().defaultState().toNbt(), Lookup.RENAMED);
        assertNotSame(lampA, lampB);
        assertEquals(lampA, lampB);
        assertEquals(lampA.hashCode(), lampB.hashCode());
        // A handle of one works in the other palette
        assertEquals(a.runtimeId(lampA), b.runtimeId(lampA));
        assertSame(b.itemDefinition(first.type()), b.itemDefinition(second.type()));
    }

    @Test
    void handlesSurviveGarbageCollection() {
        CustomItem ruby = ruby("example:ruby");
        CustomBlock lamp = lamp("example:lamp");
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion())
            .extend(PaletteLayer.builder("gc").item(ruby).block(lamp).build());
        BedrockBlockState state = palette.blockState(lamp.type().defaultState().toNbt(), Lookup.RENAMED);
        BedrockItemVariant variant = palette.itemVariant("example:ruby", 0, Lookup.RENAMED);
        // Equal blocks and items made and dropped meanwhile must not change what the palette hands back
        WeakReference<CustomBlock> temporary = temporaryCopies();
        for (int i = 0; i < 100 && temporary.get() != null; i++) {
            System.gc();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertNull(temporary.get(), "nothing keeps a dropped block alive");
        assertSame(state, palette.blockState(lamp.type().defaultState().toNbt(), Lookup.RENAMED));
        assertSame(lamp.type().defaultState(), state);
        assertSame(variant, palette.itemVariant("example:ruby", 0, Lookup.RENAMED));
        assertSame(ruby.type().variant(), variant);
        assertOwned(palette, ruby, lamp);
    }

    private static WeakReference<CustomBlock> temporaryCopies() {
        CustomBlock copy = lamp("example:lamp");
        copy.type().states();
        ruby("example:ruby").type().variant();
        return new WeakReference<>(copy);
    }

    /** Every way a palette hands back a handle of the item or block gives the one it owns. */
    private static void assertOwned(Palette palette, CustomItem item, CustomBlock block) {
        String where = palette.version() + " " + palette.layers().size() + " layers";
        BedrockItemType type = item.type();
        BedrockItemVariant base = type.variant();

        // Items
        assertSame(base, palette.itemVariant(item.identifier(), 0, Lookup.RENAMED), where);
        ItemData encoded = palette.encode(BedrockItemStack.of(base, 3));
        BedrockItemStack decoded = palette.decode(encoded);
        assertNotNull(decoded, where);
        assertSame(base, decoded.variant(), where);
        assertSame(type, palette.itemDefinition(type).type(), where);
        assertSame(type, palette.resolve(base).definition().type(), where);
        assertSame(item, palette.itemDefinition(type).customItem(), where);
        assertSame(base, palette.explain(item.identifier(), 0, null).handle(), where);
        assertSame(base, palette.itemVariants().stream().filter(v -> v.type().identifier().equals(item.identifier())).findFirst().orElseThrow(), where);
        assertSame(type, palette.creativeLayout().stacks().filter(stack -> stack.type().identifier().equals(item.identifier()))
            .findFirst().orElseThrow().type(), where);

        // The block item
        BedrockItemType blockItem = block.itemType();
        assertSame(blockItem.variant(), palette.itemVariant(block.identifier(), 0, Lookup.RENAMED), where);
        BedrockItemStack decodedBlockItem = palette.decode(palette.encode(BedrockItemStack.of(blockItem)));
        assertNotNull(decodedBlockItem, where);
        assertSame(blockItem.variant(), decodedBlockItem.variant(), where);
        assertSame(blockItem, palette.itemDefinition(blockItem).type(), where);
        assertSame(blockItem, palette.creativeLayout().stacks().filter(stack -> stack.type().identifier().equals(block.identifier()))
            .findFirst().orElseThrow().type(), where);

        // Blocks
        assertSame(block.type(), palette.blocks().block(block.identifier()).type(), where);
        for (BedrockBlockState state : block.type().states()) {
            assertSame(state, palette.blockState(state.toNbt(), Lookup.RENAMED), where);
            RegisteredBlockState registered = palette.blockDefinition(state);
            assertSame(state, palette.blocks().key(registered), where);
            assertSame(state, BedrockBlockState.fromCanonical(palette.blocks().canonical(), palette.blocks().canonical().index(state.toNbt())), where);
            assertSame(state, palette.explain(state.toNbt()).handle(), where);
        }
        assertSame(block.type().defaultState(), palette.blockOf(blockItem.variant()), where);
    }
}
