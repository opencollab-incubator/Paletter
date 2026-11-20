package dev.opencollab.paletter.tools.bds;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.Http;
import dev.opencollab.paletter.tools.BuildNumber;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
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
     *                (including the patch) has to be known. It names files and directories of the cache, so it must be four
     *                dotted numbers ({@link BuildNumber}), as must the newest one the links API names
     */
    public static BdsDistribution resolve(boolean preview, String version) throws IOException, InterruptedException {
        return resolve(!BdsServer.WINDOWS, preview, version);
    }

    /** @param linux the Linux build (else the Windows one), whatever this OS is */
    public static BdsDistribution resolve(boolean linux, boolean preview, String version) throws IOException, InterruptedException {
        String platform = (linux ? "linux" : "win") + (preview ? "-preview" : "");
        if (version != null) {
            BuildNumber.require(version);
        }
        String url = version != null
            ? "https://www.minecraft.net/bedrockdedicatedserver/bin-" + platform + "/bedrock-server-" + version + ".zip"
            : newest(linux, preview);
        String file = url.substring(url.lastIndexOf('/') + 1);
        return new BdsDistribution(BuildNumber.require(file.replace("bedrock-server-", "").replace(".zip", "")), preview, platform, url);
    }

    private static String newest(boolean linux, boolean preview) throws IOException, InterruptedException {
        return downloadUrl(Http.DEFAULT.get(LINKS), "serverBedrock" + (preview ? "Preview" : "") + (linux ? "Linux" : "Windows"));
    }

    /**
     * The download url of a type in an answer of the links API ({@code result.links[]} of {@code downloadType} and
     * {@code downloadUrl}). The one parser of that API: the BDS data workflow calls {@code builds resolve}, which uses it.
     *
     * @throws IOException when the answer has another shape or no such type, quoting the answer
     */
    static String downloadUrl(String links, String type) throws IOException {
        try {
            for (JsonElement link : JsonParser.parseString(links).getAsJsonObject().getAsJsonObject("result").getAsJsonArray("links")) {
                if (link.getAsJsonObject().get("downloadType").getAsString().equals(type)) {
                    return link.getAsJsonObject().get("downloadUrl").getAsString();
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("The download links API (" + LINKS + ") answered in an unknown shape (" + e + "): " + abbreviated(links), e);
        }
        throw new IOException("No " + type + " download in the answer of " + LINKS + ": " + abbreviated(links));
    }

    private static String abbreviated(String text) {
        return text.length() > 2000 ? text.substring(0, 2000) + "..." : text;
    }

    /**
     * {@link #zip(Path, String)} checked against the sha256 that the repository's build record has for this build and
     * platform ({@link ZipChecksums#recorded}, read from the working directory), if any.
     */
    public Path zip(Path cache) throws IOException, InterruptedException {
        return zip(cache, ZipChecksums.recorded(Path.of("").toAbsolutePath(), platform, version));
    }

    /**
     * Downloads the zip into the cache unless a readable one with the expected checksum is there. A download only replaces
     * the cached zip once its central directory reads ({@link #checkZip}) and its sha256 is the expected one; a cached zip
     * that does not read, or has another checksum, is downloaded again.
     *
     * @param sha256 the expected sha256, null when none is recorded
     * @throws IOException when the downloaded zip has another sha256 than the expected one
     */
    public Path zip(Path cache, String sha256) throws IOException, InterruptedException {
        Path zip = zipFile(cache);
        if (Files.exists(zip)) {
            try {
                checkZip(zip);
                ZipChecksums.verify(zip, sha256, "The cached " + zip.getFileName());
                return zip;
            } catch (IOException e) {
                System.out.println("The cached " + zip + " is not usable (" + e.getMessage() + "), downloading it again");
            }
        }
        Files.createDirectories(zip.getParent());
        System.out.println("Downloading " + url + (sha256 == null ? " (no recorded sha256 to check it against)" : ""));
        Http.DEFAULT.download(url, zip, ZIP_HEADERS, part -> {
            checkZip(part);
            ZipChecksums.verify(part, sha256, "The download of " + url);
        });
        return zip;
    }

    /** Where {@link #zip(Path)} keeps the zip: {@code <cache>/zips/<platform>/bedrock-server-<version>.zip}. */
    public Path zipFile(Path cache) {
        return inside(cache.resolve("zips").resolve(platform), "bedrock-server-" + version + ".zip");
    }

    /**
     * The directory a run of this build works in: {@code <cache>/runs/<prefix><platform>-<version>}, removed and recreated
     * by the run.
     */
    public Path runDirectory(Path cache, String prefix) {
        return inside(cache.resolve("runs"), prefix + platform + "-" + version);
    }

    /**
     * {@code directory/name}, checked to be inside {@code directory}: a name that the network or the command line gave
     * (a build number) can never reach a path elsewhere, even if a check upstream missed it.
     *
     * @throws IllegalArgumentException when the name leaves the directory or is the directory itself
     */
    public static Path inside(Path directory, String name) {
        Path base = directory.toAbsolutePath().normalize();
        Path path = base.resolve(name).normalize();
        if (!path.startsWith(base) || path.equals(base) || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("`" + name + "` is not a name inside " + base);
        }
        return path;
    }

    /** Throws unless the zip's central directory reads and lists at least one entry: a truncated download does not. */
    public static void checkZip(Path zip) throws IOException {
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
        return serverDirectory(cache, "");
    }

    /** A server directory of this build for a probe of its own: {@code <cache>/servers/<prefix><platform>-<version>}. */
    public Path serverDirectory(Path cache, String prefix) {
        return inside(cache.resolve("servers"), prefix + platform + "-" + version);
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

    /**
     * Deletes a file or a directory with everything in it. A symbolic link, or a junction or other reparse point, is
     * deleted itself: what it points to is never entered.
     */
    public static void deleteRecursively(Path path) throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    Files.delete(directory);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
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
