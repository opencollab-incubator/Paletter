package org.geysermc.paletter.tools;

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

/**
 * The GET requests of the tooling (BDS links and zips, npm, the wiki): with connect and read timeouts, a few tries with
 * backoff, and the user agent {@code Paletter}. A request that fails every try throws {@link NetworkException}.
 *
 * @param connectTimeout how long a connection may take
 * @param readTimeout how long a read may wait for data (the response headers, and any chunk of the body)
 * @param tries how many times a request is tried
 * @param backoff the wait before the second try; it doubles for each try after that
 */
public record Http(Duration connectTimeout, Duration readTimeout, int tries, Duration backoff) {
    public static final String USER_AGENT = "Paletter";
    public static final Http DEFAULT = new Http(Duration.ofSeconds(30), Duration.ofSeconds(60), 3, Duration.ofSeconds(2));

    /** A request that failed every try, or was answered with a status that a retry does not change. */
    public static final class NetworkException extends IOException {
        NetworkException(String message, Throwable cause) {
            super(message, cause);
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
     * deleted and downloaded again; when every try fails, no part is left behind.
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
        IOException last = null;
        for (int attempt = 1; attempt <= tries; attempt++) {
            if (attempt > 1) {
                System.out.println("GET " + url + " failed (" + last.getMessage() + "), try " + attempt + " of " + tries);
                Thread.sleep(backoff.toMillis() << (attempt - 2));
            }
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            try {
                connection.setConnectTimeout((int) connectTimeout.toMillis());
                connection.setReadTimeout((int) readTimeout.toMillis());
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                headers.forEach(connection::setRequestProperty);
                int status = connection.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK) {
                    last = new IOException("HTTP " + status);
                    if (!retried(status)) {
                        break;
                    }
                    continue;
                }
                try (InputStream input = connection.getInputStream()) {
                    return body.read(input);
                }
            } catch (IOException e) {
                last = e;
            } finally {
                connection.disconnect();
            }
        }
        throw new NetworkException("GET " + url + " failed: " + last.getMessage(), last);
    }

    /** Server errors, timeouts and rate limits may pass; anything else (404 above all) is the answer. */
    static boolean retried(int status) {
        return status >= 500 || status == 408 || status == 429;
    }
}
