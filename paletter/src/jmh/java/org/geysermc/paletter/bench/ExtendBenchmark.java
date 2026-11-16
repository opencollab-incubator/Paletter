package org.geysermc.paletter.bench;

import org.geysermc.paletter.Palette;
import org.geysermc.paletter.PaletteLayer;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * Extending a palette by a session layer: what a server does once per player that gets content of its own. Every call
 * extends by a new layer instance, so nothing comes from the cache of extensions.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ExtendBenchmark {
    private Palette palette;
    private CustomBlock block;

    @Setup
    public void setUp() {
        Paletter paletter = Paletter.bundled();
        palette = paletter.palette(paletter.latest().protocolVersion());
        block = CustomBlock.builder("bench:session_block").property(BlockProperty.bool("bench:lit")).build();
    }

    @Benchmark
    public Palette extend() {
        return palette.extend(PaletteLayer.builder("session").block(block).build());
    }
}
