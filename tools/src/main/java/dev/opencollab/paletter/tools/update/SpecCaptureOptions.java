package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.tools.bds.BdsRun;

import java.nio.file.Path;
import java.util.List;

/**
 * What a {@link SpecCapture} is asked to do. {@code update} builds it directly; {@code spec-capture} from its command line.
 *
 * @param bds the build and its server
 * @param mode which add-on is loaded
 * @param out where the capture goes, or null for {@code data/bds/<build>/<mode directory>}
 * @param vanillaPack a pack with vanilla block and item files, a source of keys for {@link Mode#KEYS}, or null
 * @param experiments the toggles of the world, or null for the ones recorded for the build
 * @param protocol the protocol to capture with when the server status carries none, or 0
 * @param reuseServer whether the server is the one the run before extracted (it is removed after this run all the same)
 * @param withGating whether a capture of {@link Mode#GATING} follows on the same server
 * @param withFormatSweep whether a capture of {@link Mode#FORMAT_SWEEP} follows on the same server
 */
public record SpecCaptureOptions(BdsRun bds, Mode mode, Path out, Path vanillaPack, List<String> experiments, int protocol,
                                 boolean reuseServer, boolean withGating, boolean withFormatSweep) {
    /** The add-on a capture loads, which says what it records. */
    public enum Mode {
        /** Every documented component field, varied. */
        SPEC("spec"),
        /** Keys the docs do not list, asked of BDS. */
        KEYS("spec-keys"),
        /** The components at the format versions around their boundaries. */
        FORMAT_SWEEP("spec-format"),
        /** The experiment-gated components, in a world without experiments. */
        GATING("spec-gating");

        private final String directory;

        Mode(String directory) {
            this.directory = directory;
        }

        /** The directory of a build's captures of this mode. */
        public String directory() {
            return directory;
        }
    }

    public static SpecCaptureOptions of(BdsRun bds, Mode mode) {
        return new SpecCaptureOptions(bds, mode, null, null, null, 0, false, false, false);
    }

    /** The follow-up capture of {@code mode} on the same server: no follow-ups of its own, no experiments (the gating world has none). */
    SpecCaptureOptions followUp(Mode next, Path directory) {
        return new SpecCaptureOptions(bds, next, directory, vanillaPack, next == Mode.GATING ? null : experiments, protocol, true, false, false);
    }
}
