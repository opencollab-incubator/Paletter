package dev.opencollab.paletter.tools;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The GET requests of the tooling (BDS links and zips, npm, the wiki): with connect and read timeouts, an overall deadline,
 * a few tries with backoff, and the user agent {@link #USER_AGENT}, which names the project and no person.
 * <p>
 * A request the network failed every try (no connection, a timeout, a body shorter than its {@code Content-Length}, the
 * deadline), or that was answered with a status a retry does not change, throws {@link NetworkException}. A body that
 * arrived whole but that the reader or the check failed on is a local error: it is downloaded once more at most, and then
 * its exception is thrown as it is. A 429 or 503 with a {@code Retry-After} of at most {@code retryAfterLimit} seconds
 * waits that long before the next try; without one, or with a longer one, the backoff.
 *
 * @param connectTimeout how long a connection may take
 * @param readTimeout how long a read may wait for data (the response headers, and any chunk of the body)
 * @param tries how many times a request is tried
 * @param backoff the wait before the second try; it doubles for each try after that
 * @param deadline how long a request may take in all, every try and wait included. It is checked before each try and each
 *     read of the body, so a body that trickles in under the read timeout ends at most one read timeout after it
 * @param retryAfterLimit the longest {@code Retry-After} that is honoured
 * @param sleeper how a wait between tries is spent (the tests count the waits instead)
 */
public record Http(Duration connectTimeout, Duration readTimeout, int tries, Duration backoff, Duration deadline, Duration retryAfterLimit,
                   Sleeper sleeper) {
    /** The project's home, named in the user agent. */
    public static final String PROJECT_URL = "https://github.com/opencollab-incubator/Paletter";
    /** The product token of the user agent: the tools and their version (from the jar's manifest; {@code dev} when run from classes). */
    public static final String PRODUCT = "Paletter-tools/" + java.util.Objects.requireNonNullElse(Http.class.getPackage().getImplementationVersion(), "dev");
    public static final String USER_AGENT = PRODUCT + " (+" + PROJECT_URL + ")";
    public static final Http DEFAULT = new Http(Duration.ofSeconds(30), Duration.ofSeconds(60), 3, Duration.ofSeconds(2),
        Duration.ofMinutes(15));
    /**
     * Mojang's servers (the download links API and the BDS zips on minecraft.net): two tries at most, a minute apart (or the
     * {@code Retry-After} of a 429 or 503, up to five minutes). A 403 or 404 is final. Nothing loops or polls.
     */
    public static final Http MINECRAFT = new Http(Duration.ofSeconds(30), Duration.ofSeconds(60), 2, Duration.ofSeconds(60),
        Duration.ofMinutes(15));

    /** How a wait between tries is spent. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** {@code Retry-After} honoured up to five minutes; the waits are real. */
    public Http(Duration connectTimeout, Duration readTimeout, int tries, Duration backoff, Duration deadline) {
        this(connectTimeout, readTimeout, tries, backoff, deadline, Duration.ofMinutes(5), Thread::sleep);
    }

    /** A request the network failed every try, or that was answered with a status that a retry does not change. */
    public static final class NetworkException extends IOException {
        private final int status;

        NetworkException(String message, int status, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        /** The HTTP status that answered the last try, or 0 when no answer came (no connection, a timeout, a cut body). */
        public int status() {
            return status;
        }
    }

    /** Reads a response body; an exception from it fails the try. */
    @FunctionalInterface
    public interface Body<T> {
        T read(InputStream body) throws IOException;
    }

    /** Checks a downloaded file; an exception from it fails the try. */
    @FunctionalInterface
    public interface Check {
        void check(Path file) throws IOException;
    }

    /** The body of a GET as UTF-8 text. */
    public String get(String url) throws IOException, InterruptedException {
        return get(url, Map.of());
    }

    /** @param headers extra request headers; a {@code User-Agent} here replaces {@link #USER_AGENT} */
    public String get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        return request(url, headers, body -> new String(body.readAllBytes(), StandardCharsets.UTF_8));
    }

    /**
     * Downloads into {@code file.part}, checks it and only then moves it to {@code file}. A part that fails the check is
     * deleted and downloaded once more; when every try fails, no part is left behind.
     */
    public void download(String url, Path file, Map<String, String> headers, Check check) throws IOException, InterruptedException {
        Path part = file.resolveSibling(file.getFileName() + ".part");
        try {
            request(url, headers, body -> {
                Files.copy(body, part, StandardCopyOption.REPLACE_EXISTING);
                check.check(part);
                return null;
            });
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    public <T> T request(String url, Map<String, String> headers, Body<T> body) throws IOException, InterruptedException {
        long end = System.nanoTime() + deadline.toNanos();
        IOException last = null;
        int status = 0;
        boolean local = false;
        int localFailures = 0;
        long retryAfter = -1;
        for (int attempt = 1; attempt <= tries; attempt++) {
            if (attempt > 1) {
                long wait = retryAfter >= 0 ? retryAfter : backoff.toMillis() << (attempt - 2);
                if (System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(wait) >= end) {
                    if (!local) {
                        last = new IOException("the deadline of " + deadline + " passed (" + last.getMessage() + ")", last);
                    }
                    break;
                }
                System.out.println("GET " + url + " failed (" + last.getMessage() + "), try " + attempt + " of " + tries
                    + " in " + Duration.ofMillis(wait).toSeconds() + " s");
                sleeper.sleep(wait);
            }
            retryAfter = -1;
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            Received received = null;
            try {
                connection.setConnectTimeout((int) connectTimeout.toMillis());
                connection.setReadTimeout((int) readTimeout.toMillis());
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                headers.forEach(connection::setRequestProperty);
                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    last = new IOException("HTTP " + code);
                    status = code;
                    local = false;
                    if (!retried(code)) {
                        break;
                    }
                    if (code == 429 || code == HttpURLConnection.HTTP_UNAVAILABLE) {
                        retryAfter = retryAfter(connection.getHeaderField("Retry-After"), retryAfterLimit);
                    }
                    continue;
                }
                received = new Received(connection.getInputStream(), connection.getContentLengthLong(), end, deadline);
                try (InputStream input = received) {
                    return body.read(input);
                }
            } catch (IOException e) {
                status = 0;
                last = e;
                if (received != null && received.expired) {
                    local = false;
                    break;
                }
                // The body arrived as sent and the reader or the check failed on it: one more download at most
                local = received != null && !received.failed;
                if (local && ++localFailures > 1) {
                    break;
                }
            } finally {
                connection.disconnect();
            }
        }
        if (local) {
            throw last;
        }
        throw new NetworkException("GET " + url + " failed: " + last.getMessage(), status, last);
    }

    /** Server errors, timeouts and rate limits may pass; anything else (403 and 404 above all) is the answer. */
    public static boolean retried(int status) {
        return status >= 500 || status == 408 || status == 429;
    }

    /**
     * The wait a {@code Retry-After} header asks for in milliseconds, or -1 when there is none, it is no number of seconds
     * (an HTTP date is not read), or it is longer than {@code limit}: then the backoff applies.
     */
    static long retryAfter(String header, Duration limit) {
        if (header == null || !header.strip().matches("[0-9]{1,9}")) {
            return -1;
        }
        long seconds = Long.parseLong(header.strip());
        return seconds <= limit.toSeconds() ? TimeUnit.SECONDS.toMillis(seconds) : -1;
    }

    /**
     * The body as the network gives it: remembers whether the network failed a read, and fails a body that ends before its
     * {@code Content-Length} (a cut connection does not always fail the connection's own stream) or a read after the deadline.
     */
    private static final class Received extends InputStream {
        private final InputStream in;
        private final long length;
        private final long end;
        private final Duration deadline;
        private long count;
        /** Whether a read failed in the network, not in the reader. */
        boolean failed;
        /** Whether the deadline passed while reading. */
        boolean expired;

        Received(InputStream in, long length, long end, Duration deadline) {
            this.in = in;
            this.length = length;
            this.end = end;
            this.deadline = deadline;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int size) throws IOException {
            if (System.nanoTime() - end > 0) {
                failed = true;
                expired = true;
                throw new IOException("the deadline of " + deadline + " passed after " + count + " bytes of the body");
            }
            int n;
            try {
                n = in.read(buffer, offset, size);
            } catch (IOException e) {
                failed = true;
                throw e;
            }
            if (n < 0 && length >= 0 && count < length) {
                failed = true;
                throw new IOException("the body ended after " + count + " of " + length + " bytes");
            }
            if (n > 0) {
                count += n;
            }
            return n;
        }

        @Override
        public int available() throws IOException {
            return in.available();
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
