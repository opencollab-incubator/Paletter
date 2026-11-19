package dev.opencollab.paletter.bench;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import dev.opencollab.paletter.Lookup;
import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.PaletteLayer;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.behaviour.BlockBehaviour;
import dev.opencollab.paletter.behaviour.ItemBehaviour;
import dev.opencollab.paletter.block.ResolvedBlock;
import dev.opencollab.paletter.vanilla.BedrockBlocks;
import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.item.BedrockItemStack;
import dev.opencollab.paletter.item.ResolvedItem;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockBlockType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
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
    /** The block definitions a codec helper gets from {@link Palette#applyTo}. */
    private DefinitionRegistry<BlockDefinition> blockDefinitions;
    private BedrockItemVariant[] variants;
    private BedrockItemStack[] stacks;
    private ItemData[] encoded;
    /** Equal to {@link #encoded}, element by element, but other instances. */
    private ItemData[] copies;
    private int[] runtimeIds;
    /** The key of each of {@link #runtimeIds}, air where a block has none. */
    private BedrockBlockState[] states;
    /** {@link #states} as NBT, what a Java to Bedrock translator or a world reader has. */
    private NbtMap[] stateNbt;
    private String[] identifiers;
    private int[] auxes;
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
        BedrockCodecHelper helper = Bedrock_v2225.CODEC.createHelper();
        palette.applyTo(helper);
        blockDefinitions = helper.getBlockDefinitions();
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
        states = new BedrockBlockState[runtimeIds.length];
        for (int i = 0; i < states.length; i++) {
            BedrockBlockState key = palette.blocks().key(palette.blocks().byRuntimeId(runtimeIds[i]));
            states[i] = key != null ? key : BedrockBlocks.AIR.defaultState();
        }
        stateNbt = new NbtMap[states.length];
        for (int i = 0; i < states.length; i++) {
            stateNbt[i] = states[i].toNbt();
        }
        identifiers = new String[variants.length];
        auxes = new int[variants.length];
        for (int i = 0; i < variants.length; i++) {
            identifiers[i] = variants[i].type().identifier();
            auxes[i] = variants[i].aux();
        }
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

    /** A block runtime id to its definition through the registry the codec decodes with ({@link Palette#applyTo}). */
    @Benchmark
    public BlockDefinition decodeRuntimeId() {
        return blockDefinitions.getDefinition(runtimeIds[nextBlock()]);
    }

    /** A block by runtime id to its values, what a chunk palette read does. */
    @Benchmark
    public float blockBehaviour() {
        return palette.resolveBlock(runtimeIds[nextBlock()]).behaviour().get(BlockBehaviour.FRICTION);
    }

    @Benchmark
    public ResolvedBlock resolveState() {
        return palette.resolve(states[nextBlock()]);
    }

    @Benchmark
    public float resolveStateBehaviour() {
        return palette.resolve(states[nextBlock()]).behaviour().get(BlockBehaviour.FRICTION);
    }

    @Benchmark
    public int resolveItemBehaviour() {
        return palette.resolve(variants[nextItem()]).behaviour().getOrElse(ItemBehaviour.MAX_STACK_SIZE, 0);
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

    /** A block state of NBT to its handle, the exact lookup of a translator. */
    @Benchmark
    public BedrockBlockState blockStateExact() {
        return palette.blockState(stateNbt[nextBlock()], Lookup.EXACT);
    }

    /** A block state of an identifier and values to its handle, the exact lookup without NBT. */
    @Benchmark
    public BedrockBlockState blockStateExactValues() {
        BedrockBlockState state = states[nextBlock()];
        return palette.blockState(state.type().identifier(), state.values(), Lookup.EXACT);
    }

    /** An identifier and aux to its item variant, the exact lookup of a translator. */
    @Benchmark
    public BedrockItemVariant itemVariantExact() {
        int i = nextItem();
        return palette.itemVariant(identifiers[i], auxes[i], Lookup.EXACT);
    }

    /** A vanilla (or custom) block handle to its runtime id. */
    @Benchmark
    public int runtimeIdOfState() {
        return palette.runtimeId(states[nextBlock()]);
    }

    /** A vanilla block handle to its runtime id. */
    @Benchmark
    public BedrockBlockType vanillaKey() {
        RegisteredBlockState state = palette.blocks().byRuntimeId(runtimeIds[nextBlock()]);
        BedrockBlockState key = palette.blocks().key(state);
        return key == null ? null : key.type();
    }
}
