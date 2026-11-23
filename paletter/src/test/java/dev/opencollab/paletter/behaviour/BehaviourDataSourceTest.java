package dev.opencollab.paletter.behaviour;

import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.block.ResolvedBlock;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.data.PaletteDataSource;
import dev.opencollab.paletter.internal.behaviour.BehaviourStore;
import dev.opencollab.paletter.internal.data.PropertyStore;
import dev.opencollab.paletter.internal.data.Renames;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.shape.VoxelShapeStore;
import dev.opencollab.paletter.vanilla.BedrockBlocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviourDataSourceTest {
    /** The bundled data, counting the reads of the behaviour store. */
    private static final class Counting implements PaletteDataSource {
        private final PaletteDataSource bundled = PaletteDataSource.bundled();
        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public List<DataVersion> versions() {
            return bundled.versions();
        }

        @Override
        public VanillaData load(DataVersion version) throws IOException {
            return bundled.load(version);
        }

        @Override
        public BehaviourStore behaviour() {
            reads.incrementAndGet();
            return bundled.behaviour();
        }

        @Override
        public Renames renames() {
            return bundled.renames();
        }

        @Override
        public PropertyStore properties() {
            return bundled.properties();
        }

        @Override
        public VoxelShapeStore voxelShapes() {
            return bundled.voxelShapes();
        }
    }

    private static boolean newestRelease(DataVersion version) {
        return version.equals(PaletteDataSource.bundled().versions().stream().filter(v -> !v.isPreview()).reduce((a, b) -> b).orElseThrow());
    }

    @Test
    void theBuildReadsTheBehaviourDataAndNoLookupWaits() {
        Counting source = new Counting();
        Paletter paletter = Paletter.builder().data(source).versions(BehaviourDataSourceTest::newestRelease).build();
        int read = source.reads.get();
        assertTrue(read > 0, "read by the build");
        Palette palette = paletter.palette(paletter.latest().protocolVersion());
        ResolvedBlock stone = palette.resolve(BedrockBlocks.STONE.defaultState());
        assertNotNull(stone.definition());
        stone.behaviour().get(BlockBehaviour.FRICTION);
        assertEquals(read, source.reads.get(), "the columns were built by the build");
    }

    @Test
    void withoutBehaviourTheDataIsNeverRead() {
        Counting source = new Counting();
        Paletter paletter = Paletter.builder().data(source).versions(BehaviourDataSourceTest::newestRelease).behaviour(false).build();
        Palette palette = paletter.palette(paletter.latest().protocolVersion());
        ResolvedBlock stone = palette.resolve(BedrockBlocks.STONE.defaultState());
        palette.resolveBlock(stone.definition().getRuntimeId());
        assertThrows(IllegalStateException.class, () -> stone.behaviour().get(BlockBehaviour.FRICTION));
        assertEquals(0, source.reads.get());
    }

    @Test
    void behaviourDataIsParsedOnceWhenSeveralThreadsAskAtTheSameTime(@TempDir Path directory) throws Exception {
        try (InputStream bundled = PaletteDataSource.class.getResourceAsStream("/paletter/behaviour.nbt")) {
            Files.copy(bundled, directory.resolve("behaviour.nbt"));
        }
        PaletteDataSource source = PaletteDataSource.directory(directory);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(threads);
        Callable<BehaviourStore> ask = () -> {
            start.countDown();
            start.await();
            return source.behaviour();
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<BehaviourStore>> stores = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                stores.add(pool.submit(ask));
            }
            for (Future<BehaviourStore> store : stores) {
                assertSame(stores.getFirst().get(), store.get());
            }
        }
    }
}
