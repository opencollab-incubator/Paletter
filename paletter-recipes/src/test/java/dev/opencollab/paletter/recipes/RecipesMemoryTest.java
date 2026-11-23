package dev.opencollab.paletter.recipes;

import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Memory guard of the recipes (as {@code MemoryBaselineTest} is Paletter's): what the recipes of one version retain while
 * held, and that {@link VanillaRecipes#of} does not pin them once nobody holds them. Measured as the heap in use after a full
 * collection, in a JVM of its own with a serial collector that clears soft references at every collection
 * ({@code -XX:SoftRefLRUPolicyMSPerMB=0}), so what other tests loaded does not count. The numbers are printed as
 * {@code MEMORY} lines ({@code ./gradlew :paletter-recipes:test --tests '*RecipesMemoryTest' -i}).
 */
class RecipesMemoryTest {
    /** The recipes of the newest version while held. Measured 3.8 MB (3,780,256 bytes); raise only for a reason, and say so in the commit. */
    private static final long MAX_HELD_BYTES = 4_200_000;
    /** What stays once every version was read and dropped: nothing but noise of the collector. */
    private static final long MAX_PINNED_BYTES = 500_000;

    @Test
    void recipesAreSmallAndNotPinned() throws IOException, InterruptedException {
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx256m", "-XX:+UseSerialGC", "-XX:SoftRefLRUPolicyMSPerMB=0", "-cp", System.getProperty("java.class.path"),
            Measure.class.getName());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the measuring JVM did not finish");
            if (process.exitValue() != 0) {
                fail("the measuring JVM failed: " + output);
            }
            String[] numbers = output.lines().reduce((first, second) -> second).orElseThrow().split(" ");
            long held = Long.parseLong(numbers[0]);
            long pinned = Long.parseLong(numbers[1]);
            System.out.println("MEMORY recipes of the newest version held bytes=" + held + " (limit " + MAX_HELD_BYTES + ")");
            System.out.println("MEMORY recipes of every version after they were dropped bytes=" + pinned + " (limit " + MAX_PINNED_BYTES + ")");
            assertTrue(held <= MAX_HELD_BYTES, "held " + held);
            assertTrue(pinned <= MAX_PINNED_BYTES, "pinned " + pinned);
        } finally {
            process.destroyForcibly();
        }
    }

    /** The measuring JVM: prints the bytes the newest version's recipes retain while held, and what stays once all are dropped. */
    public static final class Measure {
        public static void main(String[] args) {
            // What is shared with everything else is loaded before the baseline: the data source's version index, and the
            // vanilla constants the recipes' items are (linked to the newest data, about 8 MB, for the life of the JVM)
            List<DataVersion> versions = VanillaRecipes.versions();
            BedrockItems.STICK.variants();
            long base = used();
            VanillaRecipes held = VanillaRecipes.of(versions.getLast());
            long withHeld = used();
            if (held.recipes().isEmpty()) {
                throw new IllegalStateException("no recipes");
            }
            held = null;
            for (DataVersion version : versions) {
                VanillaRecipes.of(version);
            }
            // A soft reference is cleared by a collection after the one that saw it last used: what is left is pinned
            long after = used();
            System.out.println((withHeld - after) + " " + Math.max(0, after - base));
        }

        private static long used() {
            for (int i = 0; i < 4; i++) {
                System.gc();
            }
            Runtime runtime = Runtime.getRuntime();
            return runtime.totalMemory() - runtime.freeMemory();
        }
    }
}
