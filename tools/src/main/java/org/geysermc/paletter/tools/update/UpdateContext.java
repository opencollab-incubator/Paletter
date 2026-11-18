package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.bds.BdsDistribution;
import org.geysermc.paletter.tools.data.Importer;
import org.geysermc.paletter.tools.Layout;
import org.geysermc.paletter.tools.Versions;
import org.geysermc.paletter.tools.client.Capture;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * What a run of {@link Update} shares between its stages: where it reads and writes, its options, the report and what it
 * would write ({@link #stage}), which is compared with the repository or copied into it at the end.
 */
final class UpdateContext {
    final Path root;
    final Path resources;
    final Path bdsData;
    final Map<String, String> options;
    final boolean preview;
    final boolean verifyOnly;
    final boolean keepServer;
    final int port;
    final int timeout;
    /**
     * {@code --against <build>}: a history check of an earlier or later build ({@link BuildHistory}). Its captures are
     * compared with the committed captures of that build instead of the newest ones, and only {@code data/bds/<this
     * build>} is written (when anything differs); bundled data, constants, the content log and the model stay as they are.
     */
    final String against;
    /**
     * {@code --capture-only <dir>}: the capture half of a full recapture (see docs/data-pipeline.md, "Full recapture"). The
     * run captures and compares as usual, but writes nothing into the repository: the files that would go to
     * {@code data/bds/<build>} (and the spec capture) go to this directory, with the same relative paths, and the rest of
     * the update (bundled data, constants, the model, white-box, the build table) is left to the job that gathers the
     * captures of every build. Works for releases and previews, with or without {@code --against}.
     */
    final Path captureOnly;
    /** In a history check: what this run would write into {@code data/bds/<build>}, written only when it differs. */
    private final Map<Path, byte[]> pending = new LinkedHashMap<>();
    /** The build this run captures ({@link Update#begin}); set with the report. */
    Path buildDirectory;
    Report report;

    UpdateContext(Path root, Map<String, String> options) {
        // Normalised, so that every path built from it compares with startsWith
        root = root.toAbsolutePath().normalize();
        this.root = root;
        this.resources = root.resolve("paletter/src/main/resources/paletter");
        this.bdsData = root.resolve(Layout.BDS);
        this.options = options;
        String channel = options.getOrDefault("channel", "release");
        if (!channel.equals("release") && !channel.equals("preview")) {
            throw new IllegalArgumentException("--channel must be release or preview");
        }
        this.preview = channel.equals("preview");
        this.verifyOnly = options.containsKey("verify-only");
        this.keepServer = options.containsKey("keep-server");
        this.port = Integer.parseInt(options.getOrDefault("port", "19260"));
        this.timeout = Integer.parseInt(options.getOrDefault("timeout", "300"));
        this.against = options.containsKey("against") ? Versions.requireBuild(options.get("against")) : null;
        if (against != null && preview) {
            throw new IllegalArgumentException("--against checks release builds only");
        }
        this.captureOnly = options.containsKey("capture-only") ? Path.of(options.get("capture-only")).toAbsolutePath().normalize() : null;
        if (captureOnly != null && (verifyOnly || captureOnly.startsWith(bdsData) || bdsData.startsWith(captureOnly))) {
            throw new IllegalArgumentException("--capture-only writes the captures into a directory of its own (not data/bds, not with --verify-only)");
        }
    }

    /**
     * The committed build a capture of {@code build} is compared with: its own when it has one, so an already bundled
     * build is verified against what was committed for it; else the newest older build that has one, so a build not
     * bundled yet shows what changed since (and only then do differences hint at new protocol windows).
     *
     * @param committed the builds with such a capture
     * @return the build, or null if no build qualifies
     */
    static String baseline(String build, Collection<String> committed) {
        if (committed.contains(build)) {
            return build;
        }
        return committed.stream().filter(other -> Versions.compare(other, build) < 0).max(Versions.ORDER).orElse(null);
    }

    /** The builds in {@code data/bds} that have {@code relative}; a release run leaves out the bundled preview's. */
    Set<String> committedBuilds(String relative) throws IOException {
        Set<String> builds = new TreeSet<>();
        if (!Files.isDirectory(bdsData)) {
            return builds;
        }
        Set<String> skip = previewBuilds();
        try (Stream<Path> dirs = Files.list(bdsData)) {
            dirs.filter(dir -> Files.exists(dir.resolve(relative))).map(dir -> dir.getFileName().toString())
                .filter(build -> !skip.contains(build)).forEach(builds::add);
        }
        return builds;
    }

    void stage(Path target, byte[] content, String what) throws IOException {
        if (captureOnly != null) {
            // Only what belongs to the captures of this build; the rest is derived data that the gathering job regenerates
            Path normalized = target.toAbsolutePath().normalize();
            if (normalized.startsWith(buildDirectory) && !normalized.equals(buildDirectory)) {
                pending.put(normalized, content);
            } else {
                System.out.println("Capture only: not staged (" + what + ")");
            }
            return;
        }
        if (against != null) {
            // A history check writes only the captures of its build, and only when the build is kept (keep())
            Path normalized = target.toAbsolutePath().normalize();
            if (!normalized.startsWith(buildDirectory) || normalized.equals(buildDirectory)) {
                throw new IllegalStateException("A history check writes only into " + relative(buildDirectory) + ", not " + what
                    + " to " + normalized);
            }
            pending.put(normalized, content);
            return;
        }
        byte[] existing = Files.exists(target) ? Files.readAllBytes(target) : null;
        if (existing != null && Arrays.equals(existing, content)) {
            return;
        }
        String relative = root.relativize(target).toString().replace('\\', '/');
        report.repoChanges.add((verifyOnly ? "would " : "") + (existing == null ? "add" : "update") + " `" + relative + "` (" + what + ")");
        if (!verifyOnly) {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        }
    }

    void stageCopy(Path target, Path source, String what) throws IOException {
        stage(target, Files.readAllBytes(source), what);
    }

    /**
     * Runs {@code action}, which writes into the repository itself, and adds a repository change for every file under
     * {@code paths} (files or directories) that it added, updated or removed. A file rewritten with the same bytes is no change.
     */
    static <T> T track(Report report, Path root, List<Path> paths, String what, Callable<T> action) throws Exception {
        Map<String, String> before = digests(root, paths);
        T result;
        try {
            result = action.call();
        } catch (Exception | Error e) {
            // Also what a failed action wrote; a failure to read it back never hides the action's own
            try {
                recordChanges(report, root, paths, what, before);
            } catch (IOException | RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        recordChanges(report, root, paths, what, before);
        return result;
    }

    private static void recordChanges(Report report, Path root, List<Path> paths, String what, Map<String, String> before) throws IOException {
        Map<String, String> after = digests(root, paths);
        Set<String> files = new TreeSet<>(before.keySet());
        files.addAll(after.keySet());
        for (String file : files) {
            String old = before.get(file);
            String now = after.get(file);
            if (!Objects.equals(old, now)) {
                report.repoChanges.add((old == null ? "add" : now == null ? "remove" : "update") + " `" + file + "` (" + what + ")");
            }
        }
    }

    /** The SHA-256 of every regular file under {@code paths}, by repository path; a missing path has none. */
    private static Map<String, String> digests(Path root, List<Path> paths) throws IOException {
        Map<String, String> digests = new TreeMap<>();
        for (Path path : paths) {
            if (!Files.exists(path)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(path)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    MessageDigest digest;
                    try {
                        digest = MessageDigest.getInstance("SHA-256");
                    } catch (NoSuchAlgorithmException e) {
                        throw new IllegalStateException(e);
                    }
                    digests.put(root.relativize(file).toString().replace('\\', '/'), HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file))));
                }
            }
        }
        return digests;
    }

    /**
     * A check that compares this build with committed data, or with what BDS itself says elsewhere. In a history check,
     * any difference (any {@link Report.Status#DIFF}) is a change against the build it is checked against: the build is
     * kept.
     */
    void compared(boolean same, String name, String detail) {
        report.check(same ? Report.Status.PASS : Report.Status.DIFF, name, detail);
    }

    /**
     * A history check: a check against the bundled data version of the window rather than the build compared with
     * (an INFO, never a change), listed in the report as not compared. Outside a history check it is a {@link #compared}
     * check.
     *
     * @param instead how the same thing is compared build to build, if it is
     */
    void notCompared(boolean same, String name, String detail, String instead) {
        if (against == null) {
            compared(same, name, detail);
            return;
        }
        report.check(same ? Report.Status.PASS : Report.Status.INFO, name, detail);
        if (!same) {
            report.notCompared.add(name + ": " + detail + (instead == null ? "" : "; " + instead));
        }
    }

    /** Writes the captures of a kept build (history check) into {@code data/bds/<build>}. */
    void keep(Path work) throws IOException {
        // A recapture writes into its own directory and deletes nothing; a history check replaces the build's directory
        Path base = captureOnly != null ? captureOnly : buildDirectory;
        if (captureOnly == null) {
            BdsDistribution.deleteRecursively(buildDirectory);
        }
        for (Map.Entry<Path, byte[]> file : pending.entrySet()) {
            Path target = base.resolve(buildDirectory.relativize(file.getKey()).toString());
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
        }
        for (String capture : List.of("spec", "spec-format", "spec-gating")) {
            Path spec = work.resolve(capture);
            if (Files.isDirectory(spec)) {
                Path target = base.resolve(capture);
                Files.createDirectories(target);
                for (Path file : BuildHistory.files(spec)) {
                    Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        if (captureOnly == null) {
            report.repoChanges.add("add `" + root.relativize(buildDirectory).toString().replace('\\', '/') + "` (captures of this build)");
        }
    }

    /** A path of the repository as the report shows it (forward slashes on every platform). */
    String relative(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    /**
     * The bundled release whose window holds a protocol: the newest one not newer than it (a hotfix with a protocol bump
     * is served the data version of its release), null if every bundled version is newer.
     */
    String containingVersion(int protocol) throws IOException {
        String name = null;
        for (Importer.IndexEntry entry : Importer.readIndex(resources)) {
            if (!entry.preview() && entry.protocol() <= protocol) {
                name = entry.name();
            }
        }
        return name;
    }

    String bundledName(int protocol) throws IOException {
        for (Importer.IndexEntry entry : Importer.readIndex(resources)) {
            if (entry.protocol() == protocol) {
                return entry.name();
            }
        }
        return null;
    }

    /**
     * The builds in {@code data/bds} of the bundled preview (their version name is marked {@code preview} in
     * {@code versions.txt}). A release run does not compare against them; a preview run does (none are skipped).
     */
    Set<String> previewBuilds() throws IOException {
        if (preview || !Files.isDirectory(bdsData)) {
            return Set.of();
        }
        Set<String> names = new TreeSet<>();
        Importer.readIndex(resources).stream().filter(Importer.IndexEntry::preview).forEach(entry -> names.add(entry.name()));
        try (Stream<Path> dirs = Files.list(bdsData)) {
            return dirs.map(d -> d.getFileName().toString()).filter(build -> build.matches("\\d+\\..*") && names.contains(Update.versionName(build)))
                .collect(java.util.stream.Collectors.toSet());
        }
    }

    static byte[] gzip(String text) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    static String readGzipText(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(Files.readAllBytes(file)))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
