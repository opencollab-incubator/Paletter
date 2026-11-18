package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.annotation.SincePreview;
import org.geysermc.paletter.internal.CanonicalBlocks;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.data.VanillaItem;
import org.geysermc.paletter.item.ResolvedItem;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockBlockType;
import org.geysermc.paletter.vanilla.BedrockBlocks;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.geysermc.paletter.vanilla.BedrockItems;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** In a Paletter with previews, content only the preview has is reached by the public lookups like any other. */
class PreviewHandlesTest {
    private final Paletter paletter = Paletter.builder().previews(true).build();
    private final Palette palette = paletter.vanilla(paletter.latest());
    private final VanillaData data = paletter.vanillaData(paletter.latest());

    @Test
    void everyBlockStateOfThePreviewHasAHandle() {
        for (NbtMap state : data.blockStates()) {
            BedrockBlockState handle = palette.blockState(state, Lookup.EXACT);
            assertNotNull(handle, state.toString());
            assertEquals(state.getString("name"), handle.type().identifier());
            assertEquals(handle, palette.blockState(handle.type().identifier(), handle.values(), Lookup.EXACT));
            assertSame(handle, palette.blockState(state, Lookup.EXACT));
        }
    }

    @Test
    void previewOnlyStatesRoundTrip() {
        NbtMap state = data.blockStates().stream().filter(s -> s.getString("name").equals("minecraft:polished_calcite"))
            .findFirst().orElseThrow();
        BedrockBlockState handle = palette.blockState(state, Lookup.EXACT);
        assertNotNull(handle);
        CanonicalBlocks canonical = palette.blocks().canonical();
        assertEquals(canonical.index(state), handle.canonicalIn(canonical));
        assertEquals(canonical.index(state), canonical.index(handle.toNbt()));
    }

    @Test
    void everyItemOfThePreviewHasAHandle() {
        for (VanillaItem item : data.items()) {
            BedrockItemVariant variant = palette.itemVariant(item.identifier(), 0, Lookup.EXACT);
            assertNotNull(variant, item.toString());
            assertEquals(item.identifier(), variant.type().identifier());
            BedrockItemType type = palette.itemType(item.identifier(), Lookup.EXACT);
            assertNotNull(type, item.toString());
        }
    }

    /** The handles of the preview's items send the item itself, not the fallback of its constant (minecraft:unknown). */
    @Test
    void everyItemOfThePreviewResolvesToItself() {
        for (VanillaItem item : data.items()) {
            BedrockItemVariant variant = palette.itemVariant(item.identifier(), 0, Lookup.EXACT);
            assertEquals(item.identifier(), palette.items().resolve(variant).definition().getIdentifier(), item.toString());
        }
    }

    /**
     * Every item of the preview has a generated constant, and its keys are that constant: the handle of its canonical id,
     * the key of its definition, the type of its identifier. One object, so it is a map key whichever way it was
     * obtained, and each way sends the item itself.
     */
    @Test
    void theKeysOfAPreviewItemAreItsConstant() {
        Map<BedrockItemType, String> byKey = new HashMap<>();
        for (VanillaItem item : data.items()) {
            BedrockItemType constant = BedrockItemType.byIdentifier(item.identifier());
            assertNotNull(constant, item.identifier() + " has no constant");
            assertFalse(constant.isCustom(), item.identifier());
            BedrockItemType key = palette.items().getDefinition(item.identifier()).key();
            assertSame(constant, key, item.identifier());
            assertSame(constant, palette.itemVariant(item.identifier(), 0, Lookup.EXACT).type(), item.identifier());
            assertEquals(item.identifier(), palette.items().resolve(key).definition().getIdentifier(), item.identifier());
            byKey.put(key, item.identifier());
        }
        for (VanillaItem item : data.items()) {
            BedrockItemType handle = palette.itemVariant(item.identifier(), 0, Lookup.EXACT).type();
            assertEquals(item.identifier(), byKey.get(handle), item.identifier());
        }
    }

    /** The item of every block of the preview sends the item itself, also for the blocks only the preview has. */
    @Test
    void theItemOfAPreviewBlockResolvesToItself() {
        data.blockItems().forEach((block, item) -> {
            BedrockBlockType type = palette.blockType(block, Lookup.EXACT);
            assertNotNull(type, block);
            BedrockItemType itemType = palette.itemOf(type);
            assertNotNull(itemType, block);
            assertEquals(item, palette.items().resolve(itemType).definition().getIdentifier(), block);
        });
    }

    /**
     * Without previews the @SincePreview item constants have no item of their own: they send their fallback. With
     * previews the same constants send the item itself.
     */
    @Test
    void previewItemConstantsResolvePerPalette() throws IllegalAccessException {
        Paletter releases = Paletter.builder().build();
        Palette release = releases.vanilla(releases.latest());
        int checked = 0;
        for (Field field : BedrockItems.class.getFields()) {
            if (!field.isAnnotationPresent(SincePreview.class)) {
                continue;
            }
            BedrockItemType constant = (BedrockItemType) field.get(null);
            assertFalse(constant.isAvailable(), field.getName());
            assertEquals("minecraft:unknown", release.items().resolve(constant).definition().getIdentifier(), field.getName());
            assertEquals(constant.identifier(), palette.items().resolve(constant).definition().getIdentifier(), field.getName());
            checked++;
        }
        assertTrue(checked > 0);
    }

    /** Without previews, a preview item constant resolves through the fallback policy like any item a version lacks. */
    @Test
    void aReleasePaletteSendsThePolicyFallbackOfAPreviewItem() {
        Paletter withEntry = Paletter.builder().fallbacks(Fallbacks.builder().item(BedrockItems.BRITTLE_ICE, BedrockItems.ICE).build()).build();
        ResolvedItem ice = withEntry.vanilla(withEntry.latest()).items().resolve(BedrockItems.BRITTLE_ICE);
        assertEquals("minecraft:ice", ice.definition().getIdentifier());
        assertTrue(ice.fallback());

        Paletter withoutEntry = Paletter.builder().build();
        ResolvedItem unknown = withoutEntry.vanilla(withoutEntry.latest()).items().resolve(BedrockItems.BRITTLE_ICE);
        assertEquals("minecraft:unknown", unknown.definition().getIdentifier());
        assertTrue(unknown.fallback());

        Paletter none = Paletter.builder().fallbacks(Fallbacks.NONE).build();
        assertNull(none.vanilla(none.latest()).items().resolve(BedrockItems.BRITTLE_ICE));
    }

    /** With previews, a release that lacks a preview item sends what the fallback policy gives for it. */
    @Test
    void anOlderVersionSendsThePolicyFallbackOfAPreviewItem() {
        Paletter previews = Paletter.builder().previews(true)
            .fallbacks(Fallbacks.builder().item(BedrockItems.BRITTLE_ICE, BedrockItems.ICE).build()).build();
        Palette newestRelease = previews.vanilla(Paletter.builder().build().latest());
        assertEquals("minecraft:ice", newestRelease.items().resolve(BedrockItems.BRITTLE_ICE).definition().getIdentifier());
        assertEquals("minecraft:brittle_ice", previews.vanilla(previews.latest()).items().resolve(BedrockItems.BRITTLE_ICE)
            .definition().getIdentifier());
    }

    /** A block only the preview has: its constant has the fallback's states, so it is not the preview's type. */
    @Test
    void anUnavailableBlockConstantIsNotThePreviewType() {
        int checked = 0;
        for (NbtMap state : data.blockStates()) {
            BedrockBlockType type = palette.blockState(state, Lookup.EXACT).type();
            BedrockBlockType constant = BedrockBlockType.byIdentifier(type.identifier());
            if (constant != null && !constant.isAvailable()) {
                assertNotEquals(constant, type, type.identifier());
                assertNotEquals(type, constant, type.identifier());
                checked++;
            }
        }
        assertTrue(checked > 0);
    }

    /**
     * The creative inventory sends what the preview's data lists, also the items only the preview has. BDS 1.26.70.24
     * sends {@code minecraft:polished_calcite_wall} in its creative inventory, an item 26.60 does not have.
     */
    @Test
    void creativeInventorySendsThePreviewItems() {
        List<String> sent = palette.creative().items().stream().map(item -> item.getItem().getDefinition().getIdentifier()).toList();
        assertFalse(sent.contains("minecraft:unknown"), "the creative inventory sends minecraft:unknown");
        assertTrue(sent.contains("minecraft:polished_calcite_wall"));
    }

    @Test
    void defaultStatesAreBdsDefaults() {
        data.blockDefaults().forEach((name, state) -> {
            BedrockBlockState handle = palette.blockState(state, Lookup.EXACT);
            assertNotNull(handle, name);
            assertEquals(handle, handle.type().defaultState(), name);
        });
    }

    @Test
    void typesEqualTheReleaseConstantOnlyForTheSameStates() {
        for (NbtMap state : data.blockStates()) {
            BedrockBlockState handle = palette.blockState(state, Lookup.EXACT);
            BedrockBlockType release = BedrockBlockType.vanillaType(handle.type().identifier());
            if (release != null) {
                assertEquals(release.properties().equals(handle.type().properties())
                    && release.stateCount() == handle.type().stateCount(), release.equals(handle.type()), handle.toString());
            }
        }
        BedrockBlockState scaffolding = palette.blockState(
            data.blockStates().stream().filter(s -> s.getString("name").equals("minecraft:scaffolding")).findFirst().orElseThrow(),
            Lookup.EXACT);
        assertNotEquals(BedrockBlocks.SCAFFOLDING, scaffolding.type());
        assertNotEquals(BedrockBlocks.SCAFFOLDING.defaultState(), scaffolding.type().defaultState());
    }

    @Test
    void aBlockHasOneTypePerIdentifier() {
        Map<String, BedrockBlockType> types = new HashMap<>();
        for (NbtMap state : data.blockStates()) {
            BedrockBlockType type = palette.blockState(state, Lookup.EXACT).type();
            assertSame(type, types.computeIfAbsent(type.identifier(), name -> type), type.identifier());
        }
    }
}
