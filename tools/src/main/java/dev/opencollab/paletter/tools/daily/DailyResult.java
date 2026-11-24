package dev.opencollab.paletter.tools.daily;

import dev.opencollab.paletter.tools.BuildNumber;
import dev.opencollab.paletter.tools.DataPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code daily result}: what the capture job of the daily check hands to its publish job, from the exit codes of
 * {@code update} and of the code generation, the run's report and the patch of the data paths. The publish job reads the
 * values of {@code result.txt} against strict patterns:
 * <ul>
 *     <li>{@code update}: the exit code of {@code update}, or 3 when it wrote the data (0 or 2) and the code generation
 *     failed on it;</li>
 *     <li>{@code differs}: false for a clean run (exit 0, "OK: no changes", and a patch that holds at most the report),
 *     or for a verification that passed;</li>
 *     <li>{@code hash}: the first 16 hex digits of the sha256 of the report without its date, work directory and server
 *     lines (a standing failure is not reported twice), or the run id when there is no report;</li>
 *     <li>{@code addon}: whether the patch changes an input of the spec add-on ({@link DataPaths#ADDON_INPUTS}).</li>
 * </ul>
 * The patch is deleted when there is nothing to publish (a clean run, a failure, a verification), so a failure on the way
 * can never look like data.
 */
public final class DailyResult {
    /** The exit code of an update whose data the code generation failed on. */
    public static final int EXIT_CODEGEN = 3;
    /** The lines of a report that change every run. */
    private static final List<String> VOLATILE = List.of("- **Date**", "- **Work directory", "- **Server");
    private static final Pattern VERDICT = Pattern.compile("(?m)^(?:OK|Changed|Failed): .*$");
    private static final Pattern PATCHED = Pattern.compile("(?m)^diff --git a/(\\S+) b/(\\S+)$");
    private static final Pattern REPORT_ONLY = Pattern.compile("data/bds/[^/]+/update-report\\.md");

    /** The values of {@code result.txt}. */
    public record Result(String version, int update, boolean differs, String hash, boolean addon) {
        public String text() {
            return "version=" + version + "\nupdate=" + update + "\ndiffers=" + differs + "\nhash=" + hash + "\naddon=" + addon + "\n";
        }
    }

    private DailyResult() {
    }

    /**
     * The result of a run, and the patch deleted when it is not to be published.
     *
     * @param report the run's report, which may be missing (the run died before it)
     * @param patch the patch of the data paths, which may be missing
     * @param runId the id of the workflow run, the hash when there is no report
     */
    public static Result of(String version, int updateExit, int codegenExit, boolean verifyOnly, Path report, Path patch, String runId)
        throws IOException {
        BuildNumber.require(version);
        if (!runId.matches("[0-9]{1,20}")) {
            throw new IllegalArgumentException("Not a run id: " + runId);
        }
        String text = Files.isRegularFile(report) ? Files.readString(report) : null;
        String hash = text == null ? runId : hash(text);
        Matcher verdict = text == null ? null : VERDICT.matcher(text);
        String firstVerdict = verdict != null && verdict.find() ? verdict.group() : null;
        int code = updateExit;
        boolean wrote = !verifyOnly && (code == 0 || code == 2);
        if (wrote && codegenExit != 0) {
            code = EXIT_CODEGEN;
        }
        List<String> paths = Files.isRegularFile(patch) ? paths(Files.readString(patch, StandardCharsets.UTF_8)) : List.of();
        boolean differs;
        boolean publish;
        if (verifyOnly) {
            differs = code != 0;
            publish = false;
        } else if (code == 0 && "OK: no changes".equals(firstVerdict) && paths.stream().allMatch(path -> REPORT_ONLY.matcher(path).matches())) {
            differs = false;
            publish = false;
        } else {
            differs = true;
            publish = code == 0 || code == 2;
        }
        if (!publish) {
            Files.deleteIfExists(patch);
        }
        boolean addon = publish && paths.stream().anyMatch(DataPaths.ADDON_INPUTS::contains);
        return new Result(version, code, differs, hash, addon);
    }

    /** The paths a git patch changes (its {@code diff --git} lines). */
    static List<String> paths(String patch) {
        List<String> paths = new ArrayList<>();
        Matcher matcher = PATCHED.matcher(patch);
        while (matcher.find()) {
            paths.add(matcher.group(2));
        }
        return paths;
    }

    /** The first 16 hex digits of the sha256 of the report's lines without the ones that change every run. */
    static String hash(String report) {
        StringBuilder kept = new StringBuilder();
        for (String line : report.split("\n", -1)) {
            if (VOLATILE.stream().noneMatch(line::startsWith)) {
                kept.append(line).append('\n');
            }
        }
        // split keeps the empty text after a final newline: the lines as `grep -v` printed them, without that one
        if (report.endsWith("\n")) {
            kept.setLength(kept.length() - 1);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(kept.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
