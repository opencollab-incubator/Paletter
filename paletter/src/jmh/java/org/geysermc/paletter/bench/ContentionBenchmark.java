package org.geysermc.paletter.bench;

import org.geysermc.paletter.Palette;
import org.geysermc.paletter.PaletteLayer;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.behaviour.BlockBehaviour;
import org.geysermc.paletter.behaviour.ItemBehaviour;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockBlocks;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * The lookups of {@link HotPathBenchmark} and {@code extend} with eight threads on one palette, the way the network threads of a
 * server share the palette of a client version. Where a number is much more than the single threaded one, something is locked or
 * shared between the threads (a monitor, a cache line that is written).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Threads(8)
public class ContentionBenchmark {
    private Palette palette;
    private BedrockItemVariant[] variants;
    private BedrockBlockState[] states;
    private CustomBlock block;

    /** Where a thread is in the shuffled inputs. */
    @State(Scope.Thread)
    public static class Cursor {
        private int next;

        int step(int length) {
            int i = next;
            next = i + 1 == length ? 0 : i + 1;
            return i;
        }
    }

    @Setup
    public void setUp() {
        Paletter paletter = Paletter.bundled();
        palette = paletter.palette(paletter.latest().protocolVersion());
        Random random = new Random(42);
        List<BedrockItemVariant> variantList = new ArrayList<>(palette.itemVariants());
        Collections.shuffle(variantList, random);
        variants = variantList.toArray(BedrockItemVariant[]::new);
        List<BedrockBlockState> stateList = new ArrayList<>();
        for (int i = 0; i < palette.blocks().size(); i++) {
            BedrockBlockState key = palette.blocks().key(palette.blocks().byIndex(i));
            stateList.add(key != null ? key : BedrockBlocks.AIR.defaultState());
        }
        Collections.shuffle(stateList, random);
        states = stateList.toArray(BedrockBlockState[]::new);
        block = CustomBlock.builder("bench:session_block").property(BlockProperty.bool("bench:lit")).build();
    }

    @Benchmark
    public float resolveStateBehaviour(Cursor cursor) {
        return palette.resolve(states[cursor.step(states.length)]).behaviour().get(BlockBehaviour.FRICTION);
    }

    @Benchmark
    public int resolveItemBehaviour(Cursor cursor) {
        return palette.resolve(variants[cursor.step(variants.length)]).behaviour().getOrElse(ItemBehaviour.MAX_STACK_SIZE, 0);
    }

    @Benchmark
    public int runtimeId(Cursor cursor) {
        return palette.runtimeId(states[cursor.step(states.length)]);
    }

    /** Every thread extends the one palette by its own new layer: what the sessions of a server do when they join. */
    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public Palette extend() {
        return palette.extend(PaletteLayer.builder("session").block(block).build());
    }
}
