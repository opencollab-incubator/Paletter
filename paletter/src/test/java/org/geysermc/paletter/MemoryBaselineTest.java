package org.geysermc.paletter;

import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.component.MaxStackSize;
import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.GraphLayout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.fail;


/**
 * Memory baseline with JOL (docs/benchmarks.md): what a compiled palette retains, and what one session's
 * {@link Palette#extend} adds on top of it, the cost a server pays per player with content of its own. The numbers are
 * printed as {@code MEMORY} lines, run {@code ./gradlew :paletter:test --tests '*MemoryBaselineTest' -i} to see them.
 * <p>
 * The cost of an extension is what its object graph holds that the base palette's graph does not. JOL tells objects apart by
 * address, so a garbage collection between two walks makes moved objects look new: a measurement is repeated up to
 * {@link #ATTEMPTS} times and passes if one is within the limit.
 * <p>
 * The limits are above the measured values, which are noted beside them; raise one only for a reason, and say so in the commit.
 */
class MemoryBaselineTest {
    private static final int ATTEMPTS = 3;
    /** A session layer of two blocks and three properties: no creative placement, so the creative content is the parent's. Measured 40 to 53 KB. */
    private static final long MAX_EXTEND_BYTES = 100_000;
    /** A layer with an item and a block in creative: the creative content is compiled again. Measured 290 KB. */
    private static final long MAX_EXTEND_WITH_CREATIVE_BYTES = 450_000;
    /** A vanilla palette with everything it reaches, of the newest and of the oldest version. Measured 17.8 and 18.3 MB. */
    private static final long MAX_VANILLA_LATEST_BYTES = 19_600_000;
    private static final long MAX_VANILLA_OLDEST_BYTES = 19_900_000;

    private static PaletteLayer sessionLayer(String id) {
        return PaletteLayer.builder(id)
            .block(CustomBlock.builder("mem:block").property(BlockProperty.bool("mem:lit")).property(BlockProperty.range("mem:level", 0, 7)).build())
            .block(CustomBlock.builder("mem:other").property(BlockProperty.bool("mem:lit")).build())
            .build();
    }

    private static PaletteLayer creativeLayer(String id) {
        return PaletteLayer.builder(id)
            .block(CustomBlock.builder("mem:shown").menuCategory(CreativeCategory.CONSTRUCTION).build())
            .item(CustomItem.builder("mem:gem").component(MaxStackSize.of(16)).menuCategory(CreativeCategory.ITEMS).build())
            .build();
    }

    /**
     * What one extension adds: its graph (which holds the base) minus the graph of the base alone. The base is walked
     * after a throwaway extension warmed its lazily built parts and a GC dropped it, so the difference is the layer's own.
     */
    private static long extendBytes(Function<String, PaletteLayer> layer) {
        Paletter paletter = Paletter.builder().build();
        Palette base = paletter.palette(paletter.latest().protocolVersion());
        base.extend(layer.apply("warmup"));
        System.gc();
        long before = GraphLayout.parseInstance(base).totalSize();
        Palette extended = base.extend(layer.apply("session"));
        return GraphLayout.parseInstance(extended).totalSize() - before;
    }

    /**
     * What a vanilla palette reaches, measured in a JVM of its own: what other tests of this JVM have filled in the handles of
     * the constants and in the data source (the behaviour captures alone are about 15 MB) would count as the palette's.
     */
    private static long vanillaBytes(boolean latest) {
        try {
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djol.magicFieldOffset=true", "-cp", System.getProperty("java.class.path"), VanillaMeasure.class.getName(), String.valueOf(latest))
                .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.waitFor() != 0) {
                throw new IllegalStateException("the measuring JVM failed: " + output);
            }
            return Long.parseLong(output.lines().reduce((first, second) -> second).orElseThrow());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The measuring JVM: prints the bytes a vanilla palette (newest or oldest version) retains. */
    public static final class VanillaMeasure {
        public static void main(String[] args) {
            Paletter paletter = Paletter.builder().build();
            DataVersion version = Boolean.parseBoolean(args[0]) ? paletter.latest() : paletter.versions().getFirst();
            System.out.println(GraphLayout.parseInstance(paletter.vanilla(version)).totalSize());
        }
    }

    private static void assertWithin(String what, long limit, LongSupplier measure) {
        List<Long> measured = new ArrayList<>();
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            long bytes = measure.getAsLong();
            System.out.println("MEMORY " + what + " bytes=" + bytes + " (limit " + limit + ")");
            if (bytes <= limit) {
                return;
            }
            measured.add(bytes);
        }
        fail(what + " retains " + measured + " bytes in " + ATTEMPTS + " attempts, limit " + limit);
    }

    @Test
    void extendStaysSmall() {
        assertWithin("an extension", MAX_EXTEND_BYTES, () -> extendBytes(MemoryBaselineTest::sessionLayer));
    }

    @Test
    void extendWithCreativeContentStaysSmall() {
        assertWithin("an extension with creative content", MAX_EXTEND_WITH_CREATIVE_BYTES, () -> extendBytes(MemoryBaselineTest::creativeLayer));
    }

    @Test
    void vanillaPalettesStaySmall() {
        assertWithin("the newest vanilla palette", MAX_VANILLA_LATEST_BYTES, () -> vanillaBytes(true));
        assertWithin("the oldest vanilla palette", MAX_VANILLA_OLDEST_BYTES, () -> vanillaBytes(false));
    }
}
