package dev.opencollab.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.vanilla.BedrockBlockType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything noteworthy that happened while compiling a palette: content left out for a version, anchors that
 * matched nothing, fallbacks that were used. Compilation never logs by itself; consumers decide what to show.
 * A report never changes: a palette extended with a layer has a new report.
 *
 * @since 1.0.0
 */
public final class PaletteReport {
    private final DataVersion version;
    private final List<Entry> entries;
    private final Set<BedrockBlockType> unknownBlocks;
    private final Set<BedrockItemVariant> unknownItems;

    /**
     * Internal: made by the compiler; copies what it is given.
     *
     * @param version the data version of the palette the report belongs to
     * @param entries the entries, in the order they happened
     * @param unknownBlocks see {@link #unknownBlocks()}
     * @param unknownItems see {@link #unknownItems()}
     */
    @Internal
    public PaletteReport(@NonNull DataVersion version, @NonNull List<Entry> entries, @NonNull Set<BedrockBlockType> unknownBlocks,
                         @NonNull Set<BedrockItemVariant> unknownItems) {
        this.version = version;
        this.entries = List.copyOf(entries);
        this.unknownBlocks = Collections.unmodifiableSet(new LinkedHashSet<>(unknownBlocks));
        this.unknownItems = Collections.unmodifiableSet(new LinkedHashSet<>(unknownItems));
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
        WARNING
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
     * Returns the data version of the palette this report belongs to.
     *
     * @return the data version
     */
    public @NonNull DataVersion version() {
        return version;
    }

    /**
     * Lists the blocks this palette's version lacks and no specific fallback covers. A default fallback
     * ({@code Fallbacks.Builder#defaultBlock}) does not count as covering them: they are what it is sent for. Blocks
     * behind a disabled experiment are included. Only the palette compiled from the vanilla data and its layers has
     * them; the one-entry notes handed to the {@code reports} consumer have none.
     *
     * @return the blocks of the newest canonical set that are missing, in canonical order
     */
    public @NonNull Set<BedrockBlockType> unknownBlocks() {
        return unknownBlocks;
    }

    /**
     * Lists the item variants this palette's version lacks and no specific fallback covers, so a missing aux variant is
     * exact. A default fallback ({@code Fallbacks.Builder#defaultItem}) does not count as covering them. Items behind a
     * disabled experiment and items removed with {@code Paletter.Builder#removeItems} are included. Notes handed to the
     * {@code reports} consumer have none.
     *
     * @return the item variants of the newest canonical set that are missing, in canonical order
     */
    public @NonNull Set<BedrockItemVariant> unknownItems() {
        return unknownItems;
    }

    /**
     * Lists all entries.
     *
     * @return all entries, in the order they happened
     */
    public @NonNull List<Entry> entries() {
        return entries;
    }

    /**
     * Lists the entries of level {@link Level#WARNING}.
     *
     * @return the warnings, in the order they happened
     */
    public @NonNull List<Entry> warnings() {
        return entries.stream().filter(entry -> entry.level() == Level.WARNING).toList();
    }
}
