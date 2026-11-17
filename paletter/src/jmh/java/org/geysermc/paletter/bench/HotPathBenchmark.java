package org.geysermc.paletter.bench;

import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.PaletteLayer;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.behaviour.BlockBehaviour;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.item.ResolvedItem;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockBlockType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * The per packet lookups of a translator, on a palette the way a server uses one: the newest version with a layer of
 * custom blocks, extended by two session layers that add more. Inputs are visited in a shuffled order, so the numbers
 * include the cache misses of real traffic rather than those of a loop over one hot entry.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class HotPathBenchmark {
    private static final int SEED = 42;

    private Palette palette;
    private BedrockItemVariant[] variants;
    private BedrockItemStack[] stacks;
    private ItemData[] encoded;
    /** Equal to {@link #encoded}, element by element, but other instances. */
    private ItemData[] copies;
    private int[] runtimeIds;
    private RegisteredBlockState[] customStates;
    private BedrockBlockState[] customHandles;
    private int item;
    private int block;
    private int custom;

    @Setup
    public void setUp() {
        Paletter paletter = Paletter.builder().layer(layer("server", 0, 8)).build();
        palette = paletter.palette(paletter.latest().protocolVersion())
            .extend(layer("session", 8, 8))
            .extend(layer("session-2", 16, 8));
        Random random = new Random(SEED);

        List<BedrockItemVariant> variantList = new ArrayList<>(palette.itemVariants());
        Collections.shuffle(variantList, random);
        variants = variantList.toArray(BedrockItemVariant[]::new);
        stacks = new BedrockItemStack[variants.length];
        encoded = new ItemData[variants.length];
        copies = new ItemData[variants.length];
        for (int i = 0; i < variants.length; i++) {
            stacks[i] = BedrockItemStack.of(variants[i]);
            encoded[i] = palette.encode(stacks[i]);
            copies[i] = palette.encode(stacks[i]);
        }

        List<Integer> ids = new ArrayList<>();
        List<RegisteredBlockState> customs = new ArrayList<>();
        for (int i = 0; i < palette.blocks().size(); i++) {
            RegisteredBlockState state = palette.blocks().byIndex(i);
            ids.add(state.getRuntimeId());
            if (state.isCustom()) {
                customs.add(state);
            }
        }
        Collections.shuffle(ids, random);
        runtimeIds = ids.stream().mapToInt(Integer::intValue).toArray();
        Collections.shuffle(customs, random);
        customStates = customs.toArray(RegisteredBlockState[]::new);
        customHandles = new BedrockBlockState[customStates.length];
        for (int i = 0; i < customStates.length; i++) {
            customHandles[i] = palette.blocks().key(customStates[i]);
        }
    }

    /** {@code count} custom blocks with 16 states each, numbered from {@code first}. */
    private static PaletteLayer layer(String id, int first, int count) {
        PaletteLayer.Builder builder = PaletteLayer.builder(id);
        for (int i = first; i < first + count; i++) {
            builder.block(CustomBlock.builder("bench:block_" + i)
                .property(BlockProperty.bool("bench:lit"))
                .property(BlockProperty.range("bench:level", 0, 7))
                .build());
        }
        return builder.build();
    }

    private int nextItem() {
        int i = item;
        item = i + 1 == variants.length ? 0 : i + 1;
        return i;
    }

    private int nextBlock() {
        int i = block;
        block = i + 1 == runtimeIds.length ? 0 : i + 1;
        return i;
    }

    private int nextCustom() {
        int i = custom;
        custom = i + 1 == customStates.length ? 0 : i + 1;
        return i;
    }

    @Benchmark
    public ItemData encode() {
        return palette.encode(stacks[nextItem()]);
    }

    @Benchmark
    public BedrockItemStack decode() {
        return palette.decode(encoded[nextItem()]);
    }

    @Benchmark
    public ResolvedItem resolve() {
        return palette.resolve(variants[nextItem()]);
    }

    @Benchmark
    public boolean sameStack() {
        int i = nextItem();
        return palette.sameStack(encoded[i], copies[i]);
    }

    @Benchmark
    public RegisteredBlockState byRuntimeId() {
        return palette.blocks().byRuntimeId(runtimeIds[nextBlock()]);
    }

    @Benchmark
    public float blockBehaviour() {
        return palette.blockBehaviour(runtimeIds[nextBlock()]).get(BlockBehaviour.FRICTION);
    }

    /** A custom block of the client to its handle: what decoding a stack that places a custom block does. */
    @Benchmark
    public BedrockBlockState customKey() {
        return palette.blocks().key(customStates[nextCustom()]);
    }

    /** A custom block handle to its runtime id. */
    @Benchmark
    public int customRuntimeId() {
        return palette.runtimeId(customHandles[nextCustom()]);
    }

    /** A vanilla block handle to its runtime id. */
    @Benchmark
    public BedrockBlockType vanillaKey() {
        RegisteredBlockState state = palette.blocks().byRuntimeId(runtimeIds[nextBlock()]);
        BedrockBlockState key = palette.blocks().key(state);
        return key == null ? null : key.type();
    }
}
