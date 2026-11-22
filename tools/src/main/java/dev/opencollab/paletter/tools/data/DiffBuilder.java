package dev.opencollab.paletter.tools.data;

import java.util.Collection;
import java.util.LinkedHashSet;

/** The base of the classes that fill a {@link Diff}: each compares one kind of data and writes its sections. */
abstract class DiffBuilder {
    final Diff diff;

    DiffBuilder() {
        this(new Diff());
    }

    DiffBuilder(Diff diff) {
        this.diff = diff;
    }

    Diff.Section section(String title) {
        return diff.section(title);
    }

    void put(String title, Collection<String> lines) {
        section(title).differences.addAll(lines);
    }

    /** Like {@link #put}, but nothing for no lines. */
    void putIfAny(String title, Collection<String> lines) {
        if (!lines.isEmpty()) {
            put(title, lines);
        }
    }

    /** Like {@link #put}, repeated lines once. */
    void putDistinct(String title, Collection<String> lines) {
        put(title, new LinkedHashSet<>(lines));
    }
}
