package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;

/**
 * The result of resolving a block state against a palette.
 *
 * @param requested the state that was asked for
 * @param block the resolved definition, or null if nothing suitable exists
 * @param kind how it was resolved
 * @since 1.0.0
 */
public record BlockResolution(@NonNull NbtMap requested, @Nullable PaletteBlock block, @NonNull Kind kind) {

    public enum Kind {
        /**
         * The state exists exactly.
         */
        EXACT,
        /**
         * Same block, but state values were added, removed or reset to fit this version, e.g. stairs before
         * {@code minecraft:corner} existed.
         */
        ADAPTED,
        /**
         * The block does not exist in this version; a fallback block was used.
         */
        FALLBACK,
        /**
         * The block does not exist in this version and has no fallback of its own; the palette's default fallback
         * ({@link BlockFallback#UNKNOWN} unless a layer set {@link org.geysermc.paletter.data.FallbackDefaults}) was used.
         *
         * @since 1.0.0
         */
        DEFAULT,
        /**
         * Nothing suitable exists. {@link #block()} is null.
         */
        MISSING
    }

    public boolean isExact() {
        return kind == Kind.EXACT;
    }
}
