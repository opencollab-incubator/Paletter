package dev.opencollab.paletter.bench;

import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.PaletteLayer;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.component.MaxStackSize;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * What a server does once at startup, the way Geyser does it: build a {@link Paletter} and compile the palette of every
 * bundled release version. One shot in a fresh JVM, no warm-up: a warm-up would not be startup any more. Every fork is one
 * cold measurement; the value to report is the median of the forks.
 * <p>
 * Run it on its own, with no warm-up and one iteration (a run without {@code -Pjmh.includes} leaves it out, and the
 * command line settings of the other runs would warm it up): {@code ./gradlew :paletter:jmh -Pjmh.includes=Startup
 * -Pjmh.warmups=0 -Pjmh.iterations=1 -Pjmh.forks=5}. The log has one {@code Iteration 1} line per fork.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(5)
public class StartupBenchmark {
    /** Vanilla only: the palettes of all bundled release versions. */
    @Benchmark
    public int vanilla() {
        return compile(Paletter.builder().build());
    }

    /** With one custom layer of 20 blocks and 20 items, registered before the palettes compile. */
    @Benchmark
    public int withCustomLayer() {
        PaletteLayer.Builder layer = PaletteLayer.builder("startup");
        for (int i = 0; i < 20; i++) {
            layer.block(CustomBlock.builder("bench:block_" + i).property(BlockProperty.bool("bench:lit")).build());
            layer.item(CustomItem.builder("bench:item_" + i).component(MaxStackSize.of(16)).build());
        }
        return compile(Paletter.builder().layer(layer.build()).build());
    }

    private static int compile(Paletter paletter) {
        int variants = 0;
        for (DataVersion version : paletter.versions()) {
            Palette palette = paletter.palette(version.protocolVersion());
            variants += palette.itemVariants().size();
        }
        return variants;
    }
}
