package dev.opencollab.paletter;

import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.data.PaletteDataSource;
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
    /** A session layer of two blocks and three properties: no creative placement, so the creative content is the parent's. Measured 53,056 bytes (50,984 before build() compiled the palettes with everything loaded), the same in every run. */
    private static final long MAX_EXTEND_BYTES = 56_000;
    /** A layer with an item and a block in creative: the creative content is compiled again. Measured 300,656 bytes (299,208 before), the same in every run. */
    private static final long MAX_EXTEND_WITH_CREATIVE_BYTES = 330_000;
    /**
     * A vanilla palette with everything it reaches, of the newest and of the oldest version, behaviour off. Measured 11.9
     * and 13.3 MB (11,914,448 and 13,310,304 bytes) since build() compiles (and then drops what only compiling uses);
     * 12.9 and 13.6 MB before that, 14.5 and 15.0 MB before the state indexes were keyed by state hashes.
     */
    private static final long MAX_VANILLA_LATEST_BYTES = 13_100_000;
    private static final long MAX_VANILLA_OLDEST_BYTES = 14_600_000;
    /**
     * A Paletter serving every bundled version but the oldest, with all its palettes compiled, and the shared bundled
     * loader it loads through, behaviour off. Measured 18.4 MB (18,437,040 bytes) since build() builds what lookups used to
     * build on first use (resolution tables, merged item definitions and their flat table by runtime id, ...); 16.7 MB
     * before that, 18.5 MB before the state indexes were keyed by state hashes, 20.9 MB before what only compiling uses
     * was dropped once all are compiled.
     */
    private static final long MAX_SERVED_BYTES = 20_000_000;
    /**
     * The bundled behaviour values of every version (the generated data, built: {@code VanillaBehaviour.bundled()}), shared
     * by every palette. Measured 2.0 MB (2,033,960 bytes); 6.6 MB (6,624,728) when they were {@code behaviour.nbt} as read.
     */
    private static final long MAX_BEHAVIOUR_STORE_BYTES = 2_200_000;
    /**
     * The behaviour columns of the newest palette, all keys built (as {@code build()} does). Measured 2.2 MB (2,176,872
     * bytes); 2.4 MB (2,356,032) before.
     */
    private static final long MAX_BEHAVIOUR_COLUMNS_BYTES = 2_400_000;
    /**
     * What loading the generated behaviour classes and building the values adds to the metaspace, which stays for the life
     * of the class loader and is not on the heap (JOL does not count it). Measured 1.7 MB (1,715,872 bytes).
     */
    private static final long MAX_BEHAVIOUR_METASPACE_BYTES = 2_000_000;
    /**
     * What loading the generated block definitions and voxel shapes and building them once adds to the metaspace; nothing of
     * them stays on the heap (they are built for each version as it loads). Measured 0.38 MB (383,832 bytes).
     */
    private static final long MAX_BLOCKS_METASPACE_BYTES = 420_000;

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
        Paletter paletter = Paletter.builder().versions(MemoryBaselineTest::newestRelease).build();
        Palette base = paletter.palette(paletter.latest().protocolVersion());
        base.extend(layer.apply("warmup"));
        System.gc();
        long before = GraphLayout.parseInstance(base).totalSize();
        Palette extended = base.extend(layer.apply("session"));
        return GraphLayout.parseInstance(extended).totalSize() - before;
    }

    /**
     * What a vanilla palette reaches, measured in a JVM of its own: what other tests of this JVM have filled in the handles of
     * the constants and in the data source (the behaviour store alone is 6.6 MB, measured on its own below) would count as the palette's.
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
            text.lines().filter(line -> line.startsWith("FOOTPRINT ")).forEach(line -> System.out.println("MEMORY " + line));
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
            if (args[0].equals("behaviour")) {
                // The bundled behaviour values alone (the generated data, built), with where their bytes go
                Object store = dev.opencollab.paletter.internal.behaviour.VanillaBehaviour.bundled();
                GraphLayout layout = GraphLayout.parseInstance(store);
                layout.toFootprint().lines().forEach(line -> System.out.println("FOOTPRINT " + line));
                System.out.println(layout.totalSize());
                return;
            }
            if (args[0].equals("metaspace")) {
                // What loading and running the generated classes adds to the metaspace (not on the heap, so not in JOL's count)
                long before = metaspace();
                dev.opencollab.paletter.internal.behaviour.VanillaBehaviour.bundled();
                System.out.println(metaspace() - before);
                return;
            }
            if (args[0].equals("blocksMetaspace")) {
                // What loading and running the generated block definitions and voxel shapes adds to the metaspace
                long before = metaspace();
                dev.opencollab.paletter.internal.vanilla.VanillaBlocks.all();
                dev.opencollab.paletter.internal.shape.VoxelShapeStore.bundled();
                System.out.println(metaspace() - before);
                return;
            }
            if (args[0].equals("withBehaviour")) {
                // The newest palette with its behaviour columns, and the bundled values (counted once, as in "behaviour")
                Paletter paletter = Paletter.builder().versions(MemoryBaselineTest::newestRelease).build();
                System.out.println(GraphLayout.parseInstance(paletter.palette(paletter.latest().protocolVersion()),
                    dev.opencollab.paletter.internal.behaviour.VanillaBehaviour.bundled()).totalSize());
                return;
            }
            if (args[0].equals("extend")) {
                System.out.println(extendBytes(MemoryBaselineTest::sessionLayer));
                return;
            }
            if (args[0].equals("extendCreative")) {
                System.out.println(extendBytes(MemoryBaselineTest::creativeLayer));
                return;
            }
            if (args[0].equals("served")) {
                DataVersion oldest = PaletteDataSource.bundled().versions().getFirst();
                Paletter paletter = Paletter.builder().behaviour(false).versions(version -> !version.equals(oldest)).build();
                System.out.println(GraphLayout.parseInstance(paletter, Paletter.bundledLoader()).totalSize());
                return;
            }
            // Behaviour off: the palette alone, see "behaviour" and "withBehaviour" for the rest
            boolean newest = Boolean.parseBoolean(args[0]);
            DataVersion oldest = PaletteDataSource.bundled().versions().getFirst();
            Paletter paletter = Paletter.builder().behaviour(false)
                .versions(version -> newest ? newestRelease(version) : version.equals(oldest)).build();
            System.out.println(GraphLayout.parseInstance(paletter.vanilla(paletter.versions().getFirst())).totalSize());
        }
    }

    /** @return the bytes the Metaspace pool uses now */
    private static long metaspace() {
        return java.lang.management.ManagementFactory.getMemoryPoolMXBeans().stream().filter(pool -> pool.getName().equals("Metaspace"))
            .mapToLong(pool -> pool.getUsage().getUsed()).sum();
    }

    private static boolean newestRelease(DataVersion version) {
        return version.equals(PaletteDataSource.bundled().versions().stream().filter(v -> !v.isPreview()).reduce((a, b) -> b).orElseThrow());
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

    @Test
    void behaviourStaysWithinItsLimit() {
        long store = vanillaBytes("behaviour");
        assertOnce("the behaviour store", MAX_BEHAVIOUR_STORE_BYTES, store);
        // The newest palette with behaviour on reaches the store too: the columns are the rest of the difference
        long columns = vanillaBytes("withBehaviour") - vanillaBytes("true") - store;
        assertOnce("the behaviour columns of the newest palette", MAX_BEHAVIOUR_COLUMNS_BYTES, columns);
        assertOnce("the metaspace of the generated behaviour classes", MAX_BEHAVIOUR_METASPACE_BYTES, vanillaBytes("metaspace"));
    }

    @Test
    void generatedBlocksStayWithinTheirLimit() {
        assertOnce("the metaspace of the generated block definitions and voxel shapes", MAX_BLOCKS_METASPACE_BYTES, vanillaBytes("blocksMetaspace"));
    }

    private static void assertOnce(String what, long limit, long bytes) {
        System.out.println("MEMORY " + what + " bytes=" + bytes + " (limit " + limit + ")");
        if (bytes > limit) {
            fail(what + " retains " + bytes + " bytes, limit " + limit);
        }
    }
}
