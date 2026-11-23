package dev.opencollab.paletter.bench;

import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.PaletteLayer;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.behaviour.BlockBehaviour;
import dev.opencollab.paletter.behaviour.ItemBehaviour;
import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockBlocks;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
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
    /** The palette of the bundled preview, and the runtime ids of its blocks in a shuffled order. */
    private Palette preview;
    private int[] previewRuntimeIds;

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

        Paletter previews = Paletter.builder().previews(true).versions(DataVersion::isPreview).behaviour(false).build();
        preview = previews.palette(previews.latest().protocolVersion());
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < preview.blocks().size(); i++) {
            ids.add(preview.blocks().byIndex(i).getRuntimeId());
        }
        Collections.shuffle(ids, random);
        previewRuntimeIds = ids.stream().mapToInt(Integer::intValue).toArray();
    }

    @Benchmark
    public float resolveStateBehaviour(Cursor cursor) {
        return palette.resolve(states[cursor.step(states.length)]).behaviour().get(BlockBehaviour.FRICTION);
    }

    @Benchmark
    public int resolveItemBehaviour(Cursor cursor) {
        return palette.resolve(variants[cursor.step(variants.length)]).behaviour().getOrElse(ItemBehaviour.MAX_STACK_SIZE, 0);
    }

    /**
     * A block runtime id to its key on the palette of a preview version (previews on): the keys of the blocks the preview
     * added or changed come from a table of the preview's canonical blocks, which must not be behind a lock.
     */
    @Benchmark
    public BedrockBlockState previewBlockState(Cursor cursor) {
        return preview.blockState(previewRuntimeIds[cursor.step(previewRuntimeIds.length)]);
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
