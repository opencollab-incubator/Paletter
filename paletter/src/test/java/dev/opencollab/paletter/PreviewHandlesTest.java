package dev.opencollab.paletter;

import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.annotation.SincePreview;
import dev.opencollab.paletter.block.ResolvedBlock;
import dev.opencollab.paletter.internal.CanonicalBlocks;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.data.VanillaItem;
import dev.opencollab.paletter.item.ResolvedItem;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockBlockType;
import dev.opencollab.paletter.vanilla.BedrockBlocks;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import dev.opencollab.paletter.vanilla.BedrockItems;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
        assertEquals(canonical.index(state), Access.vanilla().canonicalId(handle, canonical));
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
            BedrockItemType key = palette.items().getDefinition(item.identifier()).type();
            assertSame(constant, key, item.identifier());
            assertSame(constant, palette.itemVariant(item.identifier(), 0, Lookup.EXACT).type(), item.identifier());
            assertEquals(item.identifier(), palette.items().resolve(key.base()).definition().getIdentifier(), item.identifier());
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
            assertEquals(item, palette.items().resolve(itemType.base()).definition().getIdentifier(), block);
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
            assertEquals("minecraft:unknown", release.items().resolve(constant.base()).definition().getIdentifier(), field.getName());
            assertEquals(constant.identifier(), palette.items().resolve(constant.base()).definition().getIdentifier(), field.getName());
            checked++;
        }
        assertTrue(checked > 0);
    }

    /** Without previews, a preview item constant resolves through the fallback policy like any item a version lacks. */
    @Test
    void aReleasePaletteSendsThePolicyFallbackOfAPreviewItem() {
        Paletter withEntry = Paletter.builder().fallbacks(Fallbacks.builder().itemType(BedrockItems.BRITTLE_ICE, BedrockItems.ICE).build()).build();
        ResolvedItem ice = withEntry.vanilla(withEntry.latest()).items().resolve(BedrockItems.BRITTLE_ICE.base());
        assertEquals("minecraft:ice", ice.definition().getIdentifier());
        assertTrue(ice.fallback());

        Paletter withoutEntry = Paletter.builder().build();
        ResolvedItem unknown = withoutEntry.vanilla(withoutEntry.latest()).items().resolve(BedrockItems.BRITTLE_ICE.base());
        assertEquals("minecraft:unknown", unknown.definition().getIdentifier());
        assertTrue(unknown.fallback());

        Paletter none = Paletter.builder().fallbacks(Fallbacks.NONE).build();
        assertNull(none.vanilla(none.latest()).items().resolve(BedrockItems.BRITTLE_ICE.base()));
    }

    /** With previews, a release that lacks a preview item sends what the fallback policy gives for it. */
    @Test
    void anOlderVersionSendsThePolicyFallbackOfAPreviewItem() {
        Paletter previews = Paletter.builder().previews(true)
            .fallbacks(Fallbacks.builder().itemType(BedrockItems.BRITTLE_ICE, BedrockItems.ICE).build()).build();
        Palette newestRelease = previews.vanilla(Paletter.builder().build().latest());
        assertEquals("minecraft:ice", newestRelease.items().resolve(BedrockItems.BRITTLE_ICE.base()).definition().getIdentifier());
        assertEquals("minecraft:brittle_ice", previews.vanilla(previews.latest()).items().resolve(BedrockItems.BRITTLE_ICE.base())
            .definition().getIdentifier());
    }

    /** The {@code @SincePreview} block constants: one for each block of the preview that the newest release lacks. */
    private List<BedrockBlockType> previewBlockConstants() throws IllegalAccessException {
        List<BedrockBlockType> constants = new ArrayList<>();
        for (Field field : BedrockBlocks.class.getFields()) {
            if (field.isAnnotationPresent(SincePreview.class)) {
                constants.add((BedrockBlockType) field.get(null));
            }
        }
        Paletter releases = Paletter.builder().build();
        Set<String> release = new HashSet<>();
        releases.vanillaData(releases.latest()).blockStates().forEach(state -> release.add(state.getString("name")));
        Set<String> previewOnly = new TreeSet<>();
        data.blockStates().forEach(state -> previewOnly.add(state.getString("name")));
        previewOnly.removeAll(release);
        assertEquals(previewOnly, new TreeSet<>(constants.stream().map(BedrockBlockType::identifier).toList()));
        return constants;
    }

    /**
     * A block only the preview has is its constant: the constant has the preview's states, in the preview's order, and
     * every one of them is the palette's handle of that state and sends the preview's block itself. One block and one
     * set of values is one state object, so constant and palette type are the same.
     */
    @Test
    void everyStateOfAPreviewBlockIsItsConstantsState() throws IllegalAccessException {
        CanonicalBlocks canonical = palette.blocks().canonical();
        int checked = 0;
        for (BedrockBlockType constant : previewBlockConstants()) {
            assertFalse(constant.isAvailable(), constant.identifier());
            assertSame(constant, palette.blockType(constant.identifier(), Lookup.EXACT), constant.identifier());
            List<NbtMap> expected = data.blockStates().stream().filter(s -> s.getString("name").equals(constant.identifier())).toList();
            List<BedrockBlockState> states = constant.states();
            assertEquals(expected.size(), states.size(), constant.identifier());
            for (int i = 0; i < states.size(); i++) {
                BedrockBlockState state = states.get(i);
                NbtMap nbt = expected.get(i);
                assertEquals(canonical.index(nbt), canonical.index(state.toNbt()), state.toString());
                assertSame(state, palette.blockState(nbt, Lookup.EXACT), state.toString());
                assertSame(state, palette.blockState(constant.identifier(), state.values(), Lookup.EXACT), state.toString());
                assertEquals(canonical.index(nbt), Access.vanilla().canonicalId(state, canonical), state.toString());
                ResolvedBlock resolved = palette.resolve(state);
                assertFalse(resolved.fallback(), state.toString());
                assertEquals(canonical.index(nbt), canonical.index(resolved.definition().getState()), state.toString());
                assertEquals(resolved.definition().getRuntimeId(), palette.runtimeId(state), state.toString());
                assertEquals(state, palette.blockState(resolved.definition()), state.toString());
                checked++;
            }
            NbtMap defaultState = data.blockDefaults().get(constant.identifier());
            if (defaultState != null) {
                assertSame(constant.defaultState(), palette.blockState(defaultState, Lookup.EXACT), constant.identifier());
            }
        }
        assertTrue(checked > 0);
    }

    /**
     * Without previews, every state of every preview block resolves through the fallback policy: the block's entry, else
     * the default fallback, else air. Each is resolved once per palette.
     */
    @Test
    void aReleasePaletteSendsThePolicyFallbackOfEveryPreviewBlockState() throws IllegalAccessException {
        Paletter withEntry = Paletter.builder().fallbacks(Fallbacks.builder().block(BedrockBlocks.BRITTLE_ICE, BedrockBlocks.ICE).build()).build();
        Palette entry = withEntry.vanilla(withEntry.latest());
        Palette unknown = Paletter.builder().build().vanilla(withEntry.latest());
        Paletter none = Paletter.builder().fallbacks(Fallbacks.NONE).build();
        Palette air = none.vanilla(none.latest());
        int checked = 0;
        for (BedrockBlockType constant : previewBlockConstants()) {
            String expected = constant == BedrockBlocks.BRITTLE_ICE ? "minecraft:ice" : "minecraft:unknown";
            for (BedrockBlockState state : constant.states()) {
                assertEquals(-1, Access.vanilla().canonicalId(state, entry.blocks().canonical()), state.toString());
                ResolvedBlock resolved = entry.resolve(state);
                assertTrue(resolved.fallback(), state.toString());
                assertEquals(expected, resolved.definition().getState().getString("name"), state.toString());
                assertSame(resolved, entry.resolve(state), "resolved once: " + state);
                assertSame(resolved.definition(), entry.blockDefinition(state), state.toString());
                assertEquals(resolved.definition().getRuntimeId(), entry.runtimeId(state), state.toString());
                assertSame(resolved, entry.resolve(state, null), state.toString());

                assertEquals("minecraft:unknown", unknown.resolve(state).definition().getState().getString("name"), state.toString());
                assertTrue(unknown.resolve(state).fallback(), state.toString());
                assertEquals("minecraft:air", air.resolve(state).definition().getState().getString("name"), state.toString());
                checked++;
            }
        }
        assertTrue(checked > 0);
    }

    /** With previews, a release that lacks a preview block sends what the fallback policy gives for it. */
    @Test
    void anOlderVersionSendsThePolicyFallbackOfAPreviewBlock() {
        Paletter previews = Paletter.builder().previews(true)
            .fallbacks(Fallbacks.builder().block(BedrockBlocks.BRITTLE_ICE, BedrockBlocks.ICE).build()).build();
        Palette newestRelease = previews.vanilla(Paletter.builder().build().latest());
        BedrockBlockState state = BedrockBlocks.BRITTLE_ICE.defaultState();
        assertEquals("minecraft:ice", newestRelease.resolve(state).definition().getState().getString("name"));
        assertTrue(newestRelease.resolve(state).fallback());
        assertEquals("minecraft:brittle_ice", previews.vanilla(previews.latest()).resolve(state).definition().getState().getString("name"));
    }

    /** The resolution of a preview item constant a palette lacks is made once per palette, like those of its own items. */
    @Test
    void aReleasePaletteResolvesAPreviewItemOnce() {
        Paletter withEntry = Paletter.builder().fallbacks(Fallbacks.builder().itemType(BedrockItems.BRITTLE_ICE, BedrockItems.ICE).build()).build();
        Palette release = withEntry.vanilla(withEntry.latest());
        ResolvedItem first = release.items().resolve(BedrockItems.BRITTLE_ICE.base());
        assertSame(first, release.items().resolve(BedrockItems.BRITTLE_ICE.base()));
        assertSame(first, release.items().resolve(BedrockItems.BRITTLE_ICE.base()));
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
    void keysEqualTheReleaseConstantsByValue() {
        for (NbtMap state : data.blockStates()) {
            BedrockBlockState handle = palette.blockState(state, Lookup.EXACT);
            BedrockBlockType release = Access.vanilla().vanillaBlockType(handle.type().identifier());
            if (release != null) {
                // Equality does not depend on previews: the type is its identifier, a state its values
                assertEquals(release, handle.type(), handle.toString());
                assertEquals(release.hashCode(), handle.type().hashCode(), handle.toString());
                BedrockBlockState same = release.stateOf(handle.values());
                if (same != null) {
                    assertEquals(same, handle, handle.toString());
                    assertEquals(same.hashCode(), handle.hashCode(), handle.toString());
                } else {
                    assertFalse(release.states().contains(handle), handle.toString());
                }
            }
        }
        BedrockBlockState scaffolding = palette.blockState(
            data.blockStates().stream().filter(s -> s.getString("name").equals("minecraft:scaffolding")).findFirst().orElseThrow(),
            Lookup.EXACT);
        assertNotSame(BedrockBlocks.SCAFFOLDING, scaffolding.type());
        assertEquals(BedrockBlocks.SCAFFOLDING, scaffolding.type());
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
