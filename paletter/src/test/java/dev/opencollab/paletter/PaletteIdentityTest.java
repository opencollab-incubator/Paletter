package dev.opencollab.paletter;

import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.BlockRegistry;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.block.component.Geometry;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.ItemRegistry;
import dev.opencollab.paletter.item.RegisteredItem;
import dev.opencollab.paletter.item.component.MaxStackSize;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockBlockType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registered items and blocks are compared by identity (as Cloudburst's {@code ItemData} does with definitions): there is
 * exactly one instance per entry per palette, and a patch over a palette reuses the instances of everything it does not
 * change.
 */
class PaletteIdentityTest {
    private static final Paletter PALETTER = Paletter.bundled();
    private static final Paletter HASHED = Palettes.paletter();

    private static final CustomItem RUBY = CustomItem.builder("identity:ruby")
        .menuCategory(CreativeCategory.ITEMS).build();
    private static final CustomBlock LAMP = CustomBlock.builder("identity:lamp")
        .property(BlockProperty.range("identity:level", 0, 3))
        .component(Geometry.of("minecraft:geometry.full_block"))
        .build();

    private static void assertOneInstancePerItem(ItemRegistry items) {
        Map<RegisteredItem, Boolean> seen = new IdentityHashMap<>();
        Map<Integer, RegisteredItem> byId = new HashMap<>();
        Map<String, RegisteredItem> byName = new HashMap<>();
        for (RegisteredItem item : items.definitions()) {
            assertSame(item, items.getDefinition(item.getRuntimeId()), item.toString());
            assertSame(item, items.getDefinition(item.getIdentifier()), item.toString());
            assertTrue(items.isRegistered(item));
            assertNull(seen.put(item, true), "listed twice: " + item);
            assertNull(byId.put(item.getRuntimeId(), item), "runtime id of " + item + " is shared");
            assertNull(byName.put(item.getIdentifier(), item), "identifier of " + item + " is shared");
        }
    }

    private static void assertOneInstancePerBlock(BlockRegistry blocks) {
        Map<RegisteredBlockState, Boolean> seen = new IdentityHashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            RegisteredBlockState state = blocks.byIndex(i);
            assertSame(state, blocks.byRuntimeId(state.getRuntimeId()), state.toString());
            assertSame(state, blocks.getDefinition(state.getRuntimeId()), state.toString());
            assertEquals(i, blocks.indexOf(state));
            assertNull(seen.put(state, true), "listed twice: " + state);
        }
        // What a canonical id gives is the one instance of the entry too
        for (int id = 0; id < blocks.canonical().size(); id++) {
            RegisteredBlockState sent = blocks.block(id);
            assertSame(sent, blocks.byRuntimeId(sent.getRuntimeId()), "canonical " + id);
            assertEquals(sent.getRuntimeId(), blocks.runtimeId(id));
        }
    }

    @Test
    void everyEntryIsOneInstanceInEveryVersion() {
        for (DataVersion version : HASHED.versions()) {
            Palette palette = HASHED.palette(version.protocolVersion());
            assertOneInstancePerItem(palette.items());
            assertOneInstancePerBlock(palette.blocks());
            // Asking twice gives the same palette, and handles give the same instances
            assertSame(palette, HASHED.palette(version.protocolVersion()));
            for (BedrockBlockType type : BedrockBlockType.vanillaTypes().subList(0, 50)) {
                for (BedrockBlockState state : type.states()) {
                    assertSame(palette.blockDefinition(state), palette.blockDefinition(state));
                    assertSame(palette.blockDefinition(state), palette.blocks().getDefinition(palette.runtimeId(state)));
                }
            }
        }
    }

    @Test
    void anExtensionReusesEveryInstanceItDoesNotChange() {
        PaletteLayer layer = PaletteLayer.builder("session")
            .item(RUBY)
            .block(LAMP)
            .overrideItem("minecraft:cake", item -> item.modifyComponents(MaxStackSize.of(1)))
            .build();
        for (DataVersion version : PALETTER.versions()) {
            int protocol = version.protocolVersion();
            Palette base = HASHED.palette(protocol);
            Palette session = base.extend(layer);
            assertSame(session, base.extend(layer), "the patch of a layer is cached");

            // Items: all of the base, except what the layer overrides
            assertOneInstancePerItem(session.items());
            RegisteredItem cake = base.items().getDefinition("minecraft:cake");
            int changed = 0;
            for (RegisteredItem item : base.items().definitions()) {
                RegisteredItem own = session.items().getDefinition(item.getRuntimeId());
                if (item == cake) {
                    assertNotSame(item, own);
                    assertEquals(item.getIdentifier(), own.getIdentifier());
                    changed++;
                } else {
                    assertSame(item, own, item.toString());
                    assertSame(item, session.items().getDefinition(item.getIdentifier()));
                }
            }
            assertEquals(cake == null ? 0 : 1, changed);
            RegisteredItem ruby = session.items().getDefinition("identity:ruby");
            assertNotNull(ruby);
            assertTrue(ruby.isCustom());

            // Blocks: every state of the base, then the added ones
            assertOneInstancePerBlock(session.blocks());
            for (int i = 0; i < base.blocks().size(); i++) {
                assertSame(base.blocks().byIndex(i), session.blocks().byIndex(i), version + " index " + i);
            }
            for (int id = 0; id < base.blocks().canonical().size(); id++) {
                assertSame(base.blocks().block(id), session.blocks().block(id), version + " canonical " + id);
            }
            // Handles of vanilla states give the base's instance in the patch
            for (BedrockBlockType type : BedrockBlockType.vanillaTypes().subList(0, 50)) {
                for (BedrockBlockState state : type.states()) {
                    assertSame(base.blockDefinition(state), session.blockDefinition(state));
                }
            }
            assertNotNull(session.blockDefinition(LAMP.type().defaultState()));
        }
    }

    @Test
    void stackedExtensionsKeepReusing() {
        int protocol = PALETTER.latest().protocolVersion();
        Palette base = HASHED.palette(protocol);
        Palette first = base.extend(PaletteLayer.builder("first").item(RUBY).build());
        Palette second = first.extend(PaletteLayer.builder("second").block(LAMP).build());
        assertOneInstancePerItem(second.items());
        for (RegisteredItem item : base.items().definitions()) {
            assertSame(item, second.items().getDefinition(item.getRuntimeId()));
        }
        assertSame(first.items().getDefinition("identity:ruby"), second.items().getDefinition("identity:ruby"));
        for (int i = 0; i < base.blocks().size(); i++) {
            assertSame(base.blocks().byIndex(i), second.blocks().byIndex(i));
        }
        BedrockItemVariant variant = base.items().canonical().handle(0);
        assertSame(base.itemDefinition(variant.type()), second.itemDefinition(variant.type()));
    }
}
