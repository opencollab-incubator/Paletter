package dev.opencollab.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.vanilla.BedrockBlockType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Everything noteworthy that happened while compiling a palette: content left out for a version, anchors that
 * matched nothing, fallbacks that were used. Each {@link Entry} has a {@link Entry#code() code} to filter by and names the
 * content it is about. The {@link Paletter.Builder#reports reports} consumer of the {@link Paletter} gets every report; by
 * default it logs them. A report never changes: a palette extended with a layer has a new report.
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
     * How serious an {@link Entry} is. Closed: a compilation that cannot go on throws {@link PaletteException} instead
     * of reporting.
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

    // ---- Codes ----

    /**
     * {@link Entry#code()}: a block state of the data has no block in the version and no fallback; it is sent as air.
     *
     * @since 1.0.0
     */
    public static final String BLOCK_MISSING = "block_missing";
    /**
     * {@link Entry#code()}: how the block states of the data resolved for the version, as counts.
     *
     * @since 1.0.0
     */
    public static final String BLOCKS_RESOLVED = "blocks_resolved";
    /**
     * {@link Entry#code()}: content of the newest data that the version lacks and that has no fallback of its own, see
     * {@link #unknownBlocks()} and {@link #unknownItems()}.
     *
     * @since 1.0.0
     */
    public static final String CANONICAL_MISSING = "canonical_missing";
    /**
     * {@link Entry#code()}: an experiment toggle that gates content in the version, enabled or left disabled.
     *
     * @since 1.0.0
     */
    public static final String EXPERIMENT = "experiment";
    /**
     * {@link Entry#code()}: an override of a vanilla item the version does not have was skipped.
     *
     * @since 1.0.0
     */
    public static final String OVERRIDE_SKIPPED = "override_skipped";
    /**
     * {@link Entry#code()}: the block an item override names does not exist in the version.
     *
     * @since 1.0.0
     */
    public static final String OVERRIDE_BLOCK_MISSING = "override_block_missing";
    /**
     * {@link Entry#code()}: a creative placement's anchor matched nothing; the content is at the default position.
     *
     * @since 1.0.0
     */
    public static final String ANCHOR_UNMATCHED = "anchor_unmatched";
    /**
     * {@link Entry#code()}: an item has {@code replace_block_item}, but there is no custom block of its name.
     *
     * @since 1.0.0
     */
    public static final String REPLACED_BLOCK_MISSING = "replaced_block_missing";
    /**
     * {@link Entry#code()}: a stack or group of the creative layout is left out of the version's creative content.
     *
     * @since 1.0.0
     */
    public static final String CREATIVE_LEFT_OUT = "creative_left_out";
    /**
     * {@link Entry#code()}: the block state of a creative stack of the data was dropped.
     *
     * @since 1.0.0
     */
    public static final String CREATIVE_BLOCK_STATE_DROPPED = "creative_block_state_dropped";
    /**
     * {@link Entry#code()}: a component value is left out of the version, or sent in another form, because the version
     * cannot take it as given.
     *
     * @since 1.0.0
     */
    public static final String COMPONENT_CHANGED = "component_changed";
    /**
     * {@link Entry#code()}: the version is newer than every capture of the component encoding, which is extrapolated.
     *
     * @since 1.0.0
     */
    public static final String UNCAPTURED_PROTOCOL = "uncaptured_protocol";
    /**
     * {@link Entry#code()}: the voxel shape sent for a custom block follows a rule no capture of BDS confirms.
     *
     * @since 1.0.0
     */
    public static final String VOXEL_SHAPE_UNVERIFIED = "voxel_shape_unverified";
    /**
     * {@link Entry#code()}: a custom block names a culling shape no layer defines.
     *
     * @since 1.0.0
     */
    public static final String CULLING_SHAPE_MISSING = "culling_shape_missing";
    /**
     * {@link Entry#code()}: the palette is served for a protocol newer than the newest data.
     *
     * @since 1.0.0
     */
    public static final String NEWER_PROTOCOL = "newer_protocol";

    /**
     * One thing that happened while compiling. Entries are equal when all their parts are.
     *
     * @since 1.0.0
     */
    public static final class Entry {
        private final Level level;
        private final String source;
        private final String code;
        private final @Nullable String subject;
        private final String message;

        private Entry(Level level, String source, String code, @Nullable String subject, String message) {
            this.level = Objects.requireNonNull(level, "level");
            this.source = Objects.requireNonNull(source, "source");
            this.code = Objects.requireNonNull(code, "code");
            this.subject = subject;
            this.message = Objects.requireNonNull(message, "message");
        }

        /**
         * Internal: made by the compiler.
         *
         * @param level the severity
         * @param source see {@link #source()}
         * @param code see {@link #code()}
         * @param subject see {@link #subject()}
         * @param message the description
         * @return the entry
         */
        @Internal
        public static @NonNull Entry of(@NonNull Level level, @NonNull String source, @NonNull String code,
                                        @Nullable String subject, @NonNull String message) {
            return new Entry(level, source, code, subject, message);
        }

        /**
         * The severity.
         *
         * @return the level
         * @since 1.0.0
         */
        public @NonNull Level level() {
            return level;
        }

        /**
         * What produced the entry.
         *
         * @return a layer id, or a subsystem like {@code creative} or {@code blocks} for the vanilla data
         * @since 1.0.0
         */
        public @NonNull String source() {
            return source;
        }

        /**
         * What kind of entry this is, to filter or count entries by: one of the code constants of {@link PaletteReport}
         * ({@link PaletteReport#ANCHOR_UNMATCHED}, ...). A later release may add codes; compare with {@code equals}.
         *
         * @return the code
         * @since 1.0.0
         */
        public @NonNull String code() {
            return code;
        }

        /**
         * The content the entry is about.
         *
         * @return the identifier of the block, item, group or voxel shape it is about, or null if it is not about one
         * @since 1.0.0
         */
        public @Nullable String subject() {
            return subject;
        }

        /**
         * The description, for people: not a format to parse.
         *
         * @return the message
         * @since 1.0.0
         */
        public @NonNull String message() {
            return message;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof Entry other && level == other.level && source.equals(other.source)
                && code.equals(other.code) && Objects.equals(subject, other.subject) && message.equals(other.message);
        }

        @Override
        public int hashCode() {
            return Objects.hash(level, source, code, subject, message);
        }

        @Override
        public String toString() {
            return "[" + level + "] " + source + " " + code + ": " + message;
        }
    }

    /**
     * Returns the data version of the palette this report belongs to.
     *
     * @return the data version
     * @since 1.0.0
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
     * @since 1.0.0
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
     * @since 1.0.0
     */
    public @NonNull Set<BedrockItemVariant> unknownItems() {
        return unknownItems;
    }

    /**
     * Lists all entries.
     *
     * @return all entries, in the order they happened
     * @since 1.0.0
     */
    public @NonNull List<Entry> entries() {
        return entries;
    }

    /**
     * Lists the entries of level {@link Level#WARNING}.
     *
     * @return the warnings, in the order they happened
     * @since 1.0.0
     */
    public @NonNull List<Entry> warnings() {
        return entries.stream().filter(entry -> entry.level() == Level.WARNING).toList();
    }
}
