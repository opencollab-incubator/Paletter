package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.tools.bds.BdsRun;

import java.nio.file.Path;
import java.util.List;

/**
 * What a run of {@link Update} is asked to do, as the {@code update} command line gives it.
 *
 * @param bds the build and its server
 * @param report where the report goes, or null for the default place
 * @param dataVersion the name of the bundled data version a new protocol is imported as, or null for the build's line
 * @param experiments the toggles of the spec add-on's world, or null for the ones recorded for the build
 * @param attribution which experiment captures attribute content to toggles
 * @param against a history check: the committed build this one is compared with ({@link BuildHistory}), or null
 * @param captureOnly the directory of a recapture leg that receives the captures instead of the repository, or null
 * @param verifyOnly whether nothing is written
 */
public record UpdateOptions(BdsRun bds, Path report, String dataVersion, List<String> experiments, Attribution attribution, String against,
                            Path captureOnly, boolean verifyOnly) {
    /** Which experiment captures attribute content to toggles. */
    public enum Attribution {
        /** One capture per toggle, and one with all of them (too many toggles: only all together). */
        EACH,
        /** One capture with all toggles. */
        ALL,
        /** None. */
        NONE;

        public String text() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** The defaults of an update with {@code bds}. */
    public static UpdateOptions of(BdsRun bds) {
        return new UpdateOptions(bds, null, null, null, Attribution.EACH, null, null, false);
    }

    /** The defaults of a release update of the newest build, as the command line gives them. */
    public static UpdateOptions release() {
        return of(new BdsRun(null, false, null, 19260, 300, false));
    }

    public UpdateOptions withAgainst(String build) {
        return new UpdateOptions(bds, report, dataVersion, experiments, attribution, build, captureOnly, verifyOnly);
    }

    public UpdateOptions withCaptureOnly(Path directory) {
        return new UpdateOptions(bds, report, dataVersion, experiments, attribution, against, directory, verifyOnly);
    }

    public UpdateOptions withVerifyOnly(boolean verify) {
        return new UpdateOptions(bds, report, dataVersion, experiments, attribution, against, captureOnly, verify);
    }

    public UpdateOptions withBds(BdsRun other) {
        return new UpdateOptions(other, report, dataVersion, experiments, attribution, against, captureOnly, verifyOnly);
    }
}
