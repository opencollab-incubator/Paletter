package dev.opencollab.paletter;

import com.sun.management.ThreadMXBean;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import dev.opencollab.paletter.behaviour.BlockBehaviour;
import dev.opencollab.paletter.behaviour.ItemBehaviour;
import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.item.BedrockItemStack;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockBlocks;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per packet lookups of a translator: they give the right answer on palettes extended by several layers, and do not
 * allocate (the timings are in the JMH benchmarks of src/jmh). The allocation tests count the bytes the test thread
 * allocates once the lookups are compiled, the least of several measurements.
 */
class HotPathTest {
    private static final Paletter PALETTER = Paletter.bundled();
    private static final int ROUNDS = 20;
    private static final int WARMUP_ROUNDS = 200;
    /** The JIT compiles in the background: a cold run may need more rounds before the lookups are compiled and do not allocate. */
    private static final int ATTEMPTS = 60;

    /** The newest version with custom blocks in three layers, the way a server with session layers has it. */
    private static Palette layered() {
        return PALETTER.palette(PALETTER.latest().protocolVersion())
            .extend(layer("first", 0))
            .extend(layer("second", 3))
            .extend(layer("third", 6));
    }

    private static PaletteLayer layer(String id, int first) {
        PaletteLayer.Builder builder = PaletteLayer.builder(id);
        for (int i = first; i < first + 3; i++) {
            builder.block(CustomBlock.builder("test:block_" + i)
                .property(BlockProperty.bool("test:lit"))
                .property(BlockProperty.range("test:level", 0, i))
                .build());
        }
        return builder.build();
    }

    /**
     * Runs a lookup over every input until it is compiled, then measures what the same work allocates. The JIT may still
     * be compiling (and allocating in the interpreter) during a measurement, so the least of {@link #ATTEMPTS} is taken.
     *
     * @return the bytes allocated by {@link #ROUNDS} rounds over the inputs
     */
    private static long allocated(int inputs, IntUnaryOperator lookup) {
        long sum = 0;
        for (int warmup = 0; warmup < WARMUP_ROUNDS; warmup++) {
            for (int i = 0; i < inputs; i++) {
                sum += lookup.applyAsInt(i);
            }
        }
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long least = Long.MAX_VALUE;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            long before = threads.getThreadAllocatedBytes(thread);
            for (int round = 0; round < ROUNDS; round++) {
                for (int i = 0; i < inputs; i++) {
                    sum += lookup.applyAsInt(i);
                }
            }
            least = Math.min(least, threads.getThreadAllocatedBytes(thread) - before);
            if (least < 1024) {
                break;
            }
        }
        // Keeps the results alive, so the lookups are not optimized away
        assertTrue(sum != Long.MIN_VALUE);
        return least;
    }

    /**
     * Encoding allocates the item data it returns, and nothing more: exactly the item data, or the item data and its
     * builder where the JIT keeps the builder. The item data is kept, so it cannot be optimized away with whatever else
     * encode would allocate; anything more (the 24 bytes of a small record per stack) fails.
     */
    @Test
    void encodeAllocatesOnlyTheItemData() {
        Sizes sizes = Sizes.measure();
        for (DataVersion version : List.of(PALETTER.latest(), PALETTER.versions().getFirst())) {
            Palette palette = PALETTER.vanilla(version);
            BedrockItemStack[] stacks = palette.itemVariants().stream().map(BedrockItemStack::of).toArray(BedrockItemStack[]::new);
            ItemData[] encoded = new ItemData[stacks.length];
            long allocated = allocated(stacks.length, i -> {
                encoded[i] = palette.encode(stacks[i]);
                return encoded[i].getCount();
            });
            long perStack = allocated / ((long) ROUNDS * stacks.length);
            assertTrue(perStack == sizes.itemData() || perStack == sizes.itemData() + sizes.builder(), version + ": " + perStack
                + " bytes per encode; an item data takes " + sizes.itemData() + ", its builder " + sizes.builder());
        }
    }

    /**
     * The bytes of one {@link ItemData} alone, and of its builder alone: both are kept, so neither is optimized away.
     * Measured by the test that needs them, so a bad measurement fails that test only.
     */
    private record Sizes(long builder, long itemData) {
        static Sizes measure() {
            ItemData template = ItemData.builder().definition(ItemData.AIR.getDefinition()).count(1).build();
            int count = 10_000;
            ItemData.Builder[] builders = new ItemData.Builder[count];
            ItemData[] made = new ItemData[count];
            long builder = allocated(count, i -> {
                builders[i] = HotPathTest.builder(template);
                return builders[i] == null ? 0 : 1;
            });
            long both = allocated(count, i -> {
                builders[i] = HotPathTest.builder(template);
                made[i] = builders[i].build();
                return made[i].getCount();
            });
            return new Sizes(builder / ((long) ROUNDS * count), (both - builder) / ((long) ROUNDS * count));
        }
    }

    /** The builder of encode, filled the same way. */
    private static ItemData.Builder builder(ItemData data) {
        return ItemData.builder()
            .definition(data.getDefinition())
            .damage(data.getDamage())
            .count(data.getCount())
            .tag(data.getTag())
            .blockDefinition(data.getBlockDefinition());
    }

    @Test
    void decodeOfAirDoesNotAllocate() {
        Palette palette = layered();
        long allocated = allocated(1000, i -> palette.decode(ItemData.AIR) == null ? 0 : 1);
        assertTrue(allocated < 4096, allocated + " bytes allocated by " + ROUNDS * 1000 + " decodes");
    }

    @Test
    void sameStackDoesNotAllocate() {
        Palette palette = layered();
        List<ItemData> stacks = new ArrayList<>();
        List<ItemData> copies = new ArrayList<>();
        for (BedrockItemVariant variant : palette.itemVariants()) {
            stacks.add(palette.encode(BedrockItemStack.of(variant)));
            copies.add(palette.encode(BedrockItemStack.of(variant)));
        }
        long allocated = allocated(stacks.size(), i -> palette.sameStack(stacks.get(i), copies.get(i)) ? 1 : 0);
        assertTrue(allocated < 4096, allocated + " bytes allocated by " + ROUNDS * stacks.size() + " comparisons");
    }

    @Test
    void customBlockKeysAcrossLayers() {
        Palette palette = layered();
        int customs = 0;
        for (int i = 0; i < palette.blocks().size(); i++) {
            RegisteredBlockState state = palette.blocks().byIndex(i);
            if (!state.isCustom()) {
                continue;
            }
            customs++;
            BedrockBlockState key = palette.blocks().key(state);
            assertNotNull(key, state.toString());
            assertEquals(state.getState(), key.toNbt(), state.toString());
            assertSame(key.type(), palette.blocks().block(state).customBlock().type(), state.toString());
            assertEquals(state.getRuntimeId(), palette.runtimeId(key), state.toString());
        }
        // 9 blocks of 2 * (level + 1) states
        assertEquals(2 * (1 + 2 + 3 + 4 + 5 + 6 + 7 + 8 + 9), customs);
    }

    @Test
    void customBlockKeysDoNotAllocate() {
        Palette palette = layered();
        List<RegisteredBlockState> customs = new ArrayList<>();
        for (int i = 0; i < palette.blocks().size(); i++) {
            if (palette.blocks().byIndex(i).isCustom()) {
                customs.add(palette.blocks().byIndex(i));
            }
        }
        long allocated = allocated(customs.size(), i -> palette.blocks().key(customs.get(i)).hashCode());
        assertTrue(allocated < 4096, allocated + " bytes allocated by " + ROUNDS * customs.size() + " lookups");
    }

    @Test
    void vanillaStatesOfOtherVersionsAreNotInAPalette() {
        // Both palettes come from one Paletter, so they share the interned vanilla definitions
        Palette latest = PALETTER.palette(PALETTER.latest().protocolVersion());
        Palette oldest = PALETTER.vanilla(PALETTER.versions().getFirst());
        int newer = 0;
        for (int i = 0; i < latest.blocks().size(); i++) {
            RegisteredBlockState state = latest.blocks().byIndex(i);
            assertSame(state, latest.blocks().exact(state.getState()));
            if (oldest.blocks().byRuntimeId(state.getRuntimeId()) == null) {
                newer++;
                assertNull(oldest.blocks().exact(state.getState()), state.toString());
            }
        }
        assertTrue(newer > 0, "the oldest version has every state of the newest");
    }

    /** A plain loop, no lambda: other tests make the shared {@link #allocated} helper megamorphic. */
    private static long allocatedBy(Runnable once) {
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        once.run(); // fills the lazy caches
        long before = threads.getThreadAllocatedBytes(thread);
        once.run();
        return threads.getThreadAllocatedBytes(thread) - before;
    }

    @Test
    void resolveStateDoesNotAllocate() {
        Palette palette = layered();
        BedrockBlockState[] states = new BedrockBlockState[palette.blocks().size()];
        for (int i = 0; i < states.length; i++) {
            BedrockBlockState key = palette.blocks().key(palette.blocks().byIndex(i));
            states[i] = key != null ? key : BedrockBlocks.AIR.defaultState();
        }
        for (BedrockBlockState state : states) {
            assertSame(palette.resolve(state), palette.resolve(state));
        }
        long[] sum = {0};
        long allocated = allocatedBy(() -> {
            for (BedrockBlockState state : states) {
                sum[0] += Float.floatToIntBits(palette.resolve(state).behaviour().get(BlockBehaviour.FRICTION));
            }
        });
        // Holds interpreted too: the resolutions are cached, not scalar-replaced
        assertTrue(allocated < 1024, allocated + " bytes allocated by " + states.length + " lookups");
    }

    /** What a chunk or inventory writer asks per block and per stack: ids of states, definitions of ids. */
    @Test
    void runtimeIdLookupsDoNotAllocate() {
        Palette palette = layered();
        int blocks = palette.blocks().size();
        BedrockBlockState[] states = new BedrockBlockState[blocks];
        int[] blockIds = new int[blocks];
        for (int i = 0; i < blocks; i++) {
            BedrockBlockState key = palette.blocks().key(palette.blocks().byIndex(i));
            states[i] = key != null ? key : BedrockBlocks.AIR.defaultState();
            blockIds[i] = palette.blocks().byIndex(i).getRuntimeId();
        }
        long allocated = allocated(blocks, i -> palette.runtimeId(states[i]));
        assertTrue(allocated < 4096, allocated + " bytes allocated by " + ROUNDS * blocks + " runtimeId lookups");
        allocated = allocated(blocks, i -> palette.blockDefinition(blockIds[i]) == null ? 0 : 1);
        assertTrue(allocated < 4096, allocated + " bytes allocated by " + ROUNDS * blocks + " block definition lookups");
        allocated = allocated(blocks, i -> palette.blockState(blockIds[i]) == null ? 0 : 1);
        assertTrue(allocated < 4096, allocated + " bytes allocated by " + ROUNDS * blocks + " block state lookups");

        int[] itemIds = palette.items().definitions().stream().mapToInt(item -> item.getRuntimeId()).toArray();
        allocated = allocated(itemIds.length, i -> palette.itemDefinition(itemIds[i]) == null ? 0 : 1);
        assertTrue(allocated < 4096, allocated + " bytes allocated by " + ROUNDS * itemIds.length + " item definition lookups");
    }

    @Test
    void resolveVariantBehaviourDoesNotAllocate() {
        Palette palette = layered();
        List<BedrockItemVariant> variants = palette.itemVariants();
        long allocated = allocated(variants.size(),
            i -> palette.resolve(variants.get(i)).behaviour().getOrElse(ItemBehaviour.MAX_STACK_SIZE, 0));
        assertTrue(allocated < 4096, allocated + " bytes allocated by " + ROUNDS * variants.size() + " lookups");
    }

    /** Six layers: the lookups of the canonical table are merged past four maps, and give what the base gives for vanilla items. */
    @Test
    void resolveVariantStaysCorrectAndAllocationFreeAtDepthSix() {
        Palette base = PALETTER.palette(PALETTER.latest().protocolVersion());
        Palette deep = base;
        for (int i = 0; i < 6; i++) {
            deep = deep.extend(PaletteLayer.builder("deep:" + i)
                .item(CustomItem.builder("deep:item_" + i).build())
                .block(CustomBlock.builder("deep:block_" + i).build()).build());
        }
        Palette palette = deep;
        List<BedrockItemVariant> variants = palette.itemVariants();
        for (BedrockItemVariant variant : base.itemVariants()) {
            var expected = base.resolve(variant);
            var actual = palette.resolve(variant);
            assertEquals(expected == null, actual == null, variant.toString());
            if (expected != null) {
                assertEquals(expected.definition().getIdentifier(), actual.definition().getIdentifier(), variant.toString());
            }
        }
        assertEquals(base.itemVariants().size() + 12, variants.size());
        long allocated = allocated(variants.size(),
            i -> palette.resolve(variants.get(i)).behaviour().getOrElse(ItemBehaviour.MAX_STACK_SIZE, 0));
        assertTrue(allocated < 4096, allocated + " bytes allocated by " + ROUNDS * variants.size() + " lookups");
    }

    @Test
    void resolveBlockDoesNotAllocate() {
        Palette palette = layered();
        int[] runtimeIds = new int[palette.blocks().size()];
        for (int i = 0; i < runtimeIds.length; i++) {
            runtimeIds[i] = palette.blocks().byIndex(i).getRuntimeId();
        }
        for (int runtimeId : runtimeIds) {
            assertSame(palette.resolveBlock(runtimeId), palette.resolveBlock(runtimeId));
        }
        long[] sum = {0};
        long allocated = allocatedBy(() -> {
            for (int runtimeId : runtimeIds) {
                sum[0] += Float.floatToIntBits(palette.resolveBlock(runtimeId).behaviour().get(BlockBehaviour.FRICTION));
            }
        });
        assertTrue(allocated < 1024, allocated + " bytes allocated by " + runtimeIds.length + " lookups");
    }

    @Test
    void anExtendedPaletteSharesTheResolutionsOfItsParent() {
        Palette parent = PALETTER.palette(PALETTER.latest().protocolVersion()).extend(layer("parent", 0));
        Palette child = parent.extend(layer("child", 3));
        int shared = 0;
        for (int i = 0; i < parent.blocks().size(); i++) {
            RegisteredBlockState state = parent.blocks().byIndex(i);
            assertSame(parent.resolveBlock(state.getRuntimeId()), child.resolveBlock(state.getRuntimeId()), state.toString());
            BedrockBlockState key = parent.blocks().key(state);
            if (key != null) {
                assertSame(parent.resolve(key), child.resolve(key), state.toString());
                shared++;
            }
        }
        assertTrue(shared > 0);
        // The child's own blocks are its own, and the parent does not have them
        RegisteredBlockState own = child.blocks().byIndex(child.blocks().size() - 1);
        assertNotNull(child.resolveBlock(own.getRuntimeId()));
        assertNull(parent.resolveBlock(own.getRuntimeId()));
    }
}
