package dev.opencollab.paletter;

import dev.opencollab.paletter.block.BlockHashes;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.vanilla.BedrockBlocks;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The map that interns block states and item components across versions is dropped once the versions a Paletter serves
 * are loaded, also when that Paletter loads through the shared bundled one and does not serve every bundled version.
 * Measured in a JVM of its own: other tests of this JVM load the bundled versions too, which would release it anyway.
 */
class InternReleaseTest {
    @Test
    void releasedOnceTheServedVersionsAreLoaded() throws Exception {
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx1g",
            "-cp", System.getProperty("java.class.path"), Check.class.getName());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the checking JVM did not finish in 120 seconds");
            }
            String text = output.get(10, TimeUnit.SECONDS);
            assertEquals(0, process.exitValue(), text);
            assertEquals("before=false after=true later=ok", text.lines().reduce((first, second) -> second).orElseThrow(), text);
        } finally {
            process.destroyForcibly();
        }
    }

    /** The checking JVM: a Paletter without the oldest bundled version, then a version loaded after the release. */
    public static final class Check {
        public static void main(String[] args) {
            DataVersion oldest = Paletter.bundled().versions().getFirst();
            Paletter paletter = Paletter.builder().versions(version -> !version.equals(oldest)).build();
            boolean before = paletter.internReleased();
            for (DataVersion version : paletter.versions()) {
                paletter.palette(version.protocolVersion());
            }
            boolean after = paletter.internReleased();
            // The oldest version is loaded after the release: it gets copies of its own, with the same values
            Palette later = Paletter.builder().build().palette(oldest.protocolVersion());
            int air = later.runtimeId(BedrockBlocks.AIR.defaultState());
            String laterResult = air == BlockHashes.hash(BedrockBlocks.AIR.defaultState().toNbt()) && later.blocks().size() > 1000 ? "ok" : "broken";
            System.out.println("before=" + before + " after=" + after + " later=" + laterResult);
        }
    }
}
