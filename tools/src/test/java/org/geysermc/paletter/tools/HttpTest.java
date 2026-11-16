package org.geysermc.paletter.tools;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Against a local server: retries, timeouts, the user agent and checked downloads. */
class HttpTest {
    private static final Http FAST = new Http(Duration.ofSeconds(2), Duration.ofMillis(300), 3, Duration.ofMillis(1));

    /** One answer per request, in order; the last one repeats. */
    @FunctionalInterface
    interface Answer {
        void answer(HttpExchange exchange) throws IOException, InterruptedException;
    }

    private HttpServer server;
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final List<Answer> answers = new ArrayList<>();
    private final List<String> agents = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            int request = requests.getAndIncrement();
            agents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            try {
                answers.get(Math.min(request, answers.size() - 1)).answer(exchange);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        // a stalled answer must not hold up the next request
        server.setExecutor(threads);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        threads.shutdownNow();
    }

    private String url() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/file";
    }

    private static Answer status(int status) {
        return exchange -> exchange.sendResponseHeaders(status, -1);
    }

    private static Answer body(byte[] body) {
        return exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        };
    }

    private static Answer body(String body) {
        return body(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void getsTheBodyWithThePaletterAgent() throws Exception {
        answers.add(body("hello"));
        assertEquals("hello", FAST.get(url()));
        assertEquals(List.of(Http.USER_AGENT), agents);
    }

    @Test
    void aHeaderReplacesTheAgent() throws Exception {
        answers.add(body("hello"));
        FAST.get(url(), Map.of("User-Agent", "Mozilla/5.0 Paletter"));
        assertEquals(List.of("Mozilla/5.0 Paletter"), agents);
    }

    @Test
    void retriesAServerError() throws Exception {
        answers.add(status(503));
        answers.add(body("hello"));
        assertEquals("hello", FAST.get(url()));
        assertEquals(2, requests.get());
    }

    @Test
    void givesUpAfterThreeTries() {
        answers.add(status(500));
        Http.NetworkException e = assertThrows(Http.NetworkException.class, () -> FAST.get(url()));
        assertTrue(e.getMessage().contains("HTTP 500"), e.getMessage());
        assertEquals(3, requests.get());
    }

    @Test
    void doesNotRetryANotFound() {
        answers.add(status(404));
        assertThrows(Http.NetworkException.class, () -> FAST.get(url()));
        assertEquals(1, requests.get());
    }

    @Test
    void aStalledResponseTimesOutAndIsRetried() throws Exception {
        answers.add(exchange -> Thread.sleep(2000));
        answers.add(body("hello"));
        assertEquals("hello", FAST.get(url()));
        assertEquals(2, requests.get());
    }

    @Test
    void aDownloadThatFailsItsCheckIsDownloadedAgain(@TempDir Path directory) throws Exception {
        answers.add(body("broken"));
        answers.add(body("whole"));
        Path file = directory.resolve("file.zip");
        FAST.download(url(), file, Map.of(), part -> {
            if (!Files.readString(part).equals("whole")) {
                throw new IOException("not whole");
            }
        });
        assertEquals("whole", Files.readString(file));
        assertFalse(Files.exists(directory.resolve("file.zip.part")));
        assertEquals(2, requests.get());
    }

    @Test
    void aDownloadThatNeverPassesLeavesNothing(@TempDir Path directory) {
        answers.add(body("broken"));
        Path file = directory.resolve("file.zip");
        assertThrows(Http.NetworkException.class, () -> FAST.download(url(), file, Map.of(), part -> {
            throw new IOException("not whole");
        }));
        assertFalse(Files.exists(file));
        assertFalse(Files.exists(directory.resolve("file.zip.part")));
        assertEquals(3, requests.get());
    }
}
