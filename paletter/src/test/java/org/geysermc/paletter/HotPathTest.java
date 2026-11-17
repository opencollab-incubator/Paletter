package org.geysermc.paletter;

import com.sun.management.ThreadMXBean;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.paletter.behaviour.BlockBehaviour;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
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
    private static final int ATTEMPTS = 10;

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
            assertEquals(state.getRuntimeId(), palette.runtimeId(key, -1), state.toString());
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

    @Test
    void blockBehaviourDoesNotAllocate() {
        Palette palette = layered();
        int[] runtimeIds = new int[palette.blocks().size()];
        for (int i = 0; i < runtimeIds.length; i++) {
            runtimeIds[i] = palette.blocks().byIndex(i).getRuntimeId();
        }
        long allocated = allocated(runtimeIds.length,
            i -> Float.floatToIntBits(palette.blockBehaviour(runtimeIds[i]).get(BlockBehaviour.FRICTION)));
        assertTrue(allocated < 4096, allocated + " bytes allocated by " + ROUNDS * runtimeIds.length + " lookups");
    }
}
