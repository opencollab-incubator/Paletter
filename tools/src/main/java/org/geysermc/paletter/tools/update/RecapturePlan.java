package org.geysermc.paletter.tools.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.Layout;
import org.geysermc.paletter.tools.Versions;

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
    /** One leg of the matrix. {@code against} is a release kept by a history check (it is recaptured as one), else null. */
    public record Leg(String build, boolean preview, String against) {
        public Leg {
            Versions.requireBuild(build);
        }
    }

    private RecapturePlan() {
    }

    /** The kept release builds, oldest first, then the preview (the newest one, if several have captures). */
    public static List<Leg> of(Path root) throws IOException {
        List<Leg> legs = new ArrayList<>();
        Map<String, BuildHistory.Entry> kept = BuildHistory.keptView(BuildHistory.read(root), BuildHistory.capturedReleases(root));
        kept.forEach((build, entry) -> legs.add(new Leg(build, false, entry.keptByHistoryCheck() ? build : null)));
        String preview = null;
        Path bds = root.resolve(Layout.BDS);
        if (Files.isDirectory(bds)) {
            try (Stream<Path> dirs = Files.list(bds)) {
                for (Path capture : dirs.map(dir -> dir.resolve("spec/capture.json")).filter(Files::exists).toList()) {
                    JsonObject meta = JsonParser.parseString(Files.readString(capture)).getAsJsonObject();
                    String build = capture.getParent().getParent().getFileName().toString();
                    if (meta.has("preview") && meta.get("preview").getAsBoolean()
                        && (preview == null || Versions.compare(build, preview) > 0)) {
                        preview = Versions.requireBuild(build);
                    }
                }
            }
        }
        if (preview != null) {
            legs.add(new Leg(preview, true, null));
        }
        return legs;
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
