package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.annotation.Internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything noteworthy that happened while compiling a palette: content left out for a version, anchors that
 * matched nothing, fallbacks that were used. Compilation never logs by itself; consumers decide what to show.
 *
 * @since 0.1.0
 */
public final class PaletteReport {
    private final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());

    @Internal
    public PaletteReport() {
    }

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
     * @param level the severity
     * @param source what produced the entry: a layer id, or a subsystem like {@code creative} or {@code blocks}
     * @param message the description
     */
    public record Entry(@NonNull Level level, @NonNull String source, @NonNull String message) {
        @Override
        public String toString() {
            return "[" + level + "] " + source + ": " + message;
        }
    }

    @Internal
    public void info(@NonNull String source, @NonNull String message) {
        entries.add(new Entry(Level.INFO, source, message));
    }

    @Internal
    public void warn(@NonNull String source, @NonNull String message) {
        entries.add(new Entry(Level.WARNING, source, message));
    }

    @Internal
    public void error(@NonNull String source, @NonNull String message) {
        entries.add(new Entry(Level.ERROR, source, message));
    }

    public @NonNull List<Entry> entries() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    public @NonNull List<Entry> warnings() {
        return entries().stream().filter(entry -> entry.level() == Level.WARNING).toList();
    }

    /**
     * @return the entries of level {@link Level#ERROR}
     */
    public @NonNull List<Entry> errors() {
        return entries().stream().filter(entry -> entry.level() == Level.ERROR).toList();
    }

    void addAll(PaletteReport other) {
        entries.addAll(other.entries());
    }
}
