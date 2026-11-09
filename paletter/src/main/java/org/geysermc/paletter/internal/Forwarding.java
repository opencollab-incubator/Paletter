package org.geysermc.paletter.internal;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * How a block state or item variant of <em>any</em> bundled version maps to the canonical ids, which the newest
 * version defines (see {@link org.geysermc.paletter.Paletter#canonicalBlocks} and {@link org.geysermc.paletter.Paletter#canonicalItems}).
 *
 * @param canonical the canonical id, or -1 if the content does not exist (anymore) in the newest version
 * @param kind how it was found
 * @param detail what was changed on the way (state keys added, removed or reset, the rename), or, for
 *               {@link Kind#UNKNOWN}, why there is no equivalent. Empty for {@link Kind#EXACT}
 * @since 1.0.0
 */
public record Forwarding(int canonical, @NonNull Kind kind, @NonNull String detail) {
    /**
     * @since 1.0.0
     */
    public enum Kind {
        /**
         * The newest version has exactly this.
         */
        EXACT,
        /**
         * The block (or item) exists in the newest version with other state keys or values; state values it still has
         * were carried over, new keys got their default, removed ones were dropped.
         */
        ADAPTED,
        /**
         * The identifier was renamed (see the bundled renames); states were adapted as for {@link #ADAPTED}.
         */
        RENAMED,
        /**
         * No equivalent in the newest version; {@link Forwarding#canonical()} is -1.
         */
        UNKNOWN
    }

    public boolean known() {
        return canonical != -1;
    }

    static Forwarding exact(int canonical) {
        return new Forwarding(canonical, Kind.EXACT, "");
    }

    public static @NonNull Forwarding unknown(@NonNull String reason) {
        return new Forwarding(-1, Kind.UNKNOWN, reason);
    }
}
