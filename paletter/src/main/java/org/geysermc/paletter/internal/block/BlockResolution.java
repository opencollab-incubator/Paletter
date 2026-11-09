package org.geysermc.paletter.internal.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.block.BlockFallback;
import org.geysermc.paletter.block.RegisteredBlockState;

/**
 * The result of resolving a block state against a palette; {@link org.geysermc.paletter.BlockExplanation} is the public
 * form.
 *
 * @param requested the state that was asked for
 * @param block the resolved definition, or null if nothing suitable exists
 * @param kind how it was resolved
 */
@Internal
public record BlockResolution(@NonNull NbtMap requested, @Nullable RegisteredBlockState block, @NonNull Kind kind) {

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
         * ({@link BlockFallback#UNKNOWN} unless the policy set another) was used.
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
