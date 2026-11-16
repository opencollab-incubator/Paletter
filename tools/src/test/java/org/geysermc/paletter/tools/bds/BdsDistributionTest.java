package org.geysermc.paletter.tools.bds;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
