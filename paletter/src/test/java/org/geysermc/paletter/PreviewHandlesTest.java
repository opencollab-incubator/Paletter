package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.internal.CanonicalBlocks;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.data.VanillaItem;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockBlockType;
import org.geysermc.paletter.vanilla.BedrockBlocks;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
