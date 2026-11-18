package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.annotation.Internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything noteworthy that happened while compiling a palette: content left out for a version, anchors that
 * matched nothing, fallbacks that were used. Compilation never logs by itself; consumers decide what to show.
 * <p>
 * Thread safety: a report may be read from any thread while entries are added; {@link #entries()} and the filtered views
 * return snapshots. A palette's report is complete once the palette is returned. Obtain one with {@link Palette#report()}
 * or the consumer of {@link Paletter.Builder#reports(java.util.function.Consumer)}; errors in a layer that
 * {@link Palette#extend(PaletteLayer)} did not apply are {@link #errors()} here.
 *
 * @since 1.0.0
 */
public final class PaletteReport {
    private final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());

    /**
     * Internal: creates an empty report.
     */
    @Internal
    public PaletteReport() {
    }

    /**
     * How serious an {@link Entry} is.
     *
     * @since 1.0.0
     */
    public enum Level {
        /**
         * Expected for older versions, e.g. a new item that is left out of the creative inventory.
         */
        INFO,
        /**
         * Likely a mistake in a layer, e.g. a creative anchor that matched nothing.
         */
        WARNING,
        /**
         * Content was not applied: a session layer that failed to compile (see {@link Palette#extend}) leaves the
         * palette it was applied to unchanged.
         */
        ERROR
    }

    /**
     * One thing that happened while compiling.
     *
     * @param level the severity
     * @param source what produced the entry: a layer id, or a subsystem like {@code creative} or {@code blocks}
     * @param message the description
     * @since 1.0.0
     */
    public record Entry(@NonNull Level level, @NonNull String source, @NonNull String message) {
        @Override
        public String toString() {
            return "[" + level + "] " + source + ": " + message;
        }
    }

    /**
     * Internal: adds an {@link Level#INFO} entry.
     *
     * @param source what produced the entry
     * @param message the description
     */
    @Internal
    public void info(@NonNull String source, @NonNull String message) {
        entries.add(new Entry(Level.INFO, source, message));
    }

    /**
     * Internal: adds a {@link Level#WARNING} entry.
     *
     * @param source what produced the entry
     * @param message the description
     */
    @Internal
    public void warn(@NonNull String source, @NonNull String message) {
        entries.add(new Entry(Level.WARNING, source, message));
    }

    /**
     * Internal: adds an {@link Level#ERROR} entry.
     *
     * @param source what produced the entry
     * @param message the description
     */
    @Internal
    public void error(@NonNull String source, @NonNull String message) {
        entries.add(new Entry(Level.ERROR, source, message));
    }

    /**
     * Lists every entry, in the order they happened.
     *
     * @return a snapshot of all entries
     * @since 1.0.0
     */
    public @NonNull List<Entry> entries() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    /**
     * Lists the entries of level {@link Level#WARNING}.
     *
     * @return a snapshot of the warnings
     * @since 1.0.0
     */
    public @NonNull List<Entry> warnings() {
        return entries().stream().filter(entry -> entry.level() == Level.WARNING).toList();
    }

    /**
     * Lists the entries of level {@link Level#ERROR}.
     *
     * @return a snapshot of the errors
     * @since 1.0.0
     */
    public @NonNull List<Entry> errors() {
        return entries().stream().filter(entry -> entry.level() == Level.ERROR).toList();
    }

    void addAll(PaletteReport other) {
        entries.addAll(other.entries());
    }
}
