package dev.opencollab.paletter.tools.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.BuildNumber;
import dev.opencollab.paletter.tools.Layout;
import dev.opencollab.paletter.tools.Versions;

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
     * one), else null.
     */
    public record Leg(String build, boolean preview, String against) {
        public Leg {
            BuildNumber.require(build);
            if (against != null && !against.equals(build)) {
                throw new IllegalArgumentException("A recapture leg is compared with its own build only, not " + against);
            }
        }
    }

    private RecapturePlan() {
    }

    /**
     * The kept release builds, oldest first, then the preview (the newest one, if several have captures). Read from the
     * checkout alone, without the network: a build without committed captures is never planned (checking a new build is
     * the daily workflow's or {@code builds check}'s work).
     */
    public static List<Leg> of(Path root) throws IOException {
        List<Leg> legs = new ArrayList<>();
        Map<String, BuildHistory.Entry> record = BuildHistory.read(root);
        Map<String, BuildHistory.Entry> kept = BuildHistory.keptView(record, BuildHistory.capturedReleases(root));
        // A frozen build's zip can no longer be downloaded: its captures stay as they are ({@link #frozen} says so)
        kept.forEach((build, entry) -> {
            if (!entry.isFrozen()) {
                legs.add(new Leg(build, false, entry.keptByHistoryCheck() ? build : null));
            }
        });
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
     * The legs of the builds named, in the order of the plan (a rehearsal of a few legs); all of them when none is named.
     *
     * @throws IllegalArgumentException when a build named is not a leg of the plan
     */
    public static List<Leg> only(List<Leg> legs, List<String> builds) {
        if (builds == null || builds.isEmpty()) {
            return legs;
        }
        List<String> planned = legs.stream().map(Leg::build).toList();
        for (String build : builds) {
            if (!planned.contains(build)) {
                throw new IllegalArgumentException(build + " is not a build of the recapture plan (" + String.join(", ", planned) + ")");
            }
        }
        return legs.stream().filter(leg -> builds.contains(leg.build())).toList();
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
