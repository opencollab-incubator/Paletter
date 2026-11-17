package org.geysermc.paletter.tools.bds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * BDS's documentation generator: with a {@code test_config.json} next to the executable BDS writes its metadata ({@code docs/}:
 * the JSON schemas of every data-driven file, vanilla data lists, Script API modules) after it starts, and stops by itself.
 * See {@code docs/data-pipeline.md}.
 */
public final class BdsDocGen {
    private BdsDocGen() {
    }

    /** Runs the generator on the extracted server (a start of its own, because BDS stops) and returns the {@code docs} directory. */
    public static Path generate(Path server, Path log, int timeout, int port) throws IOException, InterruptedException {
        BdsRunner.configure(server, port);
        Path config = server.resolve("test_config.json");
        try (BdsServer bds = new BdsServer(server, false)) {
            Files.writeString(config, "{\"generate_documentation\": true}");
            bds.start(log);
            if (bds.await(line -> line.contains("Server stop requested."), timeout) == null) {
                throw new IOException("BDS did not generate its documentation within " + timeout + "s; see " + log);
            }
        } finally {
            // The file makes BDS stop right after it starts
            Files.deleteIfExists(config);
        }
        return server.resolve("docs");
    }

    /**
     * Moves the generated tree out of the server directory into {@code <cache>/docgen/<build>}: the raw tree stays in the cache
     * (it has Mojang's text, 64 MB); only the extract of its facts is committed.
     */
    public static Path keep(Path docs, Path cache, String build) throws IOException {
        Path kept = cache.resolve("docgen").resolve(build);
        BdsDistribution.deleteRecursively(kept);
        Files.createDirectories(kept.getParent());
        Files.move(docs, kept);
        return kept;
    }
}
