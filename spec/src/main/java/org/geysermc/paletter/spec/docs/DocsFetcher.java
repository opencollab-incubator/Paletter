package org.geysermc.paletter.spec.docs;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads the docs pages the parser reads at a pinned commit of MicrosoftDocs/minecraft-creator, into a cache
 * ({@code <gradle user home>/caches/paletter/docs/<commit>/}). Pages come from raw.githubusercontent.com, directory
 * listings from the GitHub contents API (three unauthenticated requests).
 */
public final class DocsFetcher implements DocsParser.DocsSource {
    public static final String REPOSITORY = "MicrosoftDocs/minecraft-creator";
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private final String commit;
    private final Path cache;
    private final DocsParser.DocsSource local;

    public DocsFetcher(String commit, Path cache) {
        this.commit = commit;
        this.cache = cache.resolve(commit);
        this.local = DocsParser.DocsSource.directory(this.cache, commit);
    }

    public static Path defaultCache() {
        String home = System.getenv("GRADLE_USER_HOME");
        return Path.of(home != null ? home : System.getProperty("user.home") + "/.gradle", "caches", "paletter", "docs");
    }

    @Override
    public String commit() {
        return commit;
    }

    @Override
    public Markdown page(String path) throws IOException {
        fetch(path);
        return local.page(path);
    }

    @Override
    public boolean exists(String path) {
        try {
            fetch(path);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public List<String> list(String directory) throws IOException {
        Path listing = cache.resolve(directory).resolve(".listing");
        if (!Files.exists(listing)) {
            String dir = directory.endsWith("/") ? directory.substring(0, directory.length() - 1) : directory;
            String body = get("https://api.github.com/repos/" + REPOSITORY + "/contents/" + dir + "?ref=" + commit);
            List<String> names = new ArrayList<>();
            for (JsonElement entry : JsonParser.parseString(body).getAsJsonArray()) {
                String name = entry.getAsJsonObject().get("name").getAsString();
                if (name.endsWith(".md")) {
                    names.add(name);
                }
            }
            Files.createDirectories(listing.getParent());
            Files.writeString(listing, String.join("\n", names.stream().sorted().toList()) + "\n");
        }
        return Files.readAllLines(listing).stream().filter(l -> !l.isBlank()).map(name -> directory + name).sorted().toList();
    }

    private void fetch(String path) throws IOException {
        Path file = cache.resolve(path);
        if (Files.exists(file)) {
            return;
        }
        String body = get("https://raw.githubusercontent.com/" + REPOSITORY + "/" + commit + "/" + path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }

    private static String get(String url) throws IOException {
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "Paletter").build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
