package dev.opencollab.paletter.tools.update;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.opencollab.paletter.tools.Http;
import dev.opencollab.paletter.tools.Layout;
import dev.opencollab.paletter.tools.Versions;
import dev.opencollab.paletter.tools.bds.BdsDistribution;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * {@code builds}: every BDS release build, one by one. Paletter keeps the captures of one build per stretch of builds
 * with the same data; {@code data/bds/builds.json} records every build checked, so a later run only checks new ones and
 * a wider range is just another run. See docs/data-pipeline.md, "Every release build".
 * <p>
 * The list of builds comes from Bedrock-OSS/BDS-Versions (the download links API only names the newest build); the zips
 * themselves always come from minecraft.net ({@link BdsDistribution}). A build is checked with {@code update --against
 * <kept build>} on Windows: it captures everything an update captures and compares it with that build's committed
 * captures, version stamps aside.
 */
public final class BuildHistory {
    /** The repository of the list of builds. */
    public static final String REPOSITORY = "Bedrock-OSS/BDS-Versions";
    /** The list of builds, by platform and channel; used only for the version numbers. */
    public static final String VERSIONS = "https://raw.githubusercontent.com/" + REPOSITORY + "/main/versions.json";
    /** The newest commit that changed the list. */
    static final String LATEST_COMMIT = "https://api.github.com/repos/" + REPOSITORY + "/commits?path=versions.json&per_page=1";
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    /** The record of checked builds, in {@code data/bds}. */
    public static final String RECORD = "builds.json";
    /**
     * Keys that only say which build or protocol a capture is of. The format version of the packs a capture loads follows
     * the build ({@code AddonGenerator.formatVersionFor}: 1.26.31 for 1.26.31.1), so it is one as well. They are left out
     * at the top level of a capture and of the captures it embeds ({@link #EMBEDDED}), nowhere else.
     */
    static final List<String> STAMPS = List.of("_generated", "bdsVersion", "protocol", "formatVersion");
    /** Top-level keys under which a capture embeds another one, with its own stamps: the voxel shape anchors embed their packet. */
    static final List<String> EMBEDDED = List.of("packet");
    /** What {@link #withoutFormatVersion} puts in place of a capture's format version. */
    static final String FORMAT_VERSION = "<format version>";

    private BuildHistory() {
    }

    /** Where a build number came from: the list of BDS-Versions. */
    public static final String FROM_LIST = "BDS-Versions";
    /** Where a build number came from: the download links API of minecraft.net, which names the current release only. */
    public static final String FROM_LINKS_API = "links API";
    /** How a build was kept: {@code update} captured it, the source of a bundled data version that other files refer to. */
    public static final String KEPT_BY_UPDATE = "update";
    /** How a build was kept: a history check found it different from the build it was compared with; only the record refers to it. */
    public static final String KEPT_BY_HISTORY_CHECK = "history check";

    /**
     * One build in the record: its protocol, and either kept (captures in data/bds) or the kept build it equals, which
     * can be newer than it (the first kept build of its stretch of identical builds).
     *
     * @param source where its build number came from: {@link #FROM_LIST} or {@link #FROM_LINKS_API}
     * @param keptBy for a kept build, {@link #KEPT_BY_UPDATE} or {@link #KEPT_BY_HISTORY_CHECK}; null for an identical one
     * @param notCompared what the run that recorded it could not compare with the other build (it lacked the capture,
     *                    or the check is against the bundled data only), as the report listed it
     */
    public record Entry(int protocol, String identicalTo, String source, String keptBy, List<String> notCompared) {
        public Entry {
            notCompared = notCompared == null ? List.of() : List.copyOf(notCompared);
            if (identicalTo == null && !KEPT_BY_UPDATE.equals(keptBy) && !KEPT_BY_HISTORY_CHECK.equals(keptBy)) {
                throw new IllegalArgumentException("A kept build is kept by " + KEPT_BY_UPDATE + " or " + KEPT_BY_HISTORY_CHECK + ": " + keptBy);
            }
            if (identicalTo != null) {
                keptBy = null;
            }
        }

        /** A build of the list: identical to {@code identicalTo}, or kept by {@code update} when that is null. */
        public Entry(int protocol, String identicalTo) {
            this(protocol, identicalTo, FROM_LIST, identicalTo == null ? KEPT_BY_UPDATE : null, List.of());
        }

        /** A kept build of the list. */
        static Entry kept(int protocol, String keptBy) {
            return new Entry(protocol, null, FROM_LIST, keptBy, List.of());
        }

        public boolean kept() {
            return identicalTo == null;
        }

        /** Whether its captures came from a history check, so that only the record refers to them. */
        public boolean keptByHistoryCheck() {
            return KEPT_BY_HISTORY_CHECK.equals(keptBy);
        }

        Entry identicalTo(String build, List<String> notCompared) {
            return new Entry(protocol, build, source, null, notCompared);
        }

        Entry identicalTo(String build) {
            return identicalTo(build, notCompared);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The list of builds

    /**
     * The Windows release builds of BDS-Versions at one commit of that repository.
     *
     * @param commit the commit the list was read at
     * @param date when that commit was made
     * @param stable the release the list calls current
     * @param releases every release build, oldest first
     * @param fromLinksApi the builds of {@code releases} the list lacks and the links API named ({@link #withCurrentRelease})
     */
    public record BuildList(String commit, String date, String stable, List<String> releases, List<String> fromLinksApi) {
        /**
         * The list with the current release of the download links API (Mojang's own, so authoritative for the newest
         * release) added when the list lacks it and it is newer than every listed build.
         *
         * @param current the current release, null if the API could not be read
         */
        public BuildList withCurrentRelease(String current) {
            if (current == null || releases.contains(current) || (newest() != null && Versions.compare(current, newest()) < 0)) {
                return this;
            }
            List<String> builds = new ArrayList<>(releases);
            builds.add(Versions.requireBuild(current));
            List<String> api = new ArrayList<>(fromLinksApi);
            api.add(current);
            return new BuildList(commit, date, stable, List.copyOf(builds), List.copyOf(api));
        }

        /** Where a build of the list came from: {@link #FROM_LIST} or {@link #FROM_LINKS_API}. */
        public String source(String build) {
            return fromLinksApi.contains(build) ? FROM_LINKS_API : FROM_LIST;
        }

        /** The newest build BDS-Versions lists. */
        String newestListed() {
            return releases.stream().filter(build -> !fromLinksApi.contains(build)).reduce((a, b) -> b).orElse(null);
        }

        /** The builds from {@code from} to {@code to} (both inclusive, null for no bound; a line takes its hotfixes), oldest first. */
        public List<String> range(String from, String to) {
            if (from != null) {
                Versions.requireBound(from);
            }
            if (to != null) {
                Versions.requireBound(to);
            }
            return releases.stream()
                .filter(build -> from == null || Versions.compare(build, from) >= 0)
                .filter(build -> to == null || Versions.compare(build, to) <= 0 || build.startsWith(to + "."))
                .toList();
        }

        public String newest() {
            return releases.isEmpty() ? null : releases.getLast();
        }
    }

    /** The list at the newest commit of BDS-Versions that changed it. */
    public static BuildList fetch() throws IOException, InterruptedException {
        JsonArray commits = JsonParser.parseString(Http.DEFAULT.get(LATEST_COMMIT, Map.of("Accept", "application/vnd.github+json"))).getAsJsonArray();
        if (commits.isEmpty()) {
            throw new IOException("No commit of " + REPOSITORY + " changed versions.json");
        }
        JsonObject latest = commits.get(0).getAsJsonObject();
        String commit = latest.get("sha").getAsString();
        if (!COMMIT.matcher(commit).matches()) {
            throw new IOException("Not a commit of " + REPOSITORY + ": " + commit);
        }
        String date = latest.getAsJsonObject("commit").getAsJsonObject("committer").get("date").getAsString();
        return parse(Http.DEFAULT.get(at(commit)), commit, date);
    }

    /** The list at a commit. */
    static String at(String commit) {
        return "https://raw.githubusercontent.com/" + REPOSITORY + "/" + commit + "/versions.json";
    }

    /**
     * The list from its text. Its build numbers name directories and files, so every one must be a build number
     * ({@link Versions#requireBuild}).
     *
     * @throws IllegalArgumentException when the list names anything else
     */
    static BuildList parse(String versionsJson, String commit, String date) {
        JsonObject windows = JsonParser.parseString(versionsJson).getAsJsonObject().getAsJsonObject("windows");
        List<String> builds = new ArrayList<>();
        for (JsonElement version : windows.getAsJsonArray("versions")) {
            builds.add(listed(version.getAsString()));
        }
        builds.sort(Versions.ORDER);
        String stable = windows.has("stable") ? listed(windows.get("stable").getAsString()) : null;
        return new BuildList(commit, date, stable, List.copyOf(builds), List.of());
    }

    private static String listed(String build) {
        try {
            return Versions.requireBuild(build);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(REPOSITORY + " lists a build that is no build number: `" + build + "`", e);
        }
    }

    /**
     * How the list compares with the current release of minecraft.net, or null when its newest release (and the one
     * it calls stable) is that release: a note when the list lacks it and it is checked from the links API
     * ({@link BuildList#withCurrentRelease}), else a warning.
     */
    static String staleness(BuildList list, String current) {
        if (list.fromLinksApi().contains(current)) {
            return "NOTE: " + current + ", the current release of minecraft.net, is not in " + REPOSITORY + " yet (newest listed "
                + list.newestListed() + ", commit " + list.commit() + "); it is checked with the build number from the links API";
        }
        if (current.equals(list.newest()) && (list.stable() == null || current.equals(list.stable()))) {
            return null;
        }
        return "WARNING: the newest release of " + REPOSITORY + " (" + list.newest() + ", stable " + list.stable() + ", commit "
            + list.commit() + ") is not the current release of minecraft.net (" + current + ")";
    }

    /**
     * The list at the newest commit of BDS-Versions, with the current release of the download links API added when the
     * list lacks it; prints where the list comes from and how it compares with minecraft.net.
     */
    public static BuildList fetchWithCurrentRelease() throws IOException, InterruptedException {
        BuildList list = fetch();
        System.out.println(REPOSITORY + " at " + list.commit() + " (" + list.date() + ")");
        String current;
        try {
            current = BdsDistribution.resolve(false, false, null).version();
        } catch (IOException e) {
            System.out.println("WARNING: could not read the current release from the download links API: " + e);
            return list;
        }
        list = list.withCurrentRelease(current);
        String note = staleness(list, current);
        if (note != null) {
            System.out.println(note);
        }
        return list;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The record

    /** The builds of the record, oldest first. */
    public static Map<String, Entry> read(Path root) throws IOException {
        Map<String, Entry> builds = new TreeMap<>(Versions.ORDER);
        Path file = root.resolve(Layout.BDS).resolve(RECORD);
        if (Files.exists(file)) {
            JsonObject entries = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("builds");
            entries.entrySet().forEach(entry -> {
                JsonObject value = entry.getValue().getAsJsonObject();
                String identicalTo = value.has("identicalTo") ? Versions.requireBuild(value.get("identicalTo").getAsString()) : null;
                List<String> notCompared = new ArrayList<>();
                if (value.has("notCompared")) {
                    value.getAsJsonArray("notCompared").forEach(line -> notCompared.add(line.getAsString()));
                }
                builds.put(Versions.requireBuild(entry.getKey()), new Entry(value.get("protocol").getAsInt(), identicalTo,
                    value.has("source") ? value.get("source").getAsString() : FROM_LIST,
                    identicalTo != null ? null : value.has("keptBy") ? value.get("keptBy").getAsString() : KEPT_BY_UPDATE, notCompared));
            });
        }
        return builds;
    }

    public static void write(Path root, Map<String, Entry> builds, BuildList list) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("_generated", "Generated by `./gradlew :tools:run --args=\"builds check\"` (tools BuildHistory): every BDS "
            + "release build checked, with its protocol. A kept build has its captures in data/bds/<build> (keptBy: update, the "
            + "source of a bundled data version, or history check); any other equals the kept build named (identicalToProtocol: "
            + "that build's protocol, where it differs). notCompared: what its run could not compare with the other build. "
            + "source: where the build number came from, BDS-Versions (listCommit: the list at the latest run) or the download "
            + "links API of minecraft.net. Do not edit by hand.");
        json.addProperty("list", VERSIONS);
        if (list != null) {
            json.addProperty("listCommit", list.commit());
            json.addProperty("listDate", list.date());
        }
        JsonObject entries = new JsonObject();
        for (Map.Entry<String, Entry> build : builds.entrySet().stream().sorted(Map.Entry.comparingByKey(Versions.ORDER)).toList()) {
            Entry entry = build.getValue();
            JsonObject value = new JsonObject();
            value.addProperty("protocol", entry.protocol());
            value.addProperty("source", entry.source());
            if (entry.kept()) {
                value.addProperty("kept", true);
                value.addProperty("keptBy", entry.keptBy());
            } else {
                String target = entry.identicalTo();
                value.addProperty("identicalTo", target);
                // The kept build's protocol: from the record, or from its captures while the record lacks it
                Integer protocol = builds.containsKey(target) ? Integer.valueOf(builds.get(target).protocol())
                    : protocol(BdsDistribution.inside(root.resolve(Layout.BDS), target));
                if (protocol != null && protocol != entry.protocol()) {
                    value.addProperty("identicalToProtocol", protocol);
                }
            }
            if (!entry.notCompared().isEmpty()) {
                JsonArray notCompared = new JsonArray();
                entry.notCompared().forEach(notCompared::add);
                value.add("notCompared", notCompared);
            }
            entries.add(build.getKey(), value);
        }
        json.add("builds", entries);
        writeAtomically(root.resolve(Layout.BDS).resolve(RECORD), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json) + "\n");
    }

    /** Writes a file through a temporary file in its directory and a move, so a crash never leaves half of it. */
    static void writeAtomically(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
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

    /** The protocol of a build's spec capture, null if it has none. */
    static Integer protocol(Path buildDirectory) throws IOException {
        Path file = buildDirectory.resolve("spec/capture.json");
        return Files.exists(file) ? JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("protocol").getAsInt() : null;
    }

    /**
     * Whether a build directory's report was written by a history check ({@code update --against}), read from the
     * generated marker {@link Report#render} puts on its first line. Only used to find captures that a history check
     * kept but the record lacks (a run that stopped between the two); the record says how every recorded build was kept.
     */
    static boolean reportOfHistoryCheck(Path buildDirectory) throws IOException {
        Path report = buildDirectory.resolve(Update.REPORT);
        if (!Files.exists(report)) {
            return false;
        }
        try (Stream<String> lines = Files.lines(report)) {
            String command = Report.generatedCommand(lines.findFirst().orElse(""));
            return command != null && command.matches(".*\\s--against\\s.*");
        }
    }

    /**
     * What is wrong with the record against data/bds, empty if nothing: a kept build without captures, an identical build
     * whose kept build is not kept, captures that a history check kept but the record lacks.
     */
    static List<String> problems(Path root, Map<String, Entry> record, Map<String, Integer> captured) throws IOException {
        List<String> problems = new ArrayList<>();
        record.forEach((build, entry) -> {
            if (entry.kept() && !captured.containsKey(build)) {
                problems.add(build + " is recorded as kept but has no captures in " + Layout.BDS + "/" + build);
            }
            if (!entry.kept()) {
                Entry target = record.get(entry.identicalTo());
                if (!(target != null ? target.kept() : captured.containsKey(entry.identicalTo()))) {
                    problems.add(build + " is recorded as identical to " + entry.identicalTo() + ", which is not a kept build");
                }
            }
        });
        for (String build : captured.keySet()) {
            if (!record.containsKey(build) && reportOfHistoryCheck(BdsDistribution.inside(root.resolve(Layout.BDS), build))) {
                problems.add(Layout.BDS + "/" + build + " holds captures a history check kept, but the record lacks the build: "
                    + "remove the directory and check the build again");
            }
        }
        return problems;
    }

    /** The kept builds: those of the record, and builds with captures that the record lacks (kept by {@code update}). */
    static Map<String, Entry> keptView(Map<String, Entry> record, Map<String, Integer> captured) {
        Map<String, Entry> view = new TreeMap<>(Versions.ORDER);
        record.forEach((build, entry) -> {
            if (entry.kept()) {
                view.put(build, entry);
            }
        });
        captured.forEach((build, protocol) -> view.putIfAbsent(build, Entry.kept(protocol, KEPT_BY_UPDATE)));
        return view;
    }

    /** The newest kept build older than {@code build}, or null. */
    static String previousKept(Map<String, Entry> kept, String build) {
        String previous = null;
        for (Map.Entry<String, Entry> entry : kept.entrySet()) {
            if (Versions.compare(entry.getKey(), build) < 0 && entry.getValue().kept()) {
                previous = entry.getKey();
            }
        }
        return previous;
    }

    /** The oldest kept build newer than {@code build}, or null. */
    static String nextKept(Map<String, Entry> kept, String build) {
        for (Map.Entry<String, Entry> entry : kept.entrySet()) {
            if (Versions.compare(entry.getKey(), build) > 0 && entry.getValue().kept()) {
                return entry.getKey();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Checking builds

    /** What {@link #check} does with a build of the list. */
    enum Kind {
        /** In the record already: skipped. */
        RECORDED,
        /** Has captures, and no kept build is older: recorded as kept without a run. */
        FIRST,
        /** Has captures the record does not know of: {@code update --against <previous kept> --verify-only}, nothing written. */
        VERIFY,
        /** Has no captures: {@code update --against <kept>}, captures written only when they differ. */
        CHECK
    }

    /** @param against the kept build the run compares with, null when there is no run */
    record Step(String build, Kind kind, String against) {
    }

    /**
     * What a run found.
     *
     * @param notCompared what the run could not compare with the other build ({@code Report#notCompared})
     */
    public record Outcome(Integer protocol, boolean failed, boolean changed, List<String> notCompared) {
        public Outcome {
            notCompared = notCompared == null ? List.of() : List.copyOf(notCompared);
        }

        public Outcome(Integer protocol, boolean failed, boolean changed) {
            this(protocol, failed, changed, List.of());
        }
    }

    /** Runs {@code update --against} for {@link #check}. */
    @FunctionalInterface
    public interface Runner {
        Outcome run(String build, String against, boolean verifyOnly) throws Exception;
    }

    /**
     * How a build is checked. A build without captures is compared with the previous kept build, or the next one when
     * nothing older is kept (the first builds of the record). A build with captures (from {@code update}) is compared
     * with the previous kept build, if there is one.
     */
    static Step plan(Map<String, Entry> record, Map<String, Integer> captured, String build) {
        if (record.containsKey(build)) {
            return new Step(build, Kind.RECORDED, null);
        }
        Map<String, Entry> kept = keptView(record, captured);
        String previous = previousKept(kept, build);
        if (captured.containsKey(build)) {
            return previous == null ? new Step(build, Kind.FIRST, null) : new Step(build, Kind.VERIFY, previous);
        }
        String against = previous != null ? previous : nextKept(kept, build);
        if (against == null) {
            throw new IllegalStateException("No kept build in data/bds to compare " + build + " with");
        }
        return new Step(build, Kind.CHECK, against);
    }

    /**
     * Records what a step found. One build keeps its captures per stretch of identical builds: when a build with captures
     * from {@code update} turns out identical to the previous kept build and the record says that one was kept by a
     * history check, the newer build stays kept and the older one, with every build identical to it, is recorded as
     * identical to the newer one.
     *
     * @param source where the build number came from
     * @return the build whose captures that replaced (to be removed from data/bds once the record is written), or null
     */
    static String apply(Map<String, Entry> record, Map<String, Integer> captured, Step step, Outcome outcome, String source) {
        String build = step.build();
        switch (step.kind()) {
            case RECORDED -> {
                return null;
            }
            case FIRST -> {
                record.put(build, new Entry(captured.get(build), null, source, KEPT_BY_UPDATE, List.of()));
                return null;
            }
            case CHECK -> {
                record.put(build, outcome.changed()
                    ? new Entry(outcome.protocol(), null, source, KEPT_BY_HISTORY_CHECK, outcome.notCompared())
                    : new Entry(outcome.protocol(), step.against(), source, null, outcome.notCompared()));
                return null;
            }
            case VERIFY -> {
                record.put(build, new Entry(outcome.protocol(), null, source, KEPT_BY_UPDATE, outcome.notCompared()));
                Entry previous = record.get(step.against());
                if (outcome.changed() || previous == null || !previous.keptByHistoryCheck()) {
                    return null;
                }
                String replaced = step.against();
                record.replaceAll((other, entry) -> replaced.equals(entry.identicalTo()) ? entry.identicalTo(build) : entry);
                record.put(replaced, previous.identicalTo(build, outcome.notCompared()));
                return replaced;
            }
        }
        throw new IllegalStateException(step.kind().toString());
    }

    /** The runner of {@link #check}: {@link Update} with {@code options} ({@code port}, {@code timeout}, {@code cache}). */
    public static Runner updates(Path root, Map<String, String> options) {
        return (build, against, verifyOnly) -> {
            Map<String, String> buildOptions = new LinkedHashMap<>(options);
            buildOptions.put("channel", "release");
            buildOptions.put("version", build);
            buildOptions.put("against", against);
            if (verifyOnly) {
                buildOptions.put("verify-only", "true");
            }
            Update update = new Update(root, buildOptions);
            update.run();
            return new Outcome(update.protocol(), update.failed(), update.changed(), update.notCompared());
        };
    }

    /**
     * Checks the builds that the record lacks, oldest first ({@link #plan}), and records each ({@link #apply}): the record
     * and the build table are written before any replaced captures are removed. Stops at the first failed run, or when
     * the cache's disk has less than {@code minFreeBytes} left.
     *
     * @param list where the builds come from, recorded with them
     * @param builds the builds to check, oldest first
     * @param cache the BDS cache the runs use
     * @param limit at most this many runs
     * @return the process exit code
     */
    public static int check(Path root, BuildList list, List<String> builds, Path cache, Runner runner, int limit, long minFreeBytes) throws Exception {
        Files.createDirectories(cache);
        Map<String, Entry> record = read(root);
        Map<String, Integer> captured = capturedReleases(root);
        List<String> problems = problems(root, record, captured);
        if (!problems.isEmpty()) {
            problems.forEach(problem -> System.out.println("ERROR: " + problem));
            return 1;
        }
        List<String> unlisted = new ArrayList<>(record.keySet());
        captured.keySet().forEach(build -> {
            if (!unlisted.contains(build)) {
                unlisted.add(build);
            }
        });
        unlisted.removeIf(list.releases()::contains);
        if (!unlisted.isEmpty()) {
            System.out.println("NOTE: the list lacks " + unlisted.size() + " recorded or captured builds: " + String.join(", ", unlisted));
        }
        int runs = 0;
        for (String build : builds) {
            Step step = plan(record, captured, build);
            if (step.kind() == Kind.RECORDED) {
                continue;
            }
            if (step.kind() == Kind.FIRST) {
                apply(record, captured, step, null, list.source(build));
                write(root, record, list);
                BuildTable.run(root, false);
                System.out.println(build + " (protocol " + record.get(build).protocol() + "): kept, the oldest kept build of the record");
                continue;
            }
            if (runs == limit) {
                System.out.println("Stopping after " + limit + " builds (--limit)");
                break;
            }
            long free = Files.getFileStore(cache).getUsableSpace();
            if (free < minFreeBytes) {
                System.out.println("Stopping before " + build + ": " + (free >> 20) + " MB free, less than " + (minFreeBytes >> 20) + " MB");
                return 1;
            }
            System.out.println("== " + build + ": against " + step.against()
                + (step.kind() == Kind.VERIFY ? " (its captures are committed: verify only)" : ""));
            BdsDistribution linux = BdsDistribution.resolve(true, false, build);
            boolean linuxZip = Files.exists(linux.zipFile(cache));
            Outcome outcome = runner.run(build, step.against(), step.kind() == Kind.VERIFY);
            if (outcome.failed() || outcome.protocol() == null) {
                System.out.println(build + " FAILED; see its report in " + cache.resolve("runs"));
                return 1;
            }
            String replaced = apply(record, captured, step, outcome, list.source(build));
            if (step.kind() == Kind.CHECK && record.get(build).kept()) {
                captured.put(build, outcome.protocol());
            }
            // The record first: a run that stops before the removal leaves captures the record no longer needs, never a
            // record that names removed captures
            write(root, record, list);
            if (replaced != null) {
                BdsDistribution.deleteRecursively(BdsDistribution.inside(root.resolve(Layout.BDS), replaced));
                captured.remove(replaced);
                Files.deleteIfExists(BdsDistribution.resolve(false, false, replaced).zipFile(cache));
                System.out.println("Removed " + Layout.BDS + "/" + replaced + ": " + build + " has the same data and its captures come from update");
            }
            BuildTable.run(root, false);
            cleanUp(cache, build, record.get(build).kept(), linuxZip);
            Entry entry = record.get(build);
            String target = entry.kept() ? null : entry.identicalTo();
            Integer targetProtocol = target == null ? null : record.containsKey(target) ? Integer.valueOf(record.get(target).protocol()) : captured.get(target);
            System.out.println(build + " (protocol " + entry.protocol() + "): " + (entry.kept() ? "kept (" + entry.keptBy() + ")" : "identical to " + target)
                + (targetProtocol != null && targetProtocol != entry.protocol() ? " (NOTE: protocol " + targetProtocol + " there)" : "")
                + (entry.notCompared().isEmpty() ? "" : "; not compared: " + String.join("; ", entry.notCompared())));
            BuildTable.gaps(root).stream().filter(gap -> gap.startsWith(build + " ")).forEach(gap -> System.out.println("GAP: " + gap));
            runs++;
        }
        return 0;
    }

    /**
     * Removes the run and server of a build, the Linux zip its inventory was read from (unless it was cached before the
     * run) and the Windows zip of a build that is not kept.
     */
    static void cleanUp(Path cache, String build, boolean kept, boolean keepLinuxZip) throws IOException, InterruptedException {
        BdsDistribution windows = BdsDistribution.resolve(false, false, build);
        BdsDistribution.deleteRecursively(windows.runDirectory(cache, ""));
        BdsDistribution.deleteRecursively(windows.runDirectory(cache, "spec-"));
        BdsDistribution.deleteRecursively(windows.server(cache));
        if (!keepLinuxZip) {
            Files.deleteIfExists(BdsDistribution.resolve(true, false, build).zipFile(cache));
        }
        if (!kept) {
            Files.deleteIfExists(windows.zipFile(cache));
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
            return withoutStamps(leftJson, true).equals(withoutStamps(rightJson, true));
        }
        return Arrays.equals(x, y) || left.equals(right);
    }

    /**
     * {@link #sameData(byte[], byte[])} of two experiment records, except that the {@code content.recipes} of a toggle only
     * count when the earlier record has them: records written before the recipe attribution do not, and are not changed
     * builds for it.
     */
    static boolean sameExperimentRecord(byte[] record, byte[] earlier) throws IOException {
        JsonElement now = json(new String(record, StandardCharsets.UTF_8));
        JsonElement before = json(new String(earlier, StandardCharsets.UTF_8));
        if (now != null && before != null && now.isJsonObject() && before.isJsonObject()) {
            JsonObject toggles = now.getAsJsonObject().getAsJsonObject("toggles");
            JsonObject earlierToggles = before.getAsJsonObject().getAsJsonObject("toggles");
            if (toggles != null && earlierToggles != null) {
                toggles.entrySet().forEach(entry -> {
                    JsonObject content = entry.getValue().getAsJsonObject().getAsJsonObject("content");
                    JsonElement other = earlierToggles.get(entry.getKey());
                    JsonObject otherContent = other == null ? null : other.getAsJsonObject().getAsJsonObject("content");
                    if (content != null && otherContent != null && !otherContent.has("recipes")) {
                        content.remove("recipes");
                    }
                });
                record = now.toString().getBytes(StandardCharsets.UTF_8);
            }
        }
        return sameData(record, earlier);
    }

    /**
     * The JSON without {@link #STAMPS}: at the top level of a capture, and of the captures it embeds under
     * {@link #EMBEDDED} keys (top level only). A stamp key anywhere else is data.
     */
    private static JsonElement withoutStamps(JsonElement json, boolean capture) {
        if (!capture || !json.isJsonObject()) {
            return json;
        }
        JsonObject copy = new JsonObject();
        json.getAsJsonObject().entrySet().stream().filter(entry -> !STAMPS.contains(entry.getKey()))
            .forEach(entry -> copy.add(entry.getKey(), EMBEDDED.contains(entry.getKey()) ? withoutStamps(entry.getValue(), true) : entry.getValue()));
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
