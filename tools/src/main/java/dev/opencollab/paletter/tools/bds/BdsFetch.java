package dev.opencollab.paletter.tools.bds;

import dev.opencollab.paletter.tools.BuildNumber;
import dev.opencollab.paletter.tools.Console;
import dev.opencollab.paletter.tools.Http;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code bds fetch} and {@code bds cache-key}: the zips of one build in the cache, and nothing else. The workflows restore
 * the zip cache under {@link #cacheKey}, fetch (no request when the cached zips are whole and match the recorded sha256)
 * and save the cache right after, before BDS runs. A refusal of minecraft.net ends the run with an exit code of its own.
 */
public final class BdsFetch {
    /** minecraft.net refused the request (403, or 429 after the one retry): try again another day. */
    public static final int EXIT_REFUSED = 4;
    /** minecraft.net no longer serves the zip (404): a kept build is frozen by hand. */
    public static final int EXIT_GONE = 5;
    /** The length of the sha256 prefix in the cache key. */
    static final int KEY_SHA_LENGTH = 16;

    private BdsFetch() {
    }

    /** The zips a run of the build reads: the Windows one it runs and the Linux one the BDS inventory reads. */
    public static List<BdsDistribution> distributions(boolean preview, String version) throws IOException, InterruptedException {
        BuildNumber.require(version);
        return List.of(BdsDistribution.resolve(false, preview, version), BdsDistribution.resolve(true, preview, version));
    }

    /**
     * The key of the zip cache of a build: {@code bds-zips-<build>-<the first 16 hex digits of the Windows zip's recorded sha256>},
     * or {@code -new} when none is recorded. Restored with the prefix {@code bds-zips-<build>-}, so a build recorded since its
     * zips were cached finds them and saves them under the new key.
     */
    public static String cacheKey(Path root, boolean preview, String version) throws IOException, InterruptedException {
        BdsDistribution windows = distributions(preview, version).get(0);
        String sha256 = ZipChecksums.recorded(root, windows.platform(), version);
        return "bds-zips-" + version + "-" + (sha256 == null ? "new" : sha256.substring(0, KEY_SHA_LENGTH));
    }

    /**
     * Downloads every zip the cache lacks (or holds broken, or with another sha256 than the recorded one).
     *
     * @return the process exit code: 0, {@link #EXIT_REFUSED}, {@link #EXIT_GONE}, or 1 for any other failure
     */
    public static int fetch(Path root, Path cache, List<BdsDistribution> distributions, Http http) throws IOException, InterruptedException {
        for (BdsDistribution distribution : distributions) {
            try {
                Path zip = distribution.zip(cache, ZipChecksums.recorded(root, distribution.platform(), distribution.version()), http);
                System.out.println(distribution.platform() + " " + distribution.version() + ": " + zip);
            } catch (Http.NetworkException e) {
                int code = exitCode(e);
                Console.error(switch (code) {
                    case EXIT_REFUSED -> "minecraft.net refused " + distribution.url() + " (HTTP " + e.status() + "); not tried again: run again later";
                    case EXIT_GONE -> "minecraft.net no longer serves " + distribution.url() + " (HTTP 404); if " + distribution.version()
                        + " is a kept build, freeze it: builds record --freeze " + distribution.version() + "=<reason>";
                    default -> e.getMessage();
                });
                return code;
            }
        }
        return 0;
    }

    /** The exit code of a request that failed: a refusal (403, 429), a zip that is gone (404), else 1. */
    static int exitCode(Http.NetworkException e) {
        return switch (e.status()) {
            case 403, 429 -> EXIT_REFUSED;
            case 404 -> EXIT_GONE;
            default -> 1;
        };
    }
}
