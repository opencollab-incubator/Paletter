package dev.opencollab.paletter.tools;

/**
 * What the tools print about themselves, spelled one way: {@code warning: ...} and {@code note: ...} on standard output,
 * {@code error: ...} on standard error. The maintainer docs tell a reader to look for the {@code warning:} lines, so the spelling is
 * an interface.
 */
public final class Console {
    private Console() {
    }

    /** The text of a warning, for a message that is returned before it is printed. */
    public static String warning(String text) {
        return "warning: " + text;
    }

    /** The text of a note. */
    public static String note(String text) {
        return "note: " + text;
    }

    public static void warn(String text) {
        System.out.println(warning(text));
    }

    public static void inform(String text) {
        System.out.println(note(text));
    }

    public static void error(String text) {
        System.err.println("error: " + text);
    }
}
