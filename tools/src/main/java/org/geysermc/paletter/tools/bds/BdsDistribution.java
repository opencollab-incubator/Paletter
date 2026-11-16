package org.geysermc.paletter.tools.bds;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.Http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * A Bedrock Dedicated Server build: resolved with the official download links API, downloaded once into a cache
 * outside the repository and extracted from there.
 * <p>
 * Cache layout: {@code <cache>/zips/<platform>/bedrock-server-<version>.zip} (kept) and
 * {@code <cache>/servers/<platform>-<version>} (extracted; removed after a run unless kept). Downloading BDS means
 * accepting the Minecraft EULA and privacy policy (https://minecraft.net/eula).
 *
 * @param version the BDS version, e.g. {@code 1.26.63.1} or {@code 1.26.70.23}
 * @param preview whether it is a preview build
 * @param platform {@code win} or {@code linux}, plus {@code -preview}
 * @param url the download url
 */
public record BdsDistribution(String version, boolean preview, String platform, String url) {
    static final String LINKS = "https://net-secondary.web.minecraft-services.net/api/v1.0/download/links";
    /** The zips have been downloaded with a browser-like agent from the start; a plain one is not known to work. */
    private static final Map<String, String> ZIP_HEADERS = Map.of("User-Agent", "Mozilla/5.0 " + Http.USER_AGENT);

    /**
     * @param version a BDS version such as {@code 1.26.51.1}, or {@code null} for the newest one of the channel. Older
     *                builds stay downloadable under the same url scheme, but are not listed anywhere: the exact version
     *                (including the patch) has to be known
     */
    public static BdsDistribution resolve(boolean preview, String version) throws IOException, InterruptedException {
        return resolve(!BdsServer.WINDOWS, preview, version);
    }

    /** @param linux the Linux build (else the Windows one), whatever this OS is */
    public static BdsDistribution resolve(boolean linux, boolean preview, String version) throws IOException, InterruptedException {
        String platform = (linux ? "linux" : "win") + (preview ? "-preview" : "");
        String url = version != null
            ? "https://www.minecraft.net/bedrockdedicatedserver/bin-" + platform + "/bedrock-server-" + version + ".zip"
            : newest(linux, preview);
        String file = url.substring(url.lastIndexOf('/') + 1);
        return new BdsDistribution(file.replace("bedrock-server-", "").replace(".zip", ""), preview, platform, url);
    }

    private static String newest(boolean linux, boolean preview) throws IOException, InterruptedException {
        String type = "serverBedrock" + (preview ? "Preview" : "") + (linux ? "Linux" : "Windows");
        String links = Http.DEFAULT.get(LINKS);
        for (JsonElement link : JsonParser.parseString(links).getAsJsonObject().getAsJsonObject("result").getAsJsonArray("links")) {
            if (link.getAsJsonObject().get("downloadType").getAsString().equals(type)) {
                return link.getAsJsonObject().get("downloadUrl").getAsString();
            }
        }
        throw new IOException("No " + type + " download in " + links);
    }

    /**
     * Downloads the zip into the cache unless a readable one is there. A download only replaces the cached zip once its
     * central directory reads ({@link #checkZip}); a cached zip that does not read is downloaded again.
     */
    public Path zip(Path cache) throws IOException, InterruptedException {
        Path zip = cache.resolve("zips").resolve(platform).resolve("bedrock-server-" + version + ".zip");
        if (Files.exists(zip)) {
            try {
                checkZip(zip);
                return zip;
            } catch (IOException e) {
                System.out.println("The cached " + zip + " does not read (" + e.getMessage() + "), downloading it again");
            }
        }
        Files.createDirectories(zip.getParent());
        System.out.println("Downloading " + url);
        Http.DEFAULT.download(url, zip, ZIP_HEADERS, BdsDistribution::checkZip);
        return zip;
    }

    /** Throws unless the zip's central directory reads and lists at least one entry: a truncated download does not. */
    static void checkZip(Path zip) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            if (file.size() == 0) {
                throw new IOException(zip.getFileName() + " has no entries");
            }
        }
    }

    /**
     * Extracts the server into {@code <cache>/servers/<platform>-<version>}, or reuses an extracted one.
     *
     * @param fresh extract again even if the directory exists
     */
    public Path extract(Path cache, boolean fresh) throws IOException, InterruptedException {
        Path server = server(cache);
        if (!fresh && Files.exists(BdsServer.executable(server))) {
            System.out.println("Using the extracted BDS " + version + " in " + server);
            return server;
        }
        Path zip = zip(cache);
        deleteRecursively(server);
        extract(zip, server);
        return server;
    }

    /** Where {@link #extract(Path, boolean)} puts the server: {@code <cache>/servers/<platform>-<version>}. */
    public Path server(Path cache) {
        return cache.resolve("servers").resolve(platform + "-" + version);
    }

    /** What runs on an extracted server. */
    @FunctionalInterface
    public interface ServerTask {
        void run(Path server) throws IOException, InterruptedException;
    }

    /**
     * Runs the task on the extracted server of this build in the cache. A server that is already there (one kept with
     * {@code --keep-server}) is used and kept; one extracted for the task is removed afterwards.
     */
    public void onServer(Path cache, ServerTask task) throws IOException, InterruptedException {
        boolean extracted = Files.notExists(BdsServer.executable(server(cache)));
        Path server = extract(cache, false);
        try {
            task.run(server);
        } finally {
            if (extracted) {
                try {
                    deleteRecursively(server);
                } catch (IOException e) {
                    System.out.println("Could not remove " + server + ": " + e);
                }
            }
        }
    }

    public static void extract(Path zip, Path server) throws IOException {
        Files.createDirectories(server);
        try (InputStream stream = Files.newInputStream(zip); ZipInputStream input = new ZipInputStream(stream)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path target = server.resolve(entry.getName()).normalize();
                if (!target.startsWith(server)) {
                    throw new IOException("Zip entry outside the target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        if (!BdsServer.WINDOWS) {
            BdsServer.executable(server).toFile().setExecutable(true);
        }
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (Files.notExists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }

    /** The default cache: {@code $PALETTER_BDS_CACHE}, else {@code <gradle user home>/caches/paletter/bds}. */
    public static Path defaultCache() {
        String explicit = System.getenv("PALETTER_BDS_CACHE");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        String gradleHome = System.getenv("GRADLE_USER_HOME");
        Path home = gradleHome != null && !gradleHome.isBlank() ? Path.of(gradleHome)
            : Path.of(System.getProperty("user.home"), ".gradle");
        return home.resolve("caches").resolve("paletter").resolve("bds");
    }
}
