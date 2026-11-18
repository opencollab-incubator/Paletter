package dev.opencollab.paletter.behaviour;

import dev.opencollab.paletter.data.PaletteDataSource;
import dev.opencollab.paletter.internal.behaviour.BehaviourStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

import static org.junit.jupiter.api.Assertions.assertSame;

class BehaviourDataSourceTest {
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
