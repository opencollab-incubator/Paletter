package dev.opencollab.paletter.tools.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.BuildNumber;
import dev.opencollab.paletter.tools.Layout;
import dev.opencollab.paletter.tools.Versions;
import dev.opencollab.paletter.tools.data.Importer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The builds a full recapture captures (docs/data-pipeline.md, "Full recapture"): every kept release build and the preview
 * with captures in {@code data/bds}, as the matrix of the recapture workflow. Read from the checkout, so a build the
 * workflow captures always has committed captures to compare with.
 */
public final class RecapturePlan {
    /**
     * One leg of the matrix. {@code against} is the build itself for a release kept by a history check (it is recaptured as
     * one), another kept build for a build not checked yet (it is compared with that one), else null.
     */
    public record Leg(String build, boolean preview, String against) {
        public Leg {
            BuildNumber.require(build);
        }
    }

    private RecapturePlan() {
    }

    /** The plan of the checkout alone: {@link #of(Path, BuildHistory.BuildList)} without a list of builds. */
    public static List<Leg> of(Path root) throws IOException {
        return of(root, null);
    }

    /**
     * The kept release builds, oldest first, then the preview (the newest one, if several have captures).
     * <p>
     * With the list of release builds, also the newest build of each line (data version) with a kept build that is newer
     * than that build, not recorded and without captures: such a build would otherwise be served with the data of an older build and never looked at, until someone ran {@code builds
     * check}. Its leg compares it with that kept build ({@code against}); capture-only keeps what it captures, so the
     * build has captures in data/bds afterwards and the next {@code builds check} verifies it like any build with captures
     * the record lacks. Builds the record already has are never planned again.
     *
     * @param list the release builds, null for the kept builds of the checkout only
     */
    public static List<Leg> of(Path root, BuildHistory.BuildList list) throws IOException {
        List<Leg> legs = new ArrayList<>();
        Map<String, BuildHistory.Entry> record = BuildHistory.read(root);
        Map<String, BuildHistory.Entry> kept = BuildHistory.keptView(record, BuildHistory.capturedReleases(root));
        // A frozen build's zip can no longer be downloaded: its captures stay as they are ({@link #frozen} says so)
        kept.forEach((build, entry) -> {
            if (!entry.isFrozen()) {
                legs.add(new Leg(build, false, entry.keptByHistoryCheck() ? build : null));
            }
        });
        if (list != null) {
            legs.addAll(unchecked(root, record, kept, list));
            legs.sort(java.util.Comparator.comparing(Leg::build, Versions.ORDER));
        }
        String preview = null;
        Path bds = root.resolve(Layout.BDS);
        if (Files.isDirectory(bds)) {
            try (Stream<Path> dirs = Files.list(bds)) {
                for (Path capture : dirs.map(dir -> dir.resolve("spec/capture.json")).filter(Files::exists).toList()) {
                    JsonObject meta = JsonParser.parseString(Files.readString(capture)).getAsJsonObject();
                    String build = capture.getParent().getParent().getFileName().toString();
                    if (meta.has("preview") && meta.get("preview").getAsBoolean()
                        && (preview == null || Versions.compare(build, preview) > 0)) {
                        preview = BuildNumber.require(build);
                    }
                }
            }
        }
        if (preview != null) {
            legs.add(new Leg(preview, true, null));
        }
        return legs;
    }

    /**
     * The newest unrecorded build of each line that has a kept build, as a leg against that line's newest kept build. A
     * build belongs to the data version of its line, read from versions.txt: data version {@code 26.60} is the line of the
     * builds {@code 1.26.60.x} to {@code 1.26.69.x}, whatever protocol an unchecked build speaks. A line without a kept
     * build is a new data version, which {@code update} imports.
     */
    private static List<Leg> unchecked(Path root, Map<String, BuildHistory.Entry> record, Map<String, BuildHistory.Entry> kept,
                                       BuildHistory.BuildList list) throws IOException {
        List<Leg> legs = new ArrayList<>();
        for (Importer.IndexEntry entry : Importer.readIndex(BuildTable.resources(root))) {
            if (entry.preview()) {
                continue;
            }
            String newestKept = kept.keySet().stream().filter(build -> inLine(build, entry.name())).reduce((a, b) -> b).orElse(null);
            if (newestKept != null) {
                list.releases().stream()
                    .filter(build -> inLine(build, entry.name()) && Versions.compare(build, newestKept) > 0)
                    .filter(build -> !record.containsKey(build) && !kept.containsKey(build))
                    .reduce((a, b) -> b)
                    .ifPresent(build -> legs.add(new Leg(build, false, newestKept)));
            }
        }
        return legs;
    }

    /**
     * The kept builds a recapture leaves out because they are frozen (their zip can no longer be downloaded), one report line
     * each: their captures stay those of the add-on they were made with.
     */
    public static List<String> frozen(Path root) throws IOException {
        List<String> lines = new ArrayList<>();
        BuildHistory.read(root).forEach((build, entry) -> {
            if (entry.kept() && entry.isFrozen()) {
                lines.add(build + " is frozen (" + entry.frozen() + "): not recaptured, its captures stay those of the add-on they were made with");
            }
        });
        return lines;
    }

    /** Whether a build is of a data version's line ({@link BuildNumber#inLine}); a name that is no build number is of none. */
    static boolean inLine(String build, String dataVersion) {
        return BuildNumber.isBuild(build) && BuildNumber.parse(build).inLine(dataVersion);
    }

    /** The legs as the {@code include} list of a GitHub Actions matrix: {@code build}, {@code channel}, {@code against} ("" for none). */
    public static String matrix(List<Leg> legs) {
        JsonArray include = new JsonArray();
        for (Leg leg : legs) {
            JsonObject entry = new JsonObject();
            entry.addProperty("build", leg.build());
            entry.addProperty("channel", leg.preview() ? "preview" : "release");
            entry.addProperty("against", leg.against() == null ? "" : leg.against());
            include.add(entry);
        }
        JsonObject matrix = new JsonObject();
        matrix.add("include", include);
        return matrix.toString();
    }
}
