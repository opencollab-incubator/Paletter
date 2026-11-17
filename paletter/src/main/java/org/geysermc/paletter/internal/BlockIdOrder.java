package org.geysermc.paletter.internal;

import java.util.Comparator;
import java.util.Locale;

/**
 * The order in which BDS numbers custom blocks: descending by the name of the block's file, and the file listing of Windows
 * BDS sorts names in upper case, so {@code _} comes after every letter ({@code b_x} before {@code block_x} when listed, and
 * after it when descending). Verified on every captured spec capture (1190 blocks and more, no exception). Paletter has no
 * files and uses the identifier, which has the same order for the usual layout of one file per block named after it.
 */
public final class BlockIdOrder {
    /** Custom block identifiers from the lowest block id (10000) up. */
    public static final Comparator<String> DESCENDING = Comparator.comparing((String identifier) -> identifier.toUpperCase(Locale.ROOT))
        .thenComparing(Comparator.<String>naturalOrder()).reversed();

    private BlockIdOrder() {
    }
}
