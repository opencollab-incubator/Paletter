package dev.opencollab.paletter.tools.bds;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BdsDistributionTest {
    private static Path zip(Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("bedrock_server"));
            zip.write("a server".repeat(1000).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }

    @Test
    void aWholeZipPasses(@TempDir Path directory) throws IOException {
        Path zip = zip(directory.resolve("whole.zip"));
        assertDoesNotThrow(() -> BdsDistribution.checkZip(zip));
    }

    @Test
    void aTruncatedZipFails(@TempDir Path directory) throws IOException {
        byte[] whole = Files.readAllBytes(zip(directory.resolve("whole.zip")));
        // the central directory is at the end
        Path truncated = Files.write(directory.resolve("truncated.zip"), Arrays.copyOf(whole, whole.length / 2));
        assertThrows(IOException.class, () -> BdsDistribution.checkZip(truncated));
    }

    @Test
    void anEmptyZipFails(@TempDir Path directory) throws IOException {
        Path empty = directory.resolve("empty.zip");
        new ZipOutputStream(Files.newOutputStream(empty)).close();
        assertThrows(IOException.class, () -> BdsDistribution.checkZip(empty));
    }

    @Test
    void aCachedZipThatReadsIsKept(@TempDir Path cache) throws Exception {
        // the url is never fetched: the cached zip reads
        BdsDistribution distribution = new BdsDistribution("1.0.0.1", false, "linux", "http://127.0.0.1:9/bedrock-server-1.0.0.1.zip");
        Path cached = cache.resolve("zips/linux/bedrock-server-1.0.0.1.zip");
        Files.createDirectories(cached.getParent());
        byte[] bytes = Files.readAllBytes(zip(cached));
        assertArrayEquals(bytes, Files.readAllBytes(distribution.zip(cache)));
    }

    @Test
    void aCachedZipThatDoesNotReadIsDownloadedAgain(@TempDir Path cache) throws Exception {
        byte[] whole = Files.readAllBytes(zip(Files.createTempFile(cache, "whole", ".zip")));
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, whole.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(whole);
            }
        });
        server.start();
        try {
            String url = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/bedrock-server-1.0.0.1.zip";
            BdsDistribution distribution = new BdsDistribution("1.0.0.1", false, "linux", url);
            Path cached = cache.resolve("zips/linux/bedrock-server-1.0.0.1.zip");
            Files.createDirectories(cached.getParent());
            Files.write(cached, Arrays.copyOf(whole, whole.length / 2));
            assertArrayEquals(whole, Files.readAllBytes(distribution.zip(cache)));
            assertFalse(Files.exists(cached.resolveSibling(cached.getFileName() + ".part")));
        } finally {
            server.stop(0);
        }
    }

    /** A download whose sha256 is not the recorded one fails and is not kept; a cached zip with another sha256 is not used. */
    @Test
    void aZipIsCheckedAgainstItsRecordedChecksum(@TempDir Path cache) throws Exception {
        byte[] whole = Files.readAllBytes(zip(Files.createTempFile(cache, "whole", ".zip")));
        Path served = Files.write(cache.resolve("served.zip"), whole);
        String sha256 = ZipChecksums.sha256(served);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, whole.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(whole);
            }
        });
        server.start();
        try {
            String url = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/bedrock-server-1.0.0.1.zip";
            BdsDistribution distribution = new BdsDistribution("1.0.0.1", false, "linux", url);
            Path cached = distribution.zipFile(cache);
            IOException wrong = assertThrows(IOException.class, () -> distribution.zip(cache, "00".repeat(32)));
            assertTrue(wrong.getMessage().contains(sha256) && wrong.getMessage().contains("00".repeat(32)), wrong.getMessage());
            assertFalse(Files.exists(cached), "a zip with another checksum is never kept");
            assertArrayEquals(whole, Files.readAllBytes(distribution.zip(cache, sha256)));
            // Cached with the right checksum: used as it is
            assertArrayEquals(whole, Files.readAllBytes(distribution.zip(cache, sha256)));
        } finally {
            server.stop(0);
        }
    }

    /** The links API is parsed here only; another shape or a missing type fails with the answer quoted. */
    @Test
    void theLinksApiAnswerIsReadOrQuoted() throws IOException {
        String answer = "{\"result\": {\"links\": [{\"downloadType\": \"serverBedrockWindows\", "
            + "\"downloadUrl\": \"https://www.minecraft.net/bedrockdedicatedserver/bin-win/bedrock-server-1.26.63.1.zip\"}]}}";
        assertEquals("https://www.minecraft.net/bedrockdedicatedserver/bin-win/bedrock-server-1.26.63.1.zip",
            BdsDistribution.downloadUrl(answer, "serverBedrockWindows"));
        IOException missing = assertThrows(IOException.class, () -> BdsDistribution.downloadUrl(answer, "serverBedrockPreviewWindows"));
        assertTrue(missing.getMessage().contains("serverBedrockPreviewWindows") && missing.getMessage().contains("bedrock-server-1.26.63.1"));
        IOException shape = assertThrows(IOException.class, () -> BdsDistribution.downloadUrl("{\"data\": {\"downloads\": []}}", "serverBedrockWindows"));
        assertTrue(shape.getMessage().contains("unknown shape") && shape.getMessage().contains("downloads"), shape.getMessage());
    }

    @Test
    void aVersionNamesNothingButABuild() {
        // Resolving a given version reads no network; a version that is no build number never reaches a path
        assertDoesNotThrow(() -> BdsDistribution.resolve(false, false, "1.26.63.1"));
        for (String bad : new String[] {"1.26.63.1/../../x", "1.26.63.1\\..\\x", "..", "1.26.63", "1.26.63.1-beta", ""}) {
            assertThrows(IllegalArgumentException.class, () -> BdsDistribution.resolve(false, false, bad), bad);
        }
    }

    @Test
    void pathsOfABuildStayInTheCache(@TempDir Path cache) throws Exception {
        BdsDistribution windows = BdsDistribution.resolve(false, false, "1.26.63.1");
        assertTrue(windows.runDirectory(cache, "spec-").startsWith(cache.resolve("runs")));
        assertTrue(windows.server(cache).startsWith(cache.resolve("servers")));
        assertTrue(windows.zipFile(cache).startsWith(cache.resolve("zips")));
        // A record built around the check still cannot leave the directory
        BdsDistribution forged = new BdsDistribution("..\\..\\x", false, "win", null);
        assertThrows(IllegalArgumentException.class, () -> forged.server(cache));
        assertThrows(IllegalArgumentException.class, () -> BdsDistribution.inside(cache, ".."));
        assertThrows(IllegalArgumentException.class, () -> BdsDistribution.inside(cache, "a/../../b"));
        assertThrows(IllegalArgumentException.class, () -> BdsDistribution.inside(cache, "a\\b"));
        assertThrows(IllegalArgumentException.class, () -> BdsDistribution.inside(cache, "."));
        assertEquals(cache.toAbsolutePath().normalize().resolve("1.26.63.1"), BdsDistribution.inside(cache, "1.26.63.1"));
    }

    @Test
    void deletingNeverEntersALink(@TempDir Path directory) throws IOException {
        Path outside = Files.createDirectories(directory.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "kept");
        Path tree = Files.createDirectories(directory.resolve("tree/sub"));
        Files.writeString(tree.resolve("a.txt"), "a");
        try {
            Files.createSymbolicLink(tree.resolve("link"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            // Creating links needs a privilege on Windows; the walk is the same either way
            org.junit.jupiter.api.Assumptions.abort("no symbolic links here: " + e);
        }
        BdsDistribution.deleteRecursively(directory.resolve("tree"));
        assertFalse(Files.exists(directory.resolve("tree")));
        assertEquals("kept", Files.readString(outside.resolve("keep.txt")));
    }
}
