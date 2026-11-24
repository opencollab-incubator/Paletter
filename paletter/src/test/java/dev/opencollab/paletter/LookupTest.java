package dev.opencollab.paletter;

import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockBlockType;
import dev.opencollab.paletter.vanilla.BedrockBlocks;
import dev.opencollab.paletter.vanilla.BedrockStates;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import dev.opencollab.paletter.vanilla.BedrockItems;
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
        assertSame(Ids.canonicalBlocks(PALETTER).handle(Ids.block(PALETTER, changed)), mapped);
        // Exact in its own version, then mapped to the newest equivalent
        assertSame(mapped, PALETTER.vanilla(older).blockState(changed, Lookup.EXACT));
        // RENAMED maps it forward on every palette, not only the newest
        for (DataVersion version : PALETTER.versions()) {
            assertSame(mapped, PALETTER.vanilla(version).blockState(changed, Lookup.RENAMED), version.toString());
        }
    }

    @Test
    void renamedDoesNotResetInvalidValues() {
        // A value the block does not have is no match, not its default (as an aux an item lacks is none)
        NbtMap bad = BlockStates.of("minecraft:wheat", java.util.Map.of("growth", 99));
        assertNull(LATEST.blockState(bad, Lookup.RENAMED));
        assertNotNull(LATEST.explainUnknown(bad, Lookup.RENAMED));
        assertNull(LATEST.blockState("minecraft:wheat", java.util.Map.of("growth", 99), Lookup.RENAMED));
        // A key the block does not have anymore is a change of shape: dropped
        NbtMap extra = BlockStates.of("minecraft:wheat", java.util.Map.of("growth", 3, "removed_key", 1));
        assertEquals(BedrockBlocks.WHEAT.state().with(BedrockStates.GROWTH, 3).build(), LATEST.blockState(extra, Lookup.RENAMED));
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
        assertNull(LATEST.explainUnknown(added, Lookup.RENAMED));
        String refused = OLDEST.explainUnknown(added, Lookup.EXACT);
        assertNotNull(refused);
        assertTrue(refused.contains(oldest.toString()), refused);
        assertTrue(refused.contains(BlockStates.name(added)), refused);

        // Nothing has an equivalent: the reason of the mapping is kept
        NbtMap unknown = BlockStates.of("minecraft:paletter_test_unknown");
        assertTrue(LATEST.explainUnknown(unknown, Lookup.EXACT).contains(Ids.forwardBlock(PALETTER, unknown).detail()));
        assertTrue(LATEST.explainUnknown("minecraft:paletter_test_unknown", 3, Lookup.EXACT)
            .contains("does not exist"));
    }

    @Test
    void customContentIsAlwaysAccepted() {
        CustomBlock lamp = CustomBlock.builder("lookup:lamp").build();
        CustomItem ruby = CustomItem.builder("lookup:ruby").menuCategory(CreativeCategory.ITEMS).build();
        Palette palette = LATEST.extend(PaletteLayer.builder("lookup").block(lamp).item(ruby).build());
        for (Lookup lookup : Lookup.values()) {
            assertSame(lamp.type().defaultState(), palette.blockState(lamp.type().defaultState().toNbt(), lookup));
            assertSame(ruby.type().variant(), palette.itemVariant("lookup:ruby", 0, lookup));
            assertSame(ruby.type(), palette.itemType("lookup:ruby", lookup));
        }
        assertNull(LATEST.blockState(lamp.type().defaultState().toNbt(), Lookup.EXACT));
    }

    @Test
    void anExtendedPaletteLooksUpLikeItsParentAndSharesTheTables() {
        CustomBlock lamp = CustomBlock.builder("lookup:shared").build();
        for (Palette base : new Palette[] {LATEST, OLDEST}) {
            Palette extended = base.extend(PaletteLayer.builder("shared").block(lamp).build());
            assertTrue(extended.blocks().extendsStatesOf(base.blocks()));
            assertSame(base.versionItems(), extended.versionItems());
            NbtMap stone = BedrockBlocks.STONE.defaultState().toNbt();
            assertSame(base.blockState(stone, Lookup.EXACT), extended.blockState(stone, Lookup.EXACT));
            assertSame(base.itemVariant("minecraft:apple", 0, Lookup.EXACT), extended.itemVariant("minecraft:apple", 0, Lookup.EXACT));
        }
    }

    @Test
    void blockTypesMatchExactly() {
        assertSame(BedrockBlocks.STONE, LATEST.blockType("minecraft:stone", Lookup.EXACT));
        assertNull(LATEST.blockType("minecraft:paletter_test_unknown", Lookup.EXACT));
        assertNull(LATEST.blockType("minecraft:paletter_test_unknown", Lookup.RENAMED));
        for (BedrockBlockType type : BedrockBlockType.vanillaTypes()) {
            BedrockBlockType found = LATEST.blockType(type.identifier(), Lookup.EXACT);
            if (found != null) {
                assertSame(type, found, type.identifier());
            }
        }
    }

    @Test
    void customBlockTypesAreFound() {
        CustomBlock lamp = CustomBlock.builder("lookup:lamp").property(dev.opencollab.paletter.block.BlockProperty.bool("lookup:lit")).build();
        Palette extended = LATEST.extend(PaletteLayer.builder("custom-types").block(lamp).build());
        for (Lookup lookup : Lookup.values()) {
            assertSame(lamp.type(), extended.blockType("lookup:lamp", lookup), lookup.toString());
            assertNull(LATEST.blockType("lookup:lamp", lookup), lookup.toString());
        }
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
        assertSame(BedrockItems.APPLE.variant(), LATEST.itemVariant("minecraft:apple", 0, Lookup.EXACT));
        assertSame(BedrockItems.POTION.variant(5), LATEST.itemVariant("minecraft:potion", 5, Lookup.EXACT));
        assertNull(LATEST.itemVariant("minecraft:potion", 9999, Lookup.EXACT));
        assertNull(LATEST.itemVariant("minecraft:paletter_test_unknown", 0, Lookup.RENAMED));
        for (BedrockItemType type : BedrockItemType.vanillaTypes()) {
            // Items behind an experiment toggle are not in the palette's version unless it enables the toggle
            if (LATEST.itemDefinition(type.identifier()) != null) {
                assertSame(type.variant(), LATEST.itemVariant(type.identifier(), 0, Lookup.EXACT), type.identifier());
            }
        }
    }

    @Test
    void itemTypesMatchExactly() {
        assertSame(BedrockItems.APPLE, LATEST.itemType("minecraft:apple", Lookup.EXACT));
        assertNull(LATEST.itemType("minecraft:paletter_test_unknown", Lookup.EXACT));
    }
}
