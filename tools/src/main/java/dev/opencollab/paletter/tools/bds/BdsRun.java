package dev.opencollab.paletter.tools.bds;

import java.io.IOException;
import java.nio.file.Path;

/**
 * What every command that starts a BDS shares: which build, where its zip and server are cached, the port, how long to wait for it
 * to start and whether to keep the extracted server after the run.
 *
 * @param version the build number, or null for the newest of the channel
 * @param preview whether the build is a preview
 * @param cache the BDS cache directory
 * @param port the server port (the IPv6 port is the next one)
 * @param timeout seconds to wait for the server to start
 * @param keepServer whether the extracted server stays after the run
 */
public record BdsRun(String version, boolean preview, Path cache, int port, int timeout, boolean keepServer) {
    /** {@code cache} null means {@link BdsDistribution#defaultCache}. */
    public BdsRun {
        cache = cache == null ? BdsDistribution.defaultCache() : cache;
    }

    /** The build this run uses: the newest of the channel unless {@link #version} names one. */
    public BdsDistribution distribution() throws IOException, InterruptedException {
        return BdsDistribution.resolve(preview, version);
    }

    public BdsRun withVersion(String build) {
        return new BdsRun(build, preview, cache, port, timeout, keepServer);
    }

    public BdsRun withPort(int other) {
        return new BdsRun(version, preview, cache, other, timeout, keepServer);
    }

    public BdsRun withTimeout(int seconds) {
        return new BdsRun(version, preview, cache, port, seconds, keepServer);
    }
}
