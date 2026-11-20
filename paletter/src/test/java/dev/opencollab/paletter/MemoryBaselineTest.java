package dev.opencollab.paletter;

import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.component.MaxStackSize;
import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.GraphLayout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.fail;


/**
 * Memory baseline with JOL (docs/benchmarks.md): what a compiled palette retains, and what one session's
 * {@link Palette#extend} adds on top of it, the cost a server pays per player with content of its own. The numbers are
 * printed as {@code MEMORY} lines, run {@code ./gradlew :paletter:test --tests '*MemoryBaselineTest' -i} to see them.
 * <p>
 * The cost of an extension is what its object graph holds that the base palette's graph does not. JOL tells objects apart by
 * address, so a garbage collection between two walks makes moved objects look new (or hides new ones): every measurement
 * runs once, in a JVM of its own with pinned flags (a serial collector and a young generation that holds the whole run), so
 * what other tests of the test JVM did does not count and no collection comes between the walks.
 * <p>
 * The limits are above the measured values, which are noted beside them; raise one only for a reason, and say so in the commit.
 */
class MemoryBaselineTest {
    /** A session layer of two blocks and three properties: no creative placement, so the creative content is the parent's. Measured 50,984 bytes, the same in every run. */
    private static final long MAX_EXTEND_BYTES = 56_000;
    /** A layer with an item and a block in creative: the creative content is compiled again. Measured 299,208 bytes, the same in every run. */
    private static final long MAX_EXTEND_WITH_CREATIVE_BYTES = 330_000;
    /**
     * A vanilla palette with everything it reaches, of the newest and of the oldest version. Measured 12.9 and 13.6 MB
     * (12,918,680 and 13,566,112 bytes) since the state indexes are keyed by state hashes; 14.5 and 15.0 MB before.
     */
    private static final long MAX_VANILLA_LATEST_BYTES = 14_200_000;
    private static final long MAX_VANILLA_OLDEST_BYTES = 14_900_000;
    /**
     * A Paletter serving every bundled version but the oldest, with all its palettes compiled, and the shared bundled
     * instance it loads through. Measured 16.7 MB (16,697,128 bytes) since the state indexes are keyed by state hashes;
     * 18.5 MB before, 20.9 MB before what only compiling uses was dropped once all are compiled.
     */
    private static final long MAX_SERVED_BYTES = 18_400_000;

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
     * Runs in the measuring JVM ({@link VanillaMeasure}).
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
    private static long vanillaBytes(String mode) {
        // The object layout decides the size: pinned, so the number does not depend on the JDK or the machine.
        // Serial collector, and a young generation of 600 MB that holds the allocations of a whole run: no collection
        // moves objects between two JOL walks (the measurement collects once itself, before the walk it compares with).
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xms1g", "-Xmx1g", "-Xmn600m", "-XX:+UseSerialGC", "-XX:+UseCompressedOops", "-XX:ObjectAlignmentInBytes=8"));
        if (Runtime.version().feature() >= 24) {
            // Compact object headers (experimental in 24, a product flag in 25) make objects smaller: off, whatever the default
            command.addAll(List.of("-XX:+UnlockExperimentalVMOptions", "-XX:-UseCompactObjectHeaders"));
        }
        command.addAll(List.of("-Djol.magicFieldOffset=true", "-cp", System.getProperty("java.class.path"),
            VanillaMeasure.class.getName(), mode));
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            // Read on, so a full pipe cannot stop the JVM while waiting for it
            Process started = process;
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the measuring JVM did not finish in 120 seconds");
            }
            String text = output.get(10, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("the measuring JVM failed: " + text);
            }
            return Long.parseLong(text.lines().reduce((first, second) -> second).orElseThrow());
        } catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
            throw new IllegalStateException(e);
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * The measuring JVM: prints the bytes a vanilla palette (newest or oldest version) retains, or with {@code served}, what a
     * Paletter serving every version but the oldest retains once each of its palettes is compiled (as an application serving
     * a few versions has it), the shared bundled instance it loads through included.
     */
    public static final class VanillaMeasure {
        public static void main(String[] args) {
            if (args[0].equals("extend")) {
                System.out.println(extendBytes(MemoryBaselineTest::sessionLayer));
                return;
            }
            if (args[0].equals("extendCreative")) {
                System.out.println(extendBytes(MemoryBaselineTest::creativeLayer));
                return;
            }
            if (args[0].equals("served")) {
                DataVersion oldest = Paletter.bundled().versions().getFirst();
                Paletter paletter = Paletter.builder().versions(version -> !version.equals(oldest)).build();
                for (DataVersion version : paletter.versions()) {
                    paletter.palette(version.protocolVersion());
                }
                System.out.println(GraphLayout.parseInstance(paletter, Paletter.bundled()).totalSize());
                return;
            }
            Paletter paletter = Paletter.builder().build();
            DataVersion version = Boolean.parseBoolean(args[0]) ? paletter.latest() : paletter.versions().getFirst();
            System.out.println(GraphLayout.parseInstance(paletter.vanilla(version)).totalSize());
        }
    }

    @Test
    void extendStaysSmall() {
        assertOnce("an extension", MAX_EXTEND_BYTES, vanillaBytes("extend"));
    }

    @Test
    void extendWithCreativeContentStaysSmall() {
        assertOnce("an extension with creative content", MAX_EXTEND_WITH_CREATIVE_BYTES, vanillaBytes("extendCreative"));
    }

    @Test
    void vanillaPalettesStaySmall() {
        assertOnce("the newest vanilla palette", MAX_VANILLA_LATEST_BYTES, vanillaBytes("true"));
        assertOnce("the oldest vanilla palette", MAX_VANILLA_OLDEST_BYTES, vanillaBytes("false"));
    }

    @Test
    void servedVersionsStaySmall() {
        assertOnce("the served versions", MAX_SERVED_BYTES, vanillaBytes("served"));
    }

    private static void assertOnce(String what, long limit, long bytes) {
        System.out.println("MEMORY " + what + " bytes=" + bytes + " (limit " + limit + ")");
        if (bytes > limit) {
            fail(what + " retains " + bytes + " bytes, limit " + limit);
        }
    }
}
