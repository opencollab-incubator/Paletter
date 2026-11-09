package org.geysermc.paletter;

import org.geysermc.paletter.internal.Forwarding;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.geysermc.paletter.block.BlockNetworkIds;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.item.ItemVariant;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.item.ResolvedItem;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalTest {
    private static final Paletter PALETTER = Paletter.bundled();
    private static final int LATEST = 2225;
    private static final int OLDEST = 2168;

    private static CustomBlock lamp() {
        return CustomBlock.builder("test:lamp")
            .property(BlockProperty.bool("test:lit"))
            .creative(CreativePlacement.loose(CreativeCategory.ITEMS))
            .build();
    }

    @Test
    void hashMatchesTheIdsBdsSends() {
        // network_id values from BDS block palette dumps (26.60)
        assertEquals(-604749536, BlockNetworkIds.hash(BlockStates.of("minecraft:air")));
        assertEquals(-2, BlockNetworkIds.hash(BlockStates.of("minecraft:unknown")));
        // State order must not matter: the hash sorts by key
        NbtMap stairs = BlockStates.of("minecraft:oak_stairs", NbtMap.builder()
            .putInt("weirdo_direction", 1)
            .putByte("upside_down_bit", (byte) 0)
            .putString("minecraft:corner", "none")
            .build());
        assertEquals(-61009414, BlockNetworkIds.hash(stairs));
        assertEquals(-61009414, BlockNetworkIds.hash(stairs.toBuilder().putInt("version", 18168865).build()));
    }

    @Test
    void canonicalBlocksFollowTheNewestVanillaPalette() {
        Palette latest = PALETTER.vanilla(PALETTER.latest());
        assertEquals(latest.blocks().size(), Ids.blockCount(PALETTER));
        assertEquals(Ids.blockCount(PALETTER), Ids.blockCount(latest));
        for (int i = 0; i < Ids.blockCount(latest); i++) {
            assertEquals(i, Ids.runtimeId(latest, i));
            assertSame(latest.blocks().byRuntimeId(i), Ids.blockAt(latest, i));
            assertEquals(i, latest.blocks().canonical(Ids.blockAt(latest, i)));
        }
        NbtMap withExtras = BlockStates.of("minecraft:air").toBuilder().putInt("version", 1).build();
        assertEquals(latest.blocks().air().getRuntimeId(), Ids.block(PALETTER, withExtras));
        assertEquals(-1, Ids.block(PALETTER, BlockStates.of("test:nope")));
    }

    @Test
    void olderVersionsResolveEveryCanonicalBlock() {
        for (DataVersion version : PALETTER.versions()) {
            Palette palette = PALETTER.vanilla(version);
            for (int i = 0; i < Ids.blockCount(palette); i++) {
                RegisteredBlockState resolved = palette.blocks().resolve(Ids.canonicalBlocks(palette).state(i)).block();
                // Blocks a version lacks fall back to minecraft:unknown by default
                assertSame(resolved == null ? palette.blocks().defaultState("minecraft:unknown") : resolved, Ids.blockAt(palette, i));
                assertEquals(Ids.blockAt(palette, i).getRuntimeId(), Ids.runtimeId(palette, i));
            }
            // Every block of the version maps back to a canonical id resolving to it, unless nothing resolves to it
            int unmapped = 0;
            for (int i = 0; i < palette.blocks().size(); i++) {
                RegisteredBlockState block = palette.blocks().byIndex(i);
                int canonical = palette.blocks().canonical(block);
                if (canonical == -1) {
                    unmapped++;
                } else if (Ids.forwardBlock(PALETTER, block.getState()).kind() == Forwarding.Kind.EXACT && canonical == Ids.block(PALETTER, block.getState())) {
                    assertEquals(block.getState(), Ids.canonicalBlocks(palette).state(canonical));
                } else {
                    assertSame(block, Ids.blockAt(palette, canonical));
                }
            }
            System.out.println(version + ": " + palette.blocks().missingCanonical() + " canonical states without a block, "
                + unmapped + " states without a canonical id");
        }
    }

    @Test
    void hashedIdsAreEqualAcrossVersions() {
        Palette oldest = PALETTER.palette(OLDEST, BlockNetworkIds.HASHED);
        Palette latest = PALETTER.palette(LATEST, BlockNetworkIds.HASHED);
        Palette sequential = PALETTER.palette(OLDEST);
        int shared = 0;
        int differentSequential = 0;
        for (int i = 0; i < latest.blocks().size(); i++) {
            RegisteredBlockState block = latest.blocks().byIndex(i);
            RegisteredBlockState old = oldest.blocks().exact(block.getState());
            assertEquals(BlockNetworkIds.hash(block.getState()), block.getRuntimeId());
            if (old != null) {
                shared++;
                assertEquals(block.getRuntimeId(), old.getRuntimeId(), block.toString());
                if (i != sequential.blocks().exact(block.getState()).getRuntimeId()) {
                    differentSequential++;
                }
            }
        }
        assertTrue(shared > 10000, "only " + shared + " states in common");
        assertTrue(differentSequential > 0, "sequential ids should have shifted between versions");

        // The registry is keyed by hash
        RegisteredBlockState air = latest.blocks().air();
        assertEquals(-604749536, air.getRuntimeId());
        assertSame(air, latest.blocks().getDefinition(-604749536));
        assertSame(air, Ids.blockAt(latest, Ids.block(PALETTER, BlockStates.of("minecraft:air"))));
        assertEquals(Ids.block(PALETTER, BlockStates.of("minecraft:air")), latest.blocks().canonicalOfRuntimeId(-604749536));

        StartGamePacket packet = new StartGamePacket();
        latest.applyTo(packet);
        assertTrue(packet.isBlockNetworkIdsHashed());
        StartGamePacket sequentialPacket = new StartGamePacket();
        sequential.applyTo(sequentialPacket);
        assertFalse(sequentialPacket.isBlockNetworkIdsHashed());
    }

    @Test
    void customBlocksGetAppendedCanonicalIdsAndHashedIds() {
        PaletteLayer layer = PaletteLayer.builder("blocks").block(lamp()).build();
        Palette hashed = PALETTER.palette(LATEST, BlockNetworkIds.HASHED, layer);
        Palette sequential = PALETTER.palette(OLDEST, layer);
        NbtMap lit = BlockStates.of("test:lamp", Map.of("test:lit", (byte) 1));

        int canonical = Ids.block(hashed, lit);
        assertTrue(canonical >= Ids.blockCount(PALETTER), "custom states are appended");
        assertEquals(Ids.blockCount(PALETTER) + 2, Ids.blockCount(hashed));
        assertEquals(canonical, Ids.block(sequential, lit), "same layer, same canonical ids in every version and mode");
        // Same instance for every version: compiled once per layer
        assertSame(Ids.canonicalBlocks(hashed), Ids.canonicalBlocks(sequential));

        assertEquals(BlockNetworkIds.hash(lit), Ids.runtimeId(hashed, canonical));
        assertFalse(hashed.blocks().isRemapped());
        assertTrue(sequential.blocks().isRemapped());
        assertEquals(lit, Ids.blockAt(sequential, canonical).getState());
        // Vanilla states keep their canonical ids, while their sequential runtime ids shifted
        int air = Ids.block(PALETTER, BlockStates.of("minecraft:air"));
        assertSame(sequential.blocks().air(), Ids.blockAt(sequential, air));
    }

    @Test
    void hashCollisionsFailCompilation() {
        // Birthday search for two block names with the same hash; ~2^16 tries on average
        Int2ObjectMap<String> seen = new Int2ObjectOpenHashMap<>();
        String first = null;
        String second = null;
        for (int i = 0; first == null; i++) {
            String name = "test:block_" + i;
            String previous = seen.put(BlockNetworkIds.hash(BlockStates.of(name)), name);
            if (previous != null) {
                first = previous;
                second = name;
            }
        }
        PaletteLayer layer = PaletteLayer.builder("colliding")
            .block(CustomBlock.builder(first).build())
            .block(CustomBlock.builder(second).build())
            .build();
        PaletteException exception = assertThrows(PaletteException.class, () -> PALETTER.palette(LATEST, BlockNetworkIds.HASHED, layer));
        assertTrue(exception.getMessage().contains("collision"), exception.getMessage());
        // Sequential ids do not care
        assertNotNull(PALETTER.palette(LATEST, layer));
    }

    @Test
    void everyCanonicalItemRoundTripsThroughMatch() {
        for (DataVersion version : PALETTER.versions()) {
            for (BlockNetworkIds mode : BlockNetworkIds.values()) {
                Palette palette = PALETTER.vanilla(version, mode);
                int mismatches = 0;
                int missing = 0;
                for (int i = 0; i < Ids.itemCount(palette); i++) {
                    ResolvedItem item = Ids.itemAt(palette, i);
                    if (item == null) {
                        missing++;
                        continue;
                    }
                    int matched = Ids.match(palette, item.toItemData().build());
                    if (matched != i) {
                        mismatches++;
                        // Only allowed if both resolve to exactly the same stack (e.g. fallbacks or adapted states)
                        ResolvedItem other = Ids.itemAt(palette, matched);
                        assertSame(item.definition(), other.definition(), Ids.canonicalItems(palette).variant(i).toString());
                        assertEquals(item.damage(), other.damage());
                        assertSame(item.block(), other.block(), Ids.canonicalItems(palette).variant(i) + " vs " + Ids.canonicalItems(palette).variant(matched));
                    }
                }
                if (version.equals(PALETTER.latest())) {
                    assertEquals(0, mismatches + missing, "every variant of the newest version is its own stack");
                }
                System.out.println(version + " " + mode + ": " + Ids.itemCount(palette) + " canonical items, "
                    + missing + " missing, " + mismatches + " share a stack with another");
            }
        }
    }

    @Test
    void blockItemsMatchByDefinitionAndNormalizeTheirDefaultState() {
        Palette palette = PALETTER.vanilla(PALETTER.latest(), BlockNetworkIds.HASHED);
        int planks = Ids.item(PALETTER, ItemVariant.of("minecraft:oak_planks"));
        ResolvedItem resolved = Ids.itemAt(palette, planks);
        assertEquals("minecraft:oak_planks", resolved.block().identifier());
        assertEquals(planks, Ids.match(palette, resolved.toItemData().build()));
        // Vanilla has no item placing several blocks in the creative inventory, so the block only matters for
        // variants that name one; spelling out the default state is the same variant
        assertEquals(planks, Ids.item(PALETTER, new ItemVariant("minecraft:oak_planks", 0, resolved.block().getState())));
        assertEquals(planks, Ids.match(palette, resolved.toItemData().blockDefinition(palette.blocks().air()).build()));
    }

    @Test
    void layersAddCanonicalVariants() {
        Palette latest = PALETTER.vanilla(PALETTER.latest());
        RegisteredBlockState log = latest.blocks().states("minecraft:oak_log").stream()
            .filter(state -> state != latest.items().defaultBlock(latest.items().getDefinition("minecraft:oak_log")))
            .findFirst().orElseThrow();
        ItemVariant apple3 = ItemVariant.of("minecraft:apple", 3);
        ItemVariant sidewaysLog = new ItemVariant("minecraft:oak_log", 0, log.getState());
        assertEquals(-1, Ids.item(PALETTER, apple3));
        assertEquals(-1, Ids.item(PALETTER, sidewaysLog));
        PaletteLayer layer = PaletteLayer.builder("variants")
            .variant(apple3)
            .variant(ItemVariant.of("minecraft:apple"))     // already canonical: keeps its id, nothing appended
            .variant(sidewaysLog)
            .variant(apple3)                                // duplicates are ignored
            .build();

        int vanilla = Ids.itemCount(PALETTER);
        for (BlockNetworkIds mode : BlockNetworkIds.values()) {
            for (DataVersion version : PALETTER.versions()) {
                Palette palette = PALETTER.palette(version.protocolVersion(), mode, layer);
                assertEquals(vanilla + 2, Ids.itemCount(palette));
                assertEquals(vanilla, Ids.item(palette, apple3), "stable across versions and modes");
                assertEquals(vanilla + 1, Ids.item(palette, sidewaysLog));
                assertEquals(Ids.item(PALETTER, ItemVariant.of("minecraft:apple")), Ids.item(palette, ItemVariant.of("minecraft:apple")));

                RegisteredItem apple = palette.items().getDefinition("minecraft:apple");
                assertEquals(3, Ids.itemAt(palette, vanilla).damage());
                assertEquals(vanilla, Ids.match(palette, ItemData.builder().definition(apple).damage(3).count(1).build()));
                assertEquals(Ids.item(PALETTER, ItemVariant.of("minecraft:apple")),
                    Ids.match(palette, ItemData.builder().definition(apple).damage(4).count(1).build()));

                // Block state variants are told apart by the block the client sends
                ResolvedItem logItem = Ids.itemAt(palette, vanilla + 1);
                assertEquals(log.getState(), logItem.block().getState(), version.toString());
                assertEquals(vanilla + 1, Ids.match(palette, logItem.toItemData().build()));
                int baseLog = Ids.item(PALETTER, ItemVariant.of("minecraft:oak_log"));
                assertEquals(baseLog, Ids.match(palette, Ids.itemAt(palette, baseLog).toItemData().build()));
            }
        }
    }

    @Test
    void missingCanonicalContentIsReported() {
        Palette oldest = PALETTER.vanilla(PALETTER.version(OLDEST));
        // No fallbacks are bundled: every state of a block the oldest version lacks is without a block there
        long lacking = PALETTER.vanillaData(PALETTER.latest()).blockStates().stream()
            .filter(state -> !oldest.blocks().contains(BlockStates.name(state))).count();
        assertTrue(lacking > 0);
        assertTrue(oldest.blocks().missingCanonical().size() >= lacking, oldest.blocks().missingCanonical().size() + " vs " + lacking);
        assertEquals(lacking, oldest.blocks().missingCanonical().stream()
            .filter(state -> !oldest.blocks().contains(BlockStates.name(state))).count());
        assertTrue(oldest.report().entries().stream().anyMatch(entry -> entry.source().equals("canonical")
            && entry.message().contains("canonical block states")), oldest.report().entries().toString());
        assertTrue(oldest.report().entries().stream().anyMatch(entry -> entry.source().equals("canonical")
            && entry.message().contains("canonical item variants")));
        // Not repeated by layers that do not change it
        Palette layered = PALETTER.palette(OLDEST, PaletteLayer.builder("empty").build());
        assertEquals(oldest.report().entries().size(), layered.report().entries().size());
        // The newest version has everything
        assertTrue(PALETTER.vanilla(PALETTER.latest()).report().entries().stream().noneMatch(entry -> entry.source().equals("canonical")));
    }

    @Test
    void hashedPalettesShareVanillaBlocksAndTablesAcrossVersions() {
        Palette oldest = PALETTER.vanilla(PALETTER.version(OLDEST), BlockNetworkIds.HASHED);
        Palette latest = PALETTER.vanilla(PALETTER.latest(), BlockNetworkIds.HASHED);
        Palette withBlocks = PALETTER.palette(LATEST, BlockNetworkIds.HASHED, PaletteLayer.builder("lamp").block(lamp()).build());
        int air = Ids.block(PALETTER, BlockStates.of("minecraft:air"));
        assertSame(Ids.blockAt(latest, air), Ids.blockAt(oldest, air));
        assertSame(Ids.blockAt(latest, air), Ids.blockAt(withBlocks, air));
        int shared = 0;
        for (int i = 0; i < Ids.blockCount(latest); i++) {
            if (Ids.blockAt(latest, i) == Ids.blockAt(oldest, i)) {
                shared++;
            }
            assertSame(Ids.blockAt(latest, i), Ids.blockAt(withBlocks, i), "custom blocks do not change vanilla hashed definitions");
        }
        // Every state 26.40 has unchanged (about two thirds) uses the same instance
        assertTrue(shared > Ids.blockCount(latest) / 2, shared + " shared");
        // Registries still only know their own version's states
        RegisteredBlockState newOnly = Ids.blockAt(latest, Ids.block(PALETTER, latest.blocks().states("minecraft:white_concrete_stairs").getFirst().getState()));
        assertTrue(latest.blocks().isRegistered(newOnly));
        assertFalse(oldest.blocks().isRegistered(newOnly));
        assertEquals(-1, oldest.blocks().indexOf(newOnly));
    }
}
