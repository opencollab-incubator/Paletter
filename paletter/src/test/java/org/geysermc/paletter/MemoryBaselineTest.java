package org.geysermc.paletter;

import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.data.PaletteDataSource;
import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.GraphLayout;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Memory baseline with JOL (docs/benchmarks.md): what a compiled palette retains, and what one session's
 * {@link Palette#extend} adds on top of it, the cost a server pays per player with content of its own. The numbers are
 * printed, run {@code ./gradlew :paletter:test --tests '*MemoryBaselineTest' -i} to see them.
 * <p>
 * The cost of an extension is what its object graph holds that the base palette's graph does not, so everything
 * shared with the base (vanilla data, constants) is not counted.
 */
class MemoryBaselineTest {
    /**
     * Retained bytes an extension may add, about 50% above the measured value. Measured (see {@link #print}): 230 to
     * 573 KB per extension of this layer, the spread from what is built lazily; the limit is 50% above the top.
     */
    private static final long MAX_EXTEND_BYTES = 860_000;

    private static PaletteLayer sessionLayer(String id) {
        return PaletteLayer.builder(id)
            .block(CustomBlock.builder("mem:block").property(BlockProperty.bool("mem:lit")).property(BlockProperty.range("mem:level", 0, 7)).build())
            .block(CustomBlock.builder("mem:other").property(BlockProperty.bool("mem:lit")).build())
            .build();
    }

    /**
     * What one extension adds: its graph (which holds the base) minus the graph of the base alone. The base is walked
     * after a throwaway extension warmed its lazily built parts and a GC dropped it, so the difference is the layer's own.
     */
    private static long extendBytes() {
        Paletter paletter = Paletter.builder().build();
        Palette base = paletter.palette(paletter.latest().protocolVersion());
        base.extend(sessionLayer("warmup"));
        System.gc();
        long before = GraphLayout.parseInstance(base).totalSize();
        Palette extended = base.extend(sessionLayer("session"));
        return GraphLayout.parseInstance(extended).totalSize() - before;
    }

    @Test
    void print() {
        Paletter paletter = Paletter.builder().build();
        Palette palette = paletter.palette(paletter.latest().protocolVersion());
        PaletteDataSource index = PaletteDataSource.bundled();
        index.versions();
        System.out.println("MEMORY index (bundled source, versions listed) bytes=" + GraphLayout.parseInstance(index).totalSize());
        System.out.println("MEMORY vanilla palette (latest, with everything it reaches) bytes=" + GraphLayout.parseInstance(palette).totalSize());
        for (int i = 0; i < 3; i++) {
            System.out.println("MEMORY extend (small layer: 2 blocks, 3 properties) bytes=" + extendBytes());
        }
    }

    @Test
    void extendStaysSmall() {
        long bytes = extendBytes();
        assertTrue(bytes <= MAX_EXTEND_BYTES, "an extension retains " + bytes + " bytes, limit " + MAX_EXTEND_BYTES);
    }
}
