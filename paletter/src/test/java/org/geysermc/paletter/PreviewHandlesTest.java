package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.internal.CanonicalBlocks;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.data.VanillaItem;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
