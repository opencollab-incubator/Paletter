package org.geysermc.paletter.generator;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Runs the Paletter addon on a Bedrock Dedicated Server and captures its console output.
 * <ol>
 *     <li>optionally downloads the newest BDS for this OS from the official download links API;</li>
 *     <li>installs {@code bds-addon/} as a behavior pack on a fresh flat world;</li>
 *     <li>starts BDS headless, copies its output to the log file, waits for {@code PALETTER-DONE} and stops it.</li>
 * </ol>
 * Downloading BDS means accepting the Minecraft EULA and privacy policy (https://minecraft.net/eula); BDS itself asks
 * for nothing else. Keep the server directory outside the repository.
 */
final class BdsRunner {
    static final String LINKS = "https://net-secondary.web.minecraft-services.net/api/v1.0/download/links";
    private static final String WORLD = "paletter";
    private static final String PACK_DIRECTORY = "paletter_dumper";
    private static final String PACK_UUID = "6f1d3c5e-7a1b-4c2d-9e8f-0a1b2c3d4e5f";
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    private final Path addon;

    BdsRunner(Path addon) {
        this.addon = addon;
    }

    /**
     * Downloads and extracts BDS into {@code parent/<version>} unless it is already there.
     *
     * @param version a BDS version such as {@code 1.26.51.1}, or {@code null} for the newest one (or newest preview).
     *                Older releases stay downloadable under the same URL scheme, but are not listed anywhere: the
     *                exact version (including the patch) has to be known
     * @return the server directory
     */
    static Path download(Path parent, boolean preview, String version) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        String url = version == null ? newest(client, preview)
            : "https://www.minecraft.net/bedrockdedicatedserver/bin-" + (WINDOWS ? "win" : "linux") + (preview ? "-preview" : "")
                + "/bedrock-server-" + version + ".zip";
        String file = url.substring(url.lastIndexOf('/') + 1);
        version = file.replace("bedrock-server-", "").replace(".zip", "");
        Path server = parent.resolve(version + (preview ? "-preview" : ""));
        if (Files.exists(server.resolve(WINDOWS ? "bedrock_server.exe" : "bedrock_server"))) {
            System.out.println("BDS " + version + " is already in " + server);
            return server;
        }
        System.out.println("Downloading " + url);
        Files.createDirectories(server);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "Mozilla/5.0 Paletter").build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Download failed with HTTP " + response.statusCode());
        }
        try (ZipInputStream zip = new ZipInputStream(response.body())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = server.resolve(entry.getName()).normalize();
                if (!target.startsWith(server)) {
                    throw new IOException("Zip entry outside the target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        if (!WINDOWS) {
            server.resolve("bedrock_server").toFile().setExecutable(true);
        }
        return server;
    }

    private static String newest(HttpClient client, boolean preview) throws IOException, InterruptedException {
        String links = client.send(HttpRequest.newBuilder(URI.create(LINKS)).build(), HttpResponse.BodyHandlers.ofString()).body();
        String type = "serverBedrock" + (preview ? "Preview" : "") + (WINDOWS ? "Windows" : "Linux");
        for (JsonElement link : JsonParser.parseString(links).getAsJsonObject().getAsJsonObject("result").getAsJsonArray("links")) {
            if (link.getAsJsonObject().get("downloadType").getAsString().equals(type)) {
                return link.getAsJsonObject().get("downloadUrl").getAsString();
            }
        }
        throw new IOException("No " + type + " download in " + links);
    }

    void run(Path server, Path log, int timeoutSeconds) throws IOException, InterruptedException {
        installPack(server);
        configure(server);

        List<String> command = new ArrayList<>();
        command.add(server.resolve(WINDOWS ? "bedrock_server.exe" : "bedrock_server").toAbsolutePath().toString());
        ProcessBuilder builder = new ProcessBuilder(command).directory(server.toFile()).redirectErrorStream(true);
        if (!WINDOWS) {
            builder.environment().put("LD_LIBRARY_PATH", ".");
        }
        System.out.println("Starting BDS in " + server + ", log: " + log);
        Process process = builder.start();
        boolean done = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             Writer writer = Files.newBufferedWriter(log, StandardCharsets.UTF_8);
             PrintWriter input = new PrintWriter(process.getOutputStream(), true, StandardCharsets.UTF_8)) {
            Thread watchdog = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
                    process.destroy();
                } catch (InterruptedException ignored) {
                    // finished in time
                }
            });
            String line;
            while ((line = output.readLine()) != null) {
                writer.write(line);
                writer.write('\n');
                writer.flush();
                if (!line.contains("PALETTER ")) {
                    System.out.println("  | " + line);
                }
                if (line.contains("PALETTER-DONE")) {
                    done = true;
                    input.println("stop");
                } else if (line.contains("PALETTER-FAILED")) {
                    input.println("stop");
                }
            }
            watchdog.interrupt();
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
        if (!done) {
            throw new IOException("BDS exited without PALETTER-DONE (timeout " + timeoutSeconds + "s); see " + log);
        }
        System.out.println("Dump complete: " + log);
    }

    private void installPack(Path server) throws IOException {
        Path pack = server.resolve("behavior_packs").resolve(PACK_DIRECTORY);
        deleteRecursively(pack);
        try (Stream<Path> files = Files.walk(addon)) {
            for (Path file : files.toList()) {
                Path target = pack.resolve(addon.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        String version = JsonParser.parseString(Files.readString(addon.resolve("manifest.json")))
            .getAsJsonObject().getAsJsonObject("header").get("version").toString();

        // A fresh world every time: nothing of a previous run (or a previous BDS version) leaks into the dump
        Path world = server.resolve("worlds").resolve(WORLD);
        deleteRecursively(world);
        Files.createDirectories(world);
        Files.writeString(world.resolve("world_behavior_packs.json"),
            "[{\"pack_id\": \"" + PACK_UUID + "\", \"version\": " + version + "}]\n");
    }

    private static void configure(Path server) throws IOException {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("level-name", WORLD);
        overrides.put("level-type", "FLAT");
        overrides.put("level-seed", "0");
        overrides.put("server-port", "19232");
        overrides.put("server-portv6", "19233");
        overrides.put("enable-lan-visibility", "false");
        overrides.put("online-mode", "false");
        overrides.put("content-log-console-output-enabled", "true");
        overrides.put("content-log-level", "warning");
        overrides.put("allow-list", "false");

        Path properties = server.resolve("server.properties");
        List<String> lines = new ArrayList<>(Files.readAllLines(properties));
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int equals = line.indexOf('=');
            if (equals > 0 && !line.startsWith("#")) {
                String value = overrides.remove(line.substring(0, equals));
                if (value != null) {
                    lines.set(i, line.substring(0, equals + 1) + value);
                }
            }
        }
        overrides.forEach((key, value) -> lines.add(key + "=" + value));
        Files.write(properties, lines);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.notExists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }
}
