package dev.opencollab.paletter.tools.bds;

import com.sun.net.httpserver.HttpServer;
import dev.opencollab.paletter.tools.Http;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code bds fetch} against a local server: a cached zip makes no request, a refusal and a missing zip have exit codes of their own. */
class BdsFetchTest {
    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(200);
    private byte[] served;

    @BeforeEach
    void start(@TempDir Path directory) throws IOException {
        Path zip = directory.resolve("served.zip");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream stream = new ZipOutputStream(out)) {
            stream.putNextEntry(new ZipEntry("bedrock_server.exe"));
            stream.write("a server".repeat(100).getBytes(StandardCharsets.UTF_8));
            stream.closeEntry();
        }
        served = Files.readAllBytes(zip);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            if (status.get() != 200) {
                exchange.sendResponseHeaders(status.get(), -1);
            } else {
                exchange.sendResponseHeaders(200, served.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(served);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private List<BdsDistribution> build(String version) {
        String base = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
        return List.of(new BdsDistribution(version, false, "win", base + "/bin-win/bedrock-server-" + version + ".zip"),
            new BdsDistribution(version, false, "linux", base + "/bin-linux/bedrock-server-" + version + ".zip"));
    }

    /** The policy of minecraft.net, with the waits counted instead of spent. */
    private static Http minecraft(List<Long> waits) {
        return new Http(Duration.ofSeconds(2), Duration.ofSeconds(2), Http.MINECRAFT.tries(), Http.MINECRAFT.backoff(), Duration.ofMinutes(30),
            Http.MINECRAFT.retryAfterLimit(), waits::add);
    }

    private static void record(Path root, String version, String sha256) throws IOException {
        Files.createDirectories(root.resolve("data/bds"));
        Files.writeString(root.resolve("data/bds/builds.json"), "{\"builds\": {\"" + version + "\": {\"protocol\": 1, \"keptBy\": \"update\", "
            + "\"zips\": {\"win\": {\"sha256\": \"" + sha256 + "\", \"size\": 1}, \"linux\": {\"sha256\": \"" + sha256 + "\", \"size\": 1}}}}}");
    }

    @Test
    void cachedZipsWithTheRecordedChecksumMakeNoRequest(@TempDir Path root, @TempDir Path cache) throws Exception {
        List<Long> waits = new ArrayList<>();
        assertEquals(0, BdsFetch.fetch(root, cache, build("1.26.63.1"), minecraft(waits)));
        assertEquals(2, requests.get(), "the Windows and the Linux zip");
        record(root, "1.26.63.1", ZipChecksums.sha256(build("1.26.63.1").get(0).zipFile(cache)));
        assertEquals(0, BdsFetch.fetch(root, cache, build("1.26.63.1"), minecraft(waits)));
        assertEquals(2, requests.get(), "no request for zips the cache holds");
    }

    @Test
    void aRefusalExitsWithFourAfterOneRetry(@TempDir Path root, @TempDir Path cache) throws Exception {
        status.set(429);
        List<Long> waits = new ArrayList<>();
        assertEquals(BdsFetch.EXIT_REFUSED, BdsFetch.fetch(root, cache, build("1.26.63.1"), minecraft(waits)));
        assertEquals(2, requests.get(), "one try and one retry, then the next zip is not asked for");
        assertEquals(List.of(60_000L), waits);
        status.set(403);
        requests.set(0);
        assertEquals(BdsFetch.EXIT_REFUSED, BdsFetch.fetch(root, cache, build("1.26.63.1"), minecraft(waits)));
        assertEquals(1, requests.get());
    }

    @Test
    void aMissingZipExitsWithFive(@TempDir Path root, @TempDir Path cache) throws Exception {
        status.set(404);
        assertEquals(BdsFetch.EXIT_GONE, BdsFetch.fetch(root, cache, build("1.26.36.1"), minecraft(new ArrayList<>())));
        assertEquals(1, requests.get());
        assertFalse(Files.exists(build("1.26.36.1").get(0).zipFile(cache)));
    }

    @Test
    void theCacheKeyNamesTheBuildAndItsRecordedChecksum(@TempDir Path root) throws Exception {
        assertEquals("bds-zips-1.26.63.1-new", BdsFetch.cacheKey(root, false, "1.26.63.1"));
        record(root, "1.26.63.1", "0123456789abcdef".repeat(4));
        assertEquals("bds-zips-1.26.63.1-0123456789abcdef", BdsFetch.cacheKey(root, false, "1.26.63.1"));
        assertTrue(BdsFetch.cacheKey(root, true, "1.26.70.24").endsWith("-new"), "a preview has no recorded zip");
    }

    @Test
    void theZipAgentNamesTheProjectInABrowserLikeForm() {
        assertTrue(BdsDistribution.ZIP_AGENT.startsWith("Mozilla/5.0 "), BdsDistribution.ZIP_AGENT);
        assertFalse(BdsDistribution.ZIP_AGENT.contains("@"), BdsDistribution.ZIP_AGENT);
    }
}
