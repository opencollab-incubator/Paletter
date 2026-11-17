package org.geysermc.paletter;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.data.VanillaItem;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.item.component.Icon;
import org.geysermc.paletter.item.component.MaxStackSize;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The client often requires vanilla item ids to be exactly those of the bundled data: custom content never moves a
 * vanilla item, nor takes a vanilla id.
 */
class VanillaItemIdTest {
    private static final Paletter PALETTER = Paletter.bundled();

    private static CustomBlock block(String identifier) {
        return CustomBlock.builder(identifier).creative(CreativePlacement.loose(CreativeCategory.CONSTRUCTION)).build();
    }

    private static CustomItem item(String identifier) {
        return CustomItem.builder(identifier).component(Icon.of("ruby")).creative(CreativePlacement.loose(CreativeCategory.ITEMS)).build();
    }

    private static PaletteLayer.Builder dataDrivenBlock(PaletteLayer.Builder layer, String identifier) {
        return layer.blockItem(identifier, CustomBlock.BlockItem.dataDriven(), null).block(block(identifier));
    }

    // Every later layer adds a block that sorts above the earlier ones, so the plain block items of those move
    private static final PaletteLayer FIRST = dataDrivenBlock(PaletteLayer.builder("first"), "ids:dd")
        .item(item("ids:ruby")).block(block("ids:m")).block(block("ids:p")).build();
    private static final PaletteLayer SECOND = PaletteLayer.builder("second")
        .overrideItem("ids:p", item -> item.modifyComponents(MaxStackSize.of(1)))
        // BDS would move the apple (883 to 853) when an add-on replaces it; Paletter keeps the id, unverified on a client
        .overrideItem("minecraft:apple", item -> item.replaceComponents(MaxStackSize.of(8)))
        .overrideItem("minecraft:cake", item -> item.replaceComponents(MaxStackSize.of(1)))
        .item(item("ids:sapphire")).block(block("ids:z")).block(block("ids:a")).build();
    private static final PaletteLayer THIRD = dataDrivenBlock(PaletteLayer.builder("third"), "ids:dd2")
        .item(item("ids:emerald")).block(block("ids:y")).build();

    @Test
    void customContentNeverMovesOrTakesAVanillaId() {
        for (DataVersion version : PALETTER.versions()) {
            int protocol = version.protocolVersion();
            List<VanillaItem> bundled = PALETTER.vanillaData(version).items();
            Palette vanilla = Palettes.palette(protocol);
            assertVanillaIds(bundled, vanilla, vanilla);

            Palette first = Palettes.palette(protocol, FIRST);
            Palette second = first.extend(SECOND);
            Palette third = second.extend(THIRD);
            for (Palette palette : List.of(first, second, third, Palettes.palette(protocol, FIRST, SECOND, THIRD))) {
                assertVanillaIds(bundled, vanilla, palette);
            }
            assertEquals(vanilla.items().definitions().size() + 10, third.items().definitions().size(), version.name());
        }
    }

    private static void assertVanillaIds(List<VanillaItem> bundled, Palette vanilla, Palette palette) {
        String name = palette.version().name() + " with layers " + palette.layers().stream().map(PaletteLayer::id).toList();
        Int2ObjectMap<String> vanillaIds = new Int2ObjectOpenHashMap<>();
        for (VanillaItem item : bundled) {
            RegisteredItem registered = palette.items().getDefinition(item.identifier());
            assertNotNull(registered, item.identifier() + " in " + name);
            assertEquals(item.runtimeId(), registered.getRuntimeId(), item.identifier() + " in " + name);
            assertNull(vanillaIds.put(item.runtimeId(), item.identifier()), "bundled ids are unique");
        }
        for (RegisteredItem item : vanilla.items().definitions()) {
            assertEquals(item.getRuntimeId(), palette.items().getDefinition(item.getIdentifier()).getRuntimeId(), item.getIdentifier() + " in " + name);
        }
        Int2ObjectMap<RegisteredItem> seen = new Int2ObjectOpenHashMap<>();
        for (RegisteredItem item : palette.items().definitions()) {
            String vanillaAtId = vanillaIds.get(item.getRuntimeId());
            if (vanillaAtId == null) {
                assertNull(vanilla.items().getDefinition(item.getIdentifier()), item + " is vanilla, at a custom id in " + name);
            } else {
                assertEquals(vanillaAtId, item.getIdentifier(), "custom item at a vanilla id in " + name);
            }
            assertNull(seen.put(item.getRuntimeId(), item), "two items with id " + item.getRuntimeId() + " in " + name);
            assertSame(item, palette.items().getDefinition(item.getRuntimeId()), item + " by id in " + name);
            NbtMap componentData = item.getComponentData();
            if (componentData != null && componentData.containsKey("id")) {
                assertEquals(item.getRuntimeId(), componentData.getInt("id"), item + " sends its own id in " + name);
            }
        }
        assertFalse(vanillaIds.isEmpty());
    }
}
