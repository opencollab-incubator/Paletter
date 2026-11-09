package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockBlockType;
import org.geysermc.paletter.vanilla.BedrockBlocks;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.geysermc.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LookupTest {
    private static final Paletter PALETTER = Paletter.bundled();

    @Test
    void strictIsExactInTheNewestVersion() {
        NbtMap stone = BedrockBlocks.STONE.defaultState().toNbt();
        assertSame(BedrockBlocks.STONE.defaultState(), PALETTER.block(stone, Lookup.STRICT));
        assertSame(BedrockBlocks.STONE.defaultState(), PALETTER.block(stone, Lookup.of(PALETTER.latest())));
        assertNull(PALETTER.block(BlockStates.of("minecraft:paletter_test_unknown"), Lookup.STRICT));
        assertNull(PALETTER.block(BlockStates.of("minecraft:paletter_test_unknown"), Lookup.STRICT.withRenames()));
    }

    @Test
    void renamesAcceptWhatTheNewestVersionChanged() {
        // A state of an older version that the newest does not have as is
        Set<NbtMap> newest = new HashSet<>();
        PALETTER.vanillaData(PALETTER.latest()).blockStates().forEach(state -> newest.add(BlockStates.normalize(state)));
        DataVersion older = null;
        NbtMap changed = null;
        for (DataVersion version : PALETTER.versions()) {
            for (NbtMap state : PALETTER.vanillaData(version).blockStates()) {
                if (changed == null && !newest.contains(BlockStates.normalize(state))) {
                    older = version;
                    changed = state;
                }
            }
        }
        assertNotNull(changed, "the bundled versions differ in block states");

        assertNull(PALETTER.block(changed, Lookup.STRICT));
        BedrockBlockState mapped = PALETTER.block(changed, Lookup.STRICT.withRenames());
        assertNotNull(mapped);
        assertSame(PALETTER.block(changed), mapped);
        // Exact in its own version, then mapped to the newest equivalent
        assertSame(mapped, PALETTER.block(changed, Lookup.of(older)));
    }

    @Test
    void itemsMatchExactly() {
        assertSame(BedrockItems.APPLE.base(), PALETTER.item("minecraft:apple", 0, Lookup.STRICT));
        assertSame(BedrockItems.POTION.variant(5), PALETTER.item("minecraft:potion", 5, Lookup.STRICT));
        assertNull(PALETTER.item("minecraft:potion", 9999, Lookup.STRICT));
        assertNull(PALETTER.item("minecraft:paletter_test_unknown", 0, Lookup.STRICT.withRenames()));
        for (BedrockItemType type : BedrockItemType.vanillaTypes()) {
            assertSame(type.base(), PALETTER.item(type.identifier(), 0, Lookup.STRICT), type.identifier());
        }
    }

    @Test
    void typesMatchExactly() {
        assertSame(BedrockBlocks.STONE, PALETTER.blockType("minecraft:stone", Lookup.STRICT));
        assertSame(BedrockItems.APPLE, PALETTER.itemType("minecraft:apple", Lookup.STRICT));
        assertNull(PALETTER.blockType("minecraft:paletter_test_unknown", Lookup.STRICT.withRenames()));
        assertNull(PALETTER.itemType("minecraft:paletter_test_unknown", Lookup.STRICT));
        for (BedrockBlockType type : BedrockBlockType.vanillaTypes()) {
            assertSame(type, PALETTER.blockType(type.identifier(), Lookup.STRICT), type.identifier());
        }
    }

    @Test
    void versionsMustBeServed() {
        Lookup unknown = Lookup.of(new DataVersion("1.0", 1));
        NbtMap stone = BedrockBlocks.STONE.defaultState().toNbt();
        assertThrows(IllegalArgumentException.class, () -> PALETTER.block(stone, unknown));
        assertThrows(IllegalArgumentException.class, () -> PALETTER.item("minecraft:apple", 0, unknown));
    }
}
