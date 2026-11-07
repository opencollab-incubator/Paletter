package org.geysermc.paletter;

import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.paletter.block.BlockNetworkIds;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataSource;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.ItemComponents;
import org.geysermc.paletter.item.ResolvedItem;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Not a JMH benchmark: rough compile times, lookup costs and retained heap, printed to the test output and checked
 * against generous bounds so regressions by an order of magnitude fail the build.
 */
class BenchmarkTest {

    private static PaletteLayer sharedLayer() {
        PaletteLayer.Builder builder = PaletteLayer.builder("shared")
            .overrideItem("minecraft:cake", item -> item.components(c -> c.maxStackSize(1)));
        for (int i = 0; i < 50; i++) {
            builder.item(CustomItem.builder("bench:item_" + i)
                .components(ItemComponents.builder().icon("apple").maxStackSize(16).build())
                .creative(CreativePlacement.loose(CreativeCategory.ITEMS))
                .build());
        }
        for (int i = 0; i < 10; i++) {
            builder.block(CustomBlock.builder("bench:block_" + i)
                .property(BlockProperty.ints("bench:level", 0, 1, 2, 3))
                .creative(CreativePlacement.loose(CreativeCategory.CONSTRUCTION))
                .build());
        }
        return builder.build();
    }

    private static PaletteLayer sessionLayer() {
        return PaletteLayer.builder("session")
            .item(CustomItem.builder("bench:session_item").creative(CreativePlacement.loose(CreativeCategory.ITEMS)).build())
            .build();
    }

    @Test
    void compileTimesLookupsAndHeap() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long before = usedHeap(memory);

        Paletter paletter = Paletter.create(PaletteDataSource.bundled());
        List<Palette> keep = new ArrayList<>();
        long start = System.nanoTime();
        for (DataVersion version : paletter.versions()) {
            paletter.vanillaData(version);
        }
        long load = System.nanoTime() - start;
        long afterLoad = usedHeap(memory);
        StringBuilder out = new StringBuilder(String.format("Load %d versions: %s, %.1f MB%n",
            paletter.versions().size(), ms(load), (afterLoad - before) / 1e6));
        long previous = afterLoad;

        PaletteLayer shared = sharedLayer();
        for (BlockNetworkIds mode : List.of(BlockNetworkIds.HASHED, BlockNetworkIds.SEQUENTIAL)) {
            for (DataVersion version : paletter.versions()) {
                long t0 = System.nanoTime();
                Palette vanilla = paletter.vanilla(version, mode);
                long t1 = System.nanoTime();
                Palette palette = paletter.palette(version.protocolVersion(), mode, shared);
                long t2 = System.nanoTime();
                palette.runtimeId(0);
                palette.item(0);
                long t3 = System.nanoTime();
                keep.add(vanilla);
                keep.add(palette);
                out.append(String.format("%s %-10s vanilla %s, +layer %s, canonical tables %s%n", version.name(), mode, ms(t1 - t0), ms(t2 - t1), ms(t3 - t2)));
            }
            long now = usedHeap(memory);
            out.append(String.format("Heap of 3 x (vanilla + layer) %s palettes incl. canonical tables: %.1f MB%n", mode, (now - previous) / 1e6));
            previous = now;
        }
        long afterPalettes = usedHeap(memory);

        Palette base = paletter.palette(paletter.latest().protocolVersion(), BlockNetworkIds.HASHED, shared);
        long t0 = System.nanoTime();
        Palette session = base.extend(sessionLayer());
        session.runtimeId(0);
        session.item(0);
        long sessionTime = System.nanoTime() - t0;
        keep.add(session);
        long afterSession = usedHeap(memory);
        out.append("Session extend (1 custom item, incl. canonical tables): ").append(ms(sessionTime)).append('\n');
        out.append(String.format("Heap of the session extend: %.2f MB; total %.1f MB%n",
            (afterSession - afterPalettes) / 1e6, (afterSession - before) / 1e6));

        // Hot paths
        int blocks = base.canonicalBlockCount();
        long sum = 0;
        for (int warmup = 0; warmup < 20; warmup++) {
            for (int i = 0; i < blocks; i++) {
                sum += base.runtimeId(i);
            }
        }
        int rounds = 200;
        long b0 = System.nanoTime();
        for (int r = 0; r < rounds; r++) {
            for (int i = 0; i < blocks; i++) {
                sum += base.runtimeId(i);
            }
        }
        double blockNs = (System.nanoTime() - b0) / (double) (rounds * blocks);

        int items = base.canonicalItemCount();
        ItemData[] stacks = new ItemData[items];
        for (int i = 0; i < items; i++) {
            ResolvedItem item = base.item(i);
            stacks[i] = item == null ? ItemData.AIR : item.toItemData().build();
        }
        for (int warmup = 0; warmup < 20; warmup++) {
            for (ItemData stack : stacks) {
                sum += base.match(stack);
            }
        }
        long m0 = System.nanoTime();
        for (int r = 0; r < rounds; r++) {
            for (ItemData stack : stacks) {
                sum += base.match(stack);
            }
        }
        double matchNs = (System.nanoTime() - m0) / (double) (rounds * items);
        int[] pages = paletter.blockInterner().pageStats();
        out.append(String.format("Hashed canonical table pages: %d built, %d shared%n", pages[0], pages[1]));
        out.append(String.format("runtimeId(int): %.1f ns, match(ItemData): %.1f ns (checksum %d)%n", blockNs, matchNs, sum));
        System.out.println(out);

        assertTrue(sessionTime < 2_000_000_000L, out::toString);
        assertTrue(blockNs < 1_000 && matchNs < 5_000, out::toString);
        assertTrue(keep.size() > 0);
    }

    private static long usedHeap(MemoryMXBean memory) {
        for (int i = 0; i < 4; i++) {
            System.gc();
        }
        return memory.getHeapMemoryUsage().getUsed();
    }

    private static String ms(long nanos) {
        return String.format("%.1f ms", nanos / 1e6);
    }
}
