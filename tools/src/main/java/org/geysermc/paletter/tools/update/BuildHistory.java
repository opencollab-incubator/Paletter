package org.geysermc.paletter.tools.update;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.geysermc.paletter.tools.Http;
import org.geysermc.paletter.tools.Layout;
import org.geysermc.paletter.tools.Versions;
import org.geysermc.paletter.tools.bds.BdsDistribution;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * {@code builds}: every BDS release build, one by one. Paletter keeps the captures of a build only when they differ from
 * the build before it; {@code data/bds/builds.json} records every build checked, so a later run only checks new ones and
 * a wider range is just another run. See docs/data-pipeline.md, "Every release build".
 * <p>
 * The list of builds comes from Bedrock-OSS/BDS-Versions (the download links API only names the newest build); the zips
 * themselves always come from minecraft.net ({@link BdsDistribution}). A build is checked with {@code update --against
 * <previous kept build>} on Windows: it captures everything an update captures and compares it with that build's
 * committed captures, version stamps aside.
 */
public final class BuildHistory {
    /** The list of builds, by platform and channel; used only for the version numbers. */
    public static final String VERSIONS = "https://raw.githubusercontent.com/Bedrock-OSS/BDS-Versions/main/versions.json";
    /** The record of checked builds, in {@code data/bds}. */
    public static final String RECORD = "builds.json";
    /**
     * Top-level keys that only say which build or protocol a capture is of. The format version of the packs a capture
     * loads follows the build ({@code AddonGenerator.formatVersionFor}: 1.26.31 for 1.26.31.1), so it is one as well.
     */
    static final List<String> STAMPS = List.of("_generated", "bdsVersion", "protocol", "formatVersion");
    /** What {@link #withoutFormatVersion} puts in place of a capture's format version. */
    static final String FORMAT_VERSION = "<format version>";

    private BuildHistory() {
    }

    /** One build in the record: its protocol, and either kept (captures in data/bds) or the kept build it equals. */
    public record Entry(int protocol, String identicalTo) {
        public boolean kept() {
            return identicalTo == null;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The list of builds

    /** The Windows release builds from {@code from} to {@code to} (both inclusive, null for no bound), oldest first. */
    public static List<String> releases(String from, String to) throws IOException, InterruptedException {
        return releases(Http.DEFAULT.get(VERSIONS), from, to);
    }

    /** {@link #releases(String, String)} from the text of the list. */
    static List<String> releases(String versionsJson, String from, String to) {
        List<String> builds = new ArrayList<>();
        JsonObject windows = JsonParser.parseString(versionsJson).getAsJsonObject().getAsJsonObject("windows");
        windows.getAsJsonArray("versions").forEach(version -> builds.add(version.getAsString()));
        return builds.stream()
            .filter(build -> from == null || Versions.compare(build, from) >= 0)
            .filter(build -> to == null || Versions.compare(build, to) <= 0 || build.startsWith(to + "."))
            .sorted(Versions.ORDER).toList();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The record

    /** The checked builds, oldest first; builds with captures in data/bds that the record lacks count as kept. */
    public static Map<String, Entry> read(Path root) throws IOException {
        Map<String, Entry> builds = new TreeMap<>(Versions.ORDER);
        Path file = root.resolve(Layout.BDS).resolve(RECORD);
        if (Files.exists(file)) {
            JsonObject entries = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("builds");
            entries.entrySet().forEach(entry -> {
                JsonObject value = entry.getValue().getAsJsonObject();
                builds.put(entry.getKey(), new Entry(value.get("protocol").getAsInt(),
                    value.has("identicalTo") ? value.get("identicalTo").getAsString() : null));
            });
        }
        for (Map.Entry<String, Integer> captured : capturedReleases(root).entrySet()) {
            builds.putIfAbsent(captured.getKey(), new Entry(captured.getValue(), null));
        }
        return builds;
    }

    public static void write(Path root, Map<String, Entry> builds) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("_generated", "Generated by `./gradlew :tools:run --args=\"builds check\"` (tools BuildHistory): every BDS "
            + "release build checked, with its protocol; kept builds have their captures in data/bds/<build>, the others equal the "
            + "kept build named. Do not edit by hand.");
        json.addProperty("list", VERSIONS);
        JsonObject entries = new JsonObject();
        builds.entrySet().stream().sorted(Map.Entry.comparingByKey(Versions.ORDER)).forEach(build -> {
            JsonObject value = new JsonObject();
            value.addProperty("protocol", build.getValue().protocol());
            if (build.getValue().kept()) {
                value.addProperty("kept", true);
            } else {
                value.addProperty("identicalTo", build.getValue().identicalTo());
            }
            entries.add(build.getKey(), value);
        });
        json.add("builds", entries);
        Path file = root.resolve(Layout.BDS).resolve(RECORD);
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json) + "\n", StandardCharsets.UTF_8);
    }

    /**
     * Release builds with captures in data/bds, with the protocol of their spec capture. Previews are left out: they
     * are replaced by the next preview and are no part of the history.
     */
    static Map<String, Integer> capturedReleases(Path root) throws IOException {
        Map<String, Integer> builds = new TreeMap<>(Versions.ORDER);
        Path bds = root.resolve(Layout.BDS);
        if (!Files.isDirectory(bds)) {
            return builds;
        }
        try (Stream<Path> dirs = Files.list(bds)) {
            for (Path capture : dirs.map(dir -> dir.resolve("spec/capture.json")).filter(Files::exists).toList()) {
                JsonObject meta = JsonParser.parseString(Files.readString(capture)).getAsJsonObject();
                if (!(meta.has("preview") && meta.get("preview").getAsBoolean())) {
                    builds.put(capture.getParent().getParent().getFileName().toString(), meta.get("protocol").getAsInt());
                }
            }
        }
        return builds;
    }

    /** The newest kept build older than {@code build}, or null. */
    static String previousKept(Map<String, Entry> builds, String build) {
        String previous = null;
        for (Map.Entry<String, Entry> entry : builds.entrySet()) {
            if (Versions.compare(entry.getKey(), build) < 0 && entry.getValue().kept()) {
                previous = entry.getKey();
            }
        }
        return previous;
    }

    /** The oldest kept build newer than {@code build}, or null. */
    static String nextKept(Map<String, Entry> builds, String build) {
        for (Map.Entry<String, Entry> entry : builds.entrySet()) {
            if (Versions.compare(entry.getKey(), build) > 0 && entry.getValue().kept()) {
                return entry.getKey();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Checking builds

    /**
     * Checks the builds of the list that the record lacks, oldest first, and records each. A build is kept (its captures
     * and report written to data/bds/<build>) when anything differs from the previous kept build, or when there is none.
     * Stops at the first failed run, or when the cache's disk has less than {@code minFreeBytes} left.
     *
     * @param options passed on to {@link Update} ({@code port}, {@code timeout}, {@code cache})
     * @param limit at most this many builds in this run
     * @return the process exit code
     */
    public static int check(Path root, List<String> list, Map<String, String> options, int limit, long minFreeBytes) throws Exception {
        Path cache = options.containsKey("cache") ? Path.of(options.get("cache")) : BdsDistribution.defaultCache();
        Files.createDirectories(cache);
        Map<String, Entry> builds = read(root);
        int checked = 0;
        for (String build : list) {
            if (builds.containsKey(build)) {
                continue;
            }
            if (checked == limit) {
                System.out.println("Stopping after " + limit + " builds (--limit)");
                break;
            }
            long free = Files.getFileStore(cache).getUsableSpace();
            if (free < minFreeBytes) {
                System.out.println("Stopping before " + build + ": " + (free >> 20) + " MB free, less than " + (minFreeBytes >> 20) + " MB");
                return 1;
            }
            String previous = previousKept(builds, build);
            Map<String, String> buildOptions = new LinkedHashMap<>(options);
            buildOptions.put("channel", "release");
            buildOptions.put("version", build);
            // The first build of the record has no earlier one: it is kept, and its report compares it with the next kept build
            String against = previous != null ? previous : nextKept(builds, build);
            if (against == null) {
                throw new IllegalStateException("No kept build in data/bds to compare " + build + " with");
            }
            buildOptions.put("against", against);
            if (previous == null) {
                buildOptions.put("keep", "true");
            }
            System.out.println("== " + build + ": against " + against + (previous == null ? " (first build of the record, kept)" : ""));
            Update update = new Update(root, buildOptions);
            update.run();
            if (update.failed() || update.protocol() == null) {
                System.out.println(build + " FAILED; see its report in " + cache.resolve("runs"));
                return 1;
            }
            boolean kept = previous == null || update.changed();
            builds.put(build, new Entry(update.protocol(), kept ? null : previous));
            write(root, builds);
            cleanUp(cache, build, kept);
            System.out.println(build + " (protocol " + update.protocol() + "): " + (kept ? "kept" : "identical to " + previous));
            checked++;
        }
        return 0;
    }

    /** Removes the run of a build; the Windows zip stays only for a kept build, the Linux zip (inventory) never. */
    static void cleanUp(Path cache, String build, boolean kept) throws IOException {
        BdsDistribution.deleteRecursively(cache.resolve("runs").resolve("win-" + build));
        BdsDistribution.deleteRecursively(cache.resolve("runs").resolve("spec-win-" + build));
        BdsDistribution.deleteRecursively(cache.resolve("servers").resolve("win-" + build));
        Files.deleteIfExists(cache.resolve("zips/linux/bedrock-server-" + build + ".zip"));
        if (!kept) {
            Files.deleteIfExists(cache.resolve("zips/win/bedrock-server-" + build + ".zip"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Comparing captures

    /** The files directly in a directory, by name. */
    static List<Path> files(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).sorted(Comparator.comparing(f -> f.getFileName().toString())).toList();
        }
    }

    static boolean sameData(Path a, Path b) throws IOException {
        return sameData(Files.readAllBytes(a), Files.readAllBytes(b));
    }

    /**
     * {@link #sameData(byte[], byte[])} of two captures whose packs were written in the given format versions (null:
     * none): the format version, quoted in the pack files and in BDS's warnings about them, is a version stamp too.
     */
    static boolean sameData(Path a, String formatA, Path b, String formatB) throws IOException {
        return sameData(withoutFormatVersion(gunzip(Files.readAllBytes(a)), formatA), withoutFormatVersion(gunzip(Files.readAllBytes(b)), formatB));
    }

    /** The text with the quoted format version ({@code "1.26.30"} or {@code '1.26.30'}) replaced by {@link #FORMAT_VERSION}. */
    public static String withoutFormatVersion(String text, String formatVersion) {
        if (formatVersion == null) {
            return text;
        }
        return text.replace("\"" + formatVersion + "\"", "\"" + FORMAT_VERSION + "\"").replace("'" + formatVersion + "'", "'" + FORMAT_VERSION + "'");
    }

    private static byte[] withoutFormatVersion(byte[] bytes, String formatVersion) {
        return withoutFormatVersion(new String(bytes, StandardCharsets.UTF_8), formatVersion).getBytes(StandardCharsets.UTF_8);
    }

    /** The {@code formatVersion} of a capture's {@code capture.json}, null if it has none. */
    public static String formatVersion(Path captureDirectory) throws IOException {
        Path file = captureDirectory.resolve("capture.json");
        if (!Files.exists(file)) {
            return null;
        }
        JsonObject meta = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        return meta.has("formatVersion") ? meta.get("formatVersion").getAsString() : null;
    }

    /**
     * Whether two captures hold the same data: gzip is unpacked, JSON compared without its version stamps
     * ({@link #STAMPS}), text without line ending differences, anything else byte by byte.
     */
    static boolean sameData(byte[] a, byte[] b) throws IOException {
        byte[] x = gunzip(a);
        byte[] y = gunzip(b);
        String left = new String(x, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String right = new String(y, StandardCharsets.UTF_8).replace("\r\n", "\n");
        JsonElement leftJson = json(left);
        JsonElement rightJson = json(right);
        if (leftJson != null && rightJson != null) {
            return withoutStamps(leftJson).equals(withoutStamps(rightJson));
        }
        return Arrays.equals(x, y) || left.equals(right);
    }

    /** The JSON without {@link #STAMPS}, at any depth: a capture can embed another one (the anchors embed their packet). */
    private static JsonElement withoutStamps(JsonElement json) {
        if (json.isJsonArray()) {
            com.google.gson.JsonArray copy = new com.google.gson.JsonArray();
            json.getAsJsonArray().forEach(element -> copy.add(withoutStamps(element)));
            return copy;
        }
        if (!json.isJsonObject()) {
            return json;
        }
        JsonObject copy = new JsonObject();
        json.getAsJsonObject().entrySet().stream().filter(entry -> !STAMPS.contains(entry.getKey()))
            .forEach(entry -> copy.add(entry.getKey(), withoutStamps(entry.getValue())));
        return copy;
    }

    private static JsonElement json(String text) {
        String trimmed = text.strip();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return null;
        }
        try {
            return JsonParser.parseString(trimmed);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    private static byte[] gunzip(byte[] bytes) throws IOException {
        if (bytes.length < 2 || (bytes[0] & 0xff) != 0x1f || (bytes[1] & 0xff) != 0x8b) {
            return bytes;
        }
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return in.readAllBytes();
        }
    }
}
