package dev.opencollab.paletter.tools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A BDS build number, the one place its format is written down: {@code 1.<minor>.<patch>.<revision>}, four dotted numbers of
 * at most nine digits that start with {@code 1.} ({@code 1.26.63.1}). Builds name directories and zips, so nothing else is
 * one. Leading zeros stay in the text: older builds have them ({@code 1.21.73.01} is the name of its zip).
 * <p>
 * The data version of a build is its line: the minor and the patch rounded down to tens ({@code 1.26.63.1} is {@code 26.60},
 * hotfixes share their release's name). A preview reports its revision as a beta ({@code 1.26.70.24} reports
 * {@code 1.26.70-beta24}).
 * <p>
 * The workflows check build numbers with {@link #REGEX} (the {@code BUILD_RE} of {@code .github/workflows/recapture.yml}); a
 * test keeps the two equal. A Mojang change to the format (no {@code 1.}, three parts, another preview suffix) fails here.
 *
 * @param text the build number as written
 */
public record BuildNumber(String text, int minor, int patch, int revision) {
    /** The format as an extended regular expression, shared with the workflows. */
    public static final String REGEX = "^1(\\.[0-9]{1,9}){3}$";
    private static final Pattern BUILD = Pattern.compile("1\\.([0-9]{1,9})\\.([0-9]{1,9})\\.([0-9]{1,9})");
    private static final Pattern REPORTED_PREVIEW = Pattern.compile("(1\\.[0-9]{1,9}\\.[0-9]{1,9})-beta([0-9]{1,9})");
    private static final Pattern DATA_VERSION = Pattern.compile("([0-9]{1,9})\\.([0-9]{1,9})");

    /**
     * The build number, checked.
     *
     * @throws IllegalArgumentException for anything else (a separator, {@code ..}, a suffix, another major)
     */
    public static BuildNumber parse(String text) {
        Matcher m = text == null ? null : BUILD.matcher(text);
        if (m == null || !m.matches()) {
            throw new IllegalArgumentException("Not a BDS build number (1.<minor>.<patch>.<revision>, e.g. 1.26.63.1): " + text);
        }
        return new BuildNumber(text, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    }

    /** {@link #parse} that returns the checked text, for a build number that names a file or directory. */
    public static String require(String text) {
        return parse(text).text();
    }

    /** Whether the text is a build number. */
    public static boolean isBuild(String text) {
        return text != null && BUILD.matcher(text).matches();
    }

    /**
     * The build of a version BDS reports: a release reports its build number, a preview {@code 1.<minor>.<patch>-beta<revision>}.
     *
     * @throws IllegalArgumentException for anything else
     */
    public static BuildNumber parseReported(String reported) {
        Matcher m = reported == null ? null : REPORTED_PREVIEW.matcher(reported);
        return m != null && m.matches() ? parse(m.group(1) + "." + m.group(2)) : parse(reported);
    }

    /** Whether the text is a version BDS reports ({@link #parseReported}). */
    public static boolean isReported(String reported) {
        return isBuild(reported) || reported != null && REPORTED_PREVIEW.matcher(reported).matches();
    }

    /** The data version (the bundled version name) of the build: {@code 1.26.63.1} is {@code 26.60}. */
    public String dataVersion() {
        return minor + "." + (patch / 10 * 10);
    }

    /** Whether the build is of a data version's line: {@code 1.26.65.1} is of {@code 26.60}; a name that is no data version is of none. */
    public boolean inLine(String dataVersion) {
        Matcher m = dataVersion == null ? null : DATA_VERSION.matcher(dataVersion);
        return m != null && m.matches() && Integer.parseInt(m.group(1)) == minor && Integer.parseInt(m.group(2)) / 10 == patch / 10;
    }

    /** The version BDS reports for the build: a release its build number, a preview its revision as a beta. */
    public String reported(boolean preview) {
        int last = text.lastIndexOf('.');
        return preview ? text.substring(0, last) + "-beta" + text.substring(last + 1) : text;
    }

    @Override
    public String toString() {
        return text;
    }
}
