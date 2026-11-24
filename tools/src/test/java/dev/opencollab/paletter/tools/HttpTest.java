package dev.opencollab.paletter.tools;

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
import java.net.ServerSocket;
import java.net.Socket;
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

/** Against a local server: retries, timeouts, the deadline, cut bodies, the user agent and checked downloads. */
class HttpTest {
    private static final Http FAST = new Http(Duration.ofSeconds(2), Duration.ofMillis(300), 3, Duration.ofMillis(1), Duration.ofSeconds(10));

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

    /** The agent names the project, never a person or an address. */
    @Test
    void theAgentNamesTheProjectOnly() {
        assertTrue(Http.USER_AGENT.startsWith("Paletter-tools/"), Http.USER_AGENT);
        assertTrue(Http.USER_AGENT.contains(Http.PROJECT_URL), Http.USER_AGENT);
        assertFalse(Http.USER_AGENT.contains("@"), Http.USER_AGENT);
        String user = System.getProperty("user.name");
        assertFalse(user != null && !user.isBlank() && Http.USER_AGENT.toLowerCase().contains(user.toLowerCase()), Http.USER_AGENT);
    }

    /** The policy of {@link Http#MINECRAFT}, with the waits counted instead of spent. */
    private static Http minecraft(List<Long> waits) {
        return new Http(Duration.ofSeconds(2), Duration.ofMillis(300), Http.MINECRAFT.tries(), Http.MINECRAFT.backoff(), Duration.ofMinutes(30),
            Http.MINECRAFT.retryAfterLimit(), waits::add);
    }

    private static Answer refused(int status, String retryAfter) {
        return exchange -> {
            if (retryAfter != null) {
                exchange.getResponseHeaders().add("Retry-After", retryAfter);
            }
            exchange.sendResponseHeaders(status, -1);
        };
    }

    @Test
    void aForbiddenIsAskedOnce() {
        answers.add(status(403));
        List<Long> waits = new ArrayList<>();
        Http.NetworkException e = assertThrows(Http.NetworkException.class, () -> minecraft(waits).get(url()));
        assertEquals(403, e.status());
        assertEquals(1, requests.get());
        assertEquals(List.of(), waits);
    }

    @Test
    void aRateLimitWaitsItsRetryAfterOnce() throws Exception {
        answers.add(refused(429, "1"));
        answers.add(body("hello"));
        List<Long> waits = new ArrayList<>();
        assertEquals("hello", minecraft(waits).get(url()));
        assertEquals(2, requests.get());
        assertEquals(List.of(1000L), waits);
    }

    @Test
    void aRateLimitWithoutRetryAfterWaitsAMinuteOnceAndThenFails() {
        answers.add(refused(429, null));
        List<Long> waits = new ArrayList<>();
        Http.NetworkException e = assertThrows(Http.NetworkException.class, () -> minecraft(waits).get(url()));
        assertEquals(429, e.status());
        assertEquals(2, requests.get(), "two tries at most");
        assertEquals(List.of(60_000L), waits);
    }

    @Test
    void aRetryAfterBeyondFiveMinutesWaitsTheFixedTime() {
        answers.add(refused(503, "3600"));
        List<Long> waits = new ArrayList<>();
        assertThrows(Http.NetworkException.class, () -> minecraft(waits).get(url()));
        assertEquals(List.of(60_000L), waits);
        assertEquals(-1, Http.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT", Duration.ofMinutes(5)));
        assertEquals(300_000, Http.retryAfter("300", Duration.ofMinutes(5)));
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
        Http.NetworkException e = assertThrows(Http.NetworkException.class, () -> FAST.get(url()));
        assertEquals(404, e.status());
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
    void aBodyThatStallsAfterTheHeadersTimesOutAndIsRetried() throws Exception {
        answers.add(exchange -> {
            exchange.sendResponseHeaders(200, 10);
            OutputStream out = exchange.getResponseBody();
            out.write("hello".getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(2000);
        });
        answers.add(body("hello"));
        assertEquals("hello", FAST.get(url()));
        assertEquals(2, requests.get());
    }

    @Test
    void aBodyThatStallsEveryTryIsANetworkFailure() {
        answers.add(exchange -> {
            exchange.sendResponseHeaders(200, 10);
            OutputStream out = exchange.getResponseBody();
            out.write("hello".getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(2000);
        });
        Http.NetworkException e = assertThrows(Http.NetworkException.class, () -> FAST.get(url()));
        assertEquals(0, e.status());
        assertEquals(3, requests.get());
    }

    @Test
    void aBodyShorterThanItsLengthFailsAndIsRetried() throws Exception {
        // A raw server: the HTTP server of the JDK cannot cut a body short
        try (ServerSocket raw = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            AtomicInteger connections = new AtomicInteger();
            threads.submit(() -> {
                while (!raw.isClosed()) {
                    try (Socket socket = raw.accept()) {
                        socket.getInputStream().read(new byte[4096]);
                        String answer = connections.getAndIncrement() == 0
                            ? "HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n\r\nhello"
                            : "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nwhole";
                        socket.getOutputStream().write(answer.getBytes(StandardCharsets.US_ASCII));
                    }
                }
                return null;
            });
            assertEquals("whole", FAST.get("http://" + raw.getInetAddress().getHostAddress() + ":" + raw.getLocalPort() + "/file"));
            assertEquals(2, connections.get());
        }
    }

    @Test
    void aBodyThatTricklesInEndsAtTheDeadline() {
        answers.add(exchange -> {
            exchange.sendResponseHeaders(200, 100);
            OutputStream out = exchange.getResponseBody();
            for (int i = 0; i < 100; i++) {
                // Each byte well within the read timeout, the whole body far beyond the deadline
                out.write('x');
                out.flush();
                Thread.sleep(100);
            }
        });
        Http slow = new Http(Duration.ofSeconds(2), Duration.ofMillis(300), 3, Duration.ofMillis(1), Duration.ofSeconds(1));
        long start = System.nanoTime();
        Http.NetworkException e = assertThrows(Http.NetworkException.class, () -> slow.get(url()));
        assertTrue(e.getMessage().contains("deadline"), e.getMessage());
        assertEquals(0, e.status());
        assertEquals(1, requests.get());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0);
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
    void aDownloadThatNeverPassesIsDownloadedOnceMoreAndLeavesNothing(@TempDir Path directory) {
        answers.add(body("broken"));
        Path file = directory.resolve("file.zip");
        // A local error, not the network: its own exception, after one more download
        IOException e = assertThrows(IOException.class, () -> FAST.download(url(), file, Map.of(), part -> {
            throw new IOException("not whole");
        }));
        assertEquals("not whole", e.getMessage());
        assertFalse(Files.exists(file));
        assertFalse(Files.exists(directory.resolve("file.zip.part")));
        assertEquals(2, requests.get());
    }
}
