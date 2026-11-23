package dev.opencollab.paletter.tools;

/**
 * How every check of committed output says it is out of date: the file that differs and the command that writes it again, on
 * standard error. The checks then return exit code 1. (The generators of Java sources name their files the same way in
 * {@code GeneratedSources}.)
 */
public final class Stale {
    private Stale() {
    }

    /** Says that {@code file} is not what {@code command} would write. */
    public static void print(Object file, String command) {
        System.err.println(file + " is stale: run " + command);
    }
}
