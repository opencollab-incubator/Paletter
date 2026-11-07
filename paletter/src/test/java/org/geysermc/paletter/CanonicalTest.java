package org.geysermc.paletter;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.geysermc.paletter.block.BlockNetworkIds;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.PaletteBlock;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.item.ItemVariant;
import org.geysermc.paletter.item.PaletteItem;
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
        assertEquals(latest.blocks().size(), PALETTER.canonicalBlockCount());
        assertEquals(PALETTER.canonicalBlockCount(), latest.canonicalBlockCount());
        for (int i = 0; i < latest.canonicalBlockCount(); i++) {
            assertEquals(i, latest.runtimeId(i));
            assertSame(latest.blocks().byRuntimeId(i), latest.block(i));
            assertEquals(i, latest.blocks().canonical(latest.block(i)));
        }
        NbtMap withExtras = BlockStates.of("minecraft:air").toBuilder().putInt("version", 1).build();
        assertEquals(latest.blocks().air().getRuntimeId(), PALETTER.canonicalBlock(withExtras));
        assertEquals(-1, PALETTER.canonicalBlock(BlockStates.of("test:nope")));
    }

    @Test
    void olderVersionsResolveEveryCanonicalBlock() {
        for (DataVersion version : PALETTER.versions()) {
            Palette palette = PALETTER.vanilla(version);
            for (int i = 0; i < palette.canonicalBlockCount(); i++) {
                PaletteBlock resolved = palette.blocks().resolve(palette.canonicalBlocks().state(i)).block();
                // Blocks a version lacks fall back to minecraft:unknown by default
                assertSame(resolved == null ? palette.blocks().defaultState("minecraft:unknown") : resolved, palette.block(i));
                assertEquals(palette.block(i).getRuntimeId(), palette.runtimeId(i));
            }
            // Every block of the version maps back to a canonical id resolving to it, unless nothing resolves to it
            int unmapped = 0;
            for (int i = 0; i < palette.blocks().size(); i++) {
                PaletteBlock block = palette.blocks().byIndex(i);
                int canonical = palette.blocks().canonical(block);
                if (canonical == -1) {
                    unmapped++;
                } else if (canonical == PALETTER.canonicalBlock(block.getState())) {
                    assertEquals(block.getState(), palette.canonicalBlocks().state(canonical));
                } else {
                    assertSame(block, palette.block(canonical));
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
            PaletteBlock block = latest.blocks().byIndex(i);
            PaletteBlock old = oldest.blocks().exact(block.getState());
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
        PaletteBlock air = latest.blocks().air();
        assertEquals(-604749536, air.getRuntimeId());
        assertSame(air, latest.blocks().getDefinition(-604749536));
        assertSame(air, latest.block(PALETTER.canonicalBlock(BlockStates.of("minecraft:air"))));
        assertEquals(PALETTER.canonicalBlock(BlockStates.of("minecraft:air")), latest.blocks().canonicalOfRuntimeId(-604749536));

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

        int canonical = hashed.canonicalBlock(lit);
        assertTrue(canonical >= PALETTER.canonicalBlockCount(), "custom states are appended");
        assertEquals(PALETTER.canonicalBlockCount() + 2, hashed.canonicalBlockCount());
        assertEquals(canonical, sequential.canonicalBlock(lit), "same layer, same canonical ids in every version and mode");
        // Same instance for every version: compiled once per layer
        assertSame(hashed.canonicalBlocks(), sequential.canonicalBlocks());

        assertEquals(BlockNetworkIds.hash(lit), hashed.runtimeId(canonical));
        assertFalse(hashed.blocks().isRemapped());
        assertTrue(sequential.blocks().isRemapped());
        assertEquals(lit, sequential.block(canonical).getState());
        // Vanilla states keep their canonical ids, while their sequential runtime ids shifted
        int air = PALETTER.canonicalBlock(BlockStates.of("minecraft:air"));
        assertSame(sequential.blocks().air(), sequential.block(air));
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
                for (int i = 0; i < palette.canonicalItemCount(); i++) {
                    ResolvedItem item = palette.item(i);
                    if (item == null) {
                        missing++;
                        continue;
                    }
                    int matched = palette.match(item.toItemData().build());
                    if (matched != i) {
                        mismatches++;
                        // Only allowed if both resolve to exactly the same stack (e.g. fallbacks or adapted states)
                        ResolvedItem other = palette.item(matched);
                        assertSame(item.definition(), other.definition(), palette.canonicalItems().variant(i).toString());
                        assertEquals(item.damage(), other.damage());
                        assertSame(item.block(), other.block(), palette.canonicalItems().variant(i) + " vs " + palette.canonicalItems().variant(matched));
                    }
                }
                if (version.equals(PALETTER.latest())) {
                    assertEquals(0, mismatches + missing, "every variant of the newest version is its own stack");
                }
                System.out.println(version + " " + mode + ": " + palette.canonicalItemCount() + " canonical items, "
                    + missing + " missing, " + mismatches + " share a stack with another");
            }
        }
    }

    @Test
    void blockItemsMatchByDefinitionAndNormalizeTheirDefaultState() {
        Palette palette = PALETTER.vanilla(PALETTER.latest(), BlockNetworkIds.HASHED);
        int planks = PALETTER.canonicalItem(ItemVariant.of("minecraft:oak_planks"));
        ResolvedItem resolved = palette.item(planks);
        assertEquals("minecraft:oak_planks", resolved.block().identifier());
        assertEquals(planks, palette.match(resolved.toItemData().build()));
        // Vanilla has no item placing several blocks in the creative inventory, so the block only matters for
        // variants that name one; spelling out the default state is the same variant
        assertEquals(planks, PALETTER.canonicalItem(new ItemVariant("minecraft:oak_planks", 0, resolved.block().getState())));
        assertEquals(planks, palette.match(resolved.toItemData().blockDefinition(palette.blocks().air()).build()));
    }

    @Test
    void layersAddCanonicalVariants() {
        Palette latest = PALETTER.vanilla(PALETTER.latest());
        PaletteBlock log = latest.blocks().states("minecraft:oak_log").stream()
            .filter(state -> state != latest.items().defaultBlock(latest.items().getDefinition("minecraft:oak_log")))
            .findFirst().orElseThrow();
        ItemVariant apple3 = ItemVariant.of("minecraft:apple", 3);
        ItemVariant sidewaysLog = new ItemVariant("minecraft:oak_log", 0, log.getState());
        assertEquals(-1, PALETTER.canonicalItem(apple3));
        assertEquals(-1, PALETTER.canonicalItem(sidewaysLog));
        PaletteLayer layer = PaletteLayer.builder("variants")
            .variant(apple3)
            .variant(ItemVariant.of("minecraft:apple"))     // already canonical: keeps its id, nothing appended
            .variant(sidewaysLog)
            .variant(apple3)                                // duplicates are ignored
            .build();

        int vanilla = PALETTER.canonicalItemCount();
        for (BlockNetworkIds mode : BlockNetworkIds.values()) {
            for (DataVersion version : PALETTER.versions()) {
                Palette palette = PALETTER.palette(version.protocolVersion(), mode, layer);
                assertEquals(vanilla + 2, palette.canonicalItemCount());
                assertEquals(vanilla, palette.canonicalItem(apple3), "stable across versions and modes");
                assertEquals(vanilla + 1, palette.canonicalItem(sidewaysLog));
                assertEquals(PALETTER.canonicalItem(ItemVariant.of("minecraft:apple")), palette.canonicalItem(ItemVariant.of("minecraft:apple")));

                PaletteItem apple = palette.items().getDefinition("minecraft:apple");
                assertEquals(3, palette.item(vanilla).damage());
                assertEquals(vanilla, palette.match(ItemData.builder().definition(apple).damage(3).count(1).build()));
                assertEquals(PALETTER.canonicalItem(ItemVariant.of("minecraft:apple")),
                    palette.match(ItemData.builder().definition(apple).damage(4).count(1).build()));

                // Block state variants are told apart by the block the client sends
                ResolvedItem logItem = palette.item(vanilla + 1);
                assertEquals(log.getState(), logItem.block().getState(), version.toString());
                assertEquals(vanilla + 1, palette.match(logItem.toItemData().build()));
                int baseLog = PALETTER.canonicalItem(ItemVariant.of("minecraft:oak_log"));
                assertEquals(baseLog, palette.match(palette.item(baseLog).toItemData().build()));
            }
        }
    }

    @Test
    void missingCanonicalContentIsReported() {
        Palette oldest = PALETTER.vanilla(PALETTER.version(OLDEST));
        assertEquals(30, oldest.blocks().missingCanonical().size());
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
        int air = PALETTER.canonicalBlock(BlockStates.of("minecraft:air"));
        assertSame(latest.block(air), oldest.block(air));
        assertSame(latest.block(air), withBlocks.block(air));
        int shared = 0;
        for (int i = 0; i < latest.canonicalBlockCount(); i++) {
            if (latest.block(i) == oldest.block(i)) {
                shared++;
            }
            assertSame(latest.block(i), withBlocks.block(i), "custom blocks do not change vanilla hashed definitions");
        }
        // Every state 26.40 has unchanged (about two thirds) uses the same instance
        assertTrue(shared > latest.canonicalBlockCount() / 2, shared + " shared");
        // Registries still only know their own version's states
        PaletteBlock newOnly = latest.block(PALETTER.canonicalBlock(latest.blocks().states("minecraft:white_concrete_stairs").getFirst().getState()));
        assertTrue(latest.blocks().isRegistered(newOnly));
        assertFalse(oldest.blocks().isRegistered(newOnly));
        assertEquals(-1, oldest.blocks().indexOf(newOnly));
    }
}
