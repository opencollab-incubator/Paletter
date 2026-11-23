package dev.opencollab.paletter.tools;

import dev.opencollab.paletter.tools.bds.BdsDistribution;
import dev.opencollab.paletter.tools.bds.BdsRun;
import picocli.CommandLine.Option;

import java.nio.file.Path;

/** The options of every command that starts a BDS: the build, and where and how long it runs. */
class BdsOptions extends BuildOptions {
    static final String CACHE = "The BDS cache: downloaded zips, extracted servers and run logs (default: $PALETTER_BDS_CACHE or the Gradle user home's)";
    static final String KEEP_SERVER = "Keep the extracted server after the run";

    @Option(names = "--cache", description = CACHE)
    Path cache;

    @Option(names = "--port", description = "The server port; the IPv6 port is the next one (default: the command's)")
    Integer port;

    @Option(names = "--timeout", description = "Seconds to wait for the server to start (default: the command's)")
    Integer timeout;

    /** The run of this command, with the port and timeout it uses unless the command line says otherwise. */
    BdsRun run(int defaultPort, int defaultTimeout, boolean keepServer) {
        // A Git Bash path (/c/...) reaches Java unconverted and would silently mean C:\c\...
        if (cache != null && java.io.File.separatorChar == '\\' && cache.toString().matches("\\\\[A-Za-z]\\\\.*")) {
            throw new IllegalArgumentException(cache + " is a Git Bash path; pass --cache as C:/...");
        }
        return new BdsRun(version(), preview(), cache, port == null ? defaultPort : port, timeout == null ? defaultTimeout : timeout, keepServer);
    }

    /** The cache a command that only downloads uses. */
    static Path cache(Path option) {
        return option != null ? option : BdsDistribution.defaultCache();
    }
}
