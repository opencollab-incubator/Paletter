package dev.opencollab.paletter.tools.behaviour;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The section {@code ## Version changes to check} of {@code data/behaviour/REPORT.md}: what the generator flagged, every
 * block and item listed. The generator writes it (also after {@code retire}) and {@code checkBehaviour} checks it; the rest
 * of the report is written by {@code behaviour import}, which needs the third-party checkouts.
 */
final class ChangesSection {
    static final String REPORT = "data/behaviour/REPORT.md";
    static final String HEADING = "## Version changes to check";

    private ChangesSection() {
    }

    static String render(List<BehaviourComments.Flag> flags, List<BehaviourModel.Recapture> recaptures) {
        StringBuilder out = new StringBuilder(HEADING).append("\n\nWhat the generated classes of the versions flag, every block and item "
            + "listed (the classes have the same as comments). By the provenance rule BDS data wins and disputes are marked: a value a "
            + "direct BDS capture gave is never replaced by a third-party dump; where only dumps have a key, the newest dump of the "
            + "version is used (the version before is no evidence for this one) and flagged here.\n\n");
        out.append("### Needs recapture\n\nA newer third-party dump contradicts a value a direct BDS capture of an older version gave. "
            + "The BDS value is kept and the dump's value is a dispute of it; a new BDS capture of the version settles it.\n\n");
        if (recaptures.isEmpty()) {
            out.append("None.\n\n");
        } else {
            Map<String, List<String>> rows = new TreeMap<>();
            for (BehaviourModel.Recapture recapture : recaptures) {
                String row = recapture.version() + " | `" + recapture.key() + "` | " + BehaviourComments.label(recapture.kept().kept().capture())
                    + ": " + BehaviourLiterals.report(recapture.kept().kept().value()) + " | " + BehaviourComments.label(recapture.kept().dump())
                    + ": " + BehaviourLiterals.report(recapture.kept().dumpValue());
                rows.computeIfAbsent(BehaviourComments.safe(row), r -> new ArrayList<>()).add(recapture.id().replace("minecraft:", ""));
            }
            out.append("| Version | Key | Kept | Dump says | States / items |\n|---|---|---|---|---|\n");
            rows.forEach((row, ids) -> out.append("| ").append(row).append(" | ").append(ids.size()).append(": ")
                .append(String.join(", ", ids)).append(" |\n"));
            out.append('\n');
        }
        for (BehaviourComments.Kind kind : BehaviourComments.Kind.values()) {
            out.append("### ").append(kind.title).append("\n\n");
            Map<String, List<String>> rows = new LinkedHashMap<>();
            flags.stream().filter(flag -> flag.kind() == kind).forEach(flag -> rows.computeIfAbsent(flag.version() + " | `" + flag.key() + "` | "
                + BehaviourComments.safe(flag.change().substring(flag.change().indexOf(' ') + 1)).replace("|", "\\|") + " | " + flag.deciders(),
                r -> new ArrayList<>())
                .add(flag.id().replace("minecraft:", "")));
            if (rows.isEmpty()) {
                out.append("None.\n\n");
                continue;
            }
            out.append("| Version | Key | Change | Decided by | Blocks / items |\n|---|---|---|---|---|\n");
            new TreeMap<>(rows).forEach((row, ids) -> out.append("| ").append(row).append(" | ").append(ids.size()).append(": ")
                .append(String.join(", ", ids)).append(" |\n"));
            out.append('\n');
        }
        return out.toString();
    }

    /** @return the report with the section replaced (or added before {@code ## Disputed values}, else at the end) */
    static String replace(String report, String section) {
        int start = report.indexOf("\n" + HEADING + "\n");
        if (start >= 0) {
            int end = report.indexOf("\n## ", start + 1);
            return report.substring(0, start + 1) + section + (end < 0 ? "" : report.substring(end + 1));
        }
        int disputes = report.indexOf("\n## Disputed values\n");
        return disputes < 0 ? report + "\n" + section : report.substring(0, disputes + 1) + section + report.substring(disputes + 1);
    }

    /**
     * Brings the section of the report to {@code section}, or only says whether it differs.
     *
     * @return true if the report differs (a check: it is stale), false if it is current or there is no report
     */
    static boolean sync(Path root, String section, boolean check) throws IOException {
        Path report = root.resolve(REPORT);
        if (!Files.exists(report)) {
            return false;
        }
        String text = Files.readString(report).replace("\r\n", "\n");
        String updated = replace(text, section);
        if (updated.equals(text)) {
            return false;
        }
        if (!check) {
            Files.writeString(report, updated);
        }
        return true;
    }
}
