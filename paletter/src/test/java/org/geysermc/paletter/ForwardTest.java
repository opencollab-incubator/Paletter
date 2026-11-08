package org.geysermc.paletter;

import org.geysermc.paletter.internal.Forwarding;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataSource;
import org.geysermc.paletter.data.Renames;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.item.ItemVariant;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Content of any bundled version maps to the canonical ids of the newest one.
 */
class ForwardTest {
    private final Paletter paletter = Paletter.bundled();

    @Test
    void everyStateOfEveryVersionHasACanonicalId() {
        Set<NbtMap> newest = new HashSet<>();
        paletter.vanillaData(paletter.latest()).blockStates().forEach(state -> newest.add(BlockStates.normalize(state)));
        int adapted = 0;
        for (DataVersion version : paletter.versions()) {
            for (NbtMap state : paletter.vanillaData(version).blockStates()) {
                Forwarding forwarding = Ids.forwardBlock(paletter, state);
                assertTrue(forwarding.known(), version + " " + state + ": " + forwarding.detail());
                if (newest.contains(BlockStates.normalize(state))) {
                    assertEquals(Forwarding.Kind.EXACT, forwarding.kind());
                } else {
                    adapted++;
                    assertEquals(Forwarding.Kind.ADAPTED, forwarding.kind(), state.toString());
                    assertEquals(BlockStates.name(state), BlockStates.name(Ids.canonicalBlocks(paletter).state(forwarding.canonical())));
                    assertEquals(forwarding.canonical(), Ids.block(paletter, state));
                }
            }
        }
        assertTrue(adapted > 0, "the bundled versions differ in block states");
    }

    @Test
    void statesOfAnOlderVersionKeepTheirValues() {
        // A state of the previous version the newest one lacks keeps every value the newest block still has
        DataVersion previous = paletter.versions().get(paletter.versions().size() - 2);
        Set<NbtMap> newest = new HashSet<>();
        paletter.vanillaData(paletter.latest()).blockStates().forEach(state -> newest.add(BlockStates.normalize(state)));
        List<NbtMap> only = paletter.vanillaData(previous).blockStates().stream()
            .filter(state -> !newest.contains(BlockStates.normalize(state))).toList();
        assertTrue(!only.isEmpty(), "states of " + previous + " that the newest version lacks");
        for (NbtMap state : only) {
            NbtMap forwarded = Ids.canonicalBlocks(paletter).state(Ids.block(paletter, state));
            NbtMap states = BlockStates.states(state);
            BlockStates.states(forwarded).forEach((key, value) -> {
                if (states.containsKey(key) && Ids.canonicalBlocks(paletter).index(BlockStates.with(forwarded, key, states.get(key))) != -1) {
                    // a value the old state had and the newest block allows in this combination is kept
                    assertEquals(states.get(key), value, state + " -> " + forwarded + " at " + key);
                }
            });
        }
    }

    @Test
    void removedBlocksAreUnknownWithAReason() {
        NbtMap gone = BlockStates.of("minecraft:removed_in_a_later_version");
        Forwarding forwarding = Ids.forwardBlock(paletter, gone);
        assertEquals(-1, Ids.block(paletter, gone));
        assertEquals(Forwarding.Kind.UNKNOWN, forwarding.kind());
        assertTrue(forwarding.detail().contains("minecraft:removed_in_a_later_version"));
        assertEquals(forwarding.detail(), paletter.explainUnknown(gone));
        assertNull(paletter.explainUnknown(BlockStates.of("minecraft:air")));
        assertNotNull(paletter.explainUnknown(ItemVariant.of("minecraft:removed_in_a_later_version")));
    }

    @Test
    void itemsOfAnyVersionMapForward() {
        for (DataVersion version : paletter.versions()) {
            VanillaData data = paletter.vanillaData(version);
            data.items().forEach(item -> assertNotEquals(-1, Ids.item(paletter, ItemVariant.of(item.identifier())), version + " " + item.identifier()));
            data.creative().forEachStack(stack -> {
                ItemVariant variant = stack.variant();
                int id = Ids.item(paletter, variant);
                if (id == -1) {
                    // only variants of the newest version are allowed to be unknown (creative changed): say why
                    assertNotNull(paletter.explainUnknown(variant), variant.toString());
                }
            });
        }
    }

    @Test
    void renamedBlocksAndItemsMapToTheirNewNames() {
        PaletteDataSource bundled = PaletteDataSource.bundled();
        Paletter renaming = Paletter.create(new PaletteDataSource() {
            @Override
            public @NonNull List<DataVersion> versions() {
                return bundled.versions();
            }

            @Override
            public @NonNull VanillaData load(@NonNull DataVersion version) throws IOException {
                return bundled.load(version);
            }

            @Override
            public @NonNull Renames renames() {
                return Renames.parse(List.of("block minecraft:old_stone minecraft:stone", "block minecraft:older_stone minecraft:old_stone",
                    "item minecraft:old_stick minecraft:stick", "item minecraft:gone minecraft:also_gone"));
            }
        });
        Forwarding block = Ids.forwardBlock(renaming, BlockStates.of("minecraft:older_stone"));
        assertEquals(Forwarding.Kind.RENAMED, block.kind());
        assertEquals(Ids.block(renaming, BlockStates.of("minecraft:stone", java.util.Map.of("stone_type", "stone"))), block.canonical());
        assertEquals(Ids.item(renaming, ItemVariant.of("minecraft:stick")), Ids.item(renaming, ItemVariant.of("minecraft:old_stick")));
        Forwarding gone = Ids.forwardItem(renaming, ItemVariant.of("minecraft:gone"));
        assertEquals(Forwarding.Kind.UNKNOWN, gone.kind());
        assertTrue(gone.detail().contains("also_gone"), gone.detail());
    }

    @Test
    void paletteForwardsLikePaletter() {
        Palette palette = paletter.vanilla(paletter.latest());
        NbtMap state = paletter.vanillaData(paletter.versions().getFirst()).blockStates().stream()
            .filter(s -> Ids.forwardBlock(paletter, s).kind() == Forwarding.Kind.ADAPTED).findFirst().orElseThrow();
        assertEquals(Ids.block(paletter, state), Ids.block(palette, state));
        assertEquals(Ids.forwardBlock(paletter, state), Ids.forwardBlock(palette, state));
    }
}
