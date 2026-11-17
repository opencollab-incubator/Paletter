package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.vanilla.BedrockBlockState;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookupTest {
    private static final Paletter PALETTER = Paletter.bundled();
    private static final Palette LATEST = PALETTER.vanilla(PALETTER.latest());
    private static final Palette OLDEST = PALETTER.vanilla(PALETTER.versions().getFirst());

    @Test
    void exactIsExactInThePalettesVersion() {
        NbtMap stone = BedrockBlocks.STONE.defaultState().toNbt();
        assertSame(BedrockBlocks.STONE.defaultState(), LATEST.blockState(stone, Lookup.EXACT));
        assertNull(LATEST.blockState(BlockStates.of("minecraft:paletter_test_unknown"), Lookup.EXACT));
        assertNull(LATEST.blockState(BlockStates.of("minecraft:paletter_test_unknown"), Lookup.RENAMED));
    }

    @Test
    void renamedAcceptsWhatThePalettesVersionChanged() {
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

        assertNull(LATEST.blockState(changed, Lookup.EXACT));
        BedrockBlockState mapped = LATEST.blockState(changed, Lookup.RENAMED);
        assertNotNull(mapped);
        assertSame(LATEST.block(changed), mapped);
        // Exact in its own version, then mapped to the newest equivalent
        assertSame(mapped, PALETTER.vanilla(older).blockState(changed, Lookup.EXACT));
    }

    @Test
    void thePalettesVersionStillGatesWhatTheNewestVersionHasAsIs() {
        DataVersion oldest = PALETTER.versions().getFirst();
        Set<NbtMap> old = new HashSet<>();
        PALETTER.vanillaData(oldest).blockStates().forEach(state -> old.add(BlockStates.normalize(state)));
        NbtMap added = PALETTER.vanillaData(PALETTER.latest()).blockStates().stream()
            .filter(state -> !old.contains(BlockStates.normalize(state))).findFirst().orElseThrow();

        assertNull(OLDEST.blockState(added, Lookup.EXACT));
        assertNull(OLDEST.blockState(added, Lookup.RENAMED));
        assertNotNull(LATEST.blockState(added, Lookup.EXACT));

        Set<String> oldItems = new HashSet<>();
        PALETTER.vanillaData(oldest).items().forEach(item -> oldItems.add(item.identifier()));
        String addedItem = PALETTER.vanillaData(PALETTER.latest()).items().stream().map(item -> item.identifier())
            .filter(identifier -> !oldItems.contains(identifier)).findFirst().orElseThrow();
        assertNull(OLDEST.itemVariant(addedItem, 0, Lookup.RENAMED));
        assertNotNull(LATEST.itemVariant(addedItem, 0, Lookup.EXACT));
    }

    @Test
    void explanationsNameTheLookup() {
        NbtMap stone = BedrockBlocks.STONE.defaultState().toNbt();
        assertNull(LATEST.explainUnknown(stone, Lookup.EXACT));
        assertNull(LATEST.explainUnknown("minecraft:apple", 0, Lookup.EXACT));

        DataVersion oldest = PALETTER.versions().getFirst();
        Set<NbtMap> old = new HashSet<>();
        PALETTER.vanillaData(oldest).blockStates().forEach(state -> old.add(BlockStates.normalize(state)));
        NbtMap added = PALETTER.vanillaData(PALETTER.latest()).blockStates().stream()
            .filter(state -> !old.contains(BlockStates.normalize(state))).findFirst().orElseThrow();
        // The newest version has it, the oldest palette refuses it: the message says which data it asked
        assertNull(LATEST.explainUnknown(added));
        String refused = OLDEST.explainUnknown(added, Lookup.EXACT);
        assertNotNull(refused);
        assertTrue(refused.contains(oldest.toString()), refused);
        assertTrue(refused.contains(BlockStates.name(added)), refused);

        // Nothing has an equivalent: the reason of the mapping is kept
        NbtMap unknown = BlockStates.of("minecraft:paletter_test_unknown");
        assertTrue(LATEST.explainUnknown(unknown, Lookup.EXACT).contains(LATEST.explainUnknown(unknown)));
        assertTrue(LATEST.explainUnknown("minecraft:paletter_test_unknown", 3, Lookup.EXACT)
            .contains(LATEST.explainUnknown("minecraft:paletter_test_unknown", 3)));
    }

    @Test
    void versionsAreFoundByName() {
        for (DataVersion version : PALETTER.versions()) {
            assertSame(version, PALETTER.version(version.name()));
        }
        assertNull(PALETTER.version("0.0"));
    }

    @Test
    void itemsMatchExactly() {
        assertSame(BedrockItems.APPLE.base(), LATEST.itemVariant("minecraft:apple", 0, Lookup.EXACT));
        assertSame(BedrockItems.POTION.variant(5), LATEST.itemVariant("minecraft:potion", 5, Lookup.EXACT));
        assertNull(LATEST.itemVariant("minecraft:potion", 9999, Lookup.EXACT));
        assertNull(LATEST.itemVariant("minecraft:paletter_test_unknown", 0, Lookup.RENAMED));
        for (BedrockItemType type : BedrockItemType.vanillaTypes()) {
            // Items behind an experiment toggle are not in the palette's version unless it enables the toggle
            if (LATEST.itemDefinition(type) != null) {
                assertSame(type.base(), LATEST.itemVariant(type.identifier(), 0, Lookup.EXACT), type.identifier());
            }
        }
    }

    @Test
    void itemTypesMatchExactly() {
        assertSame(BedrockItems.APPLE, LATEST.itemType("minecraft:apple", Lookup.EXACT));
        assertNull(LATEST.itemType("minecraft:paletter_test_unknown", Lookup.EXACT));
    }
}
